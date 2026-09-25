package local.ahflipper;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.BiConsumer;
import java.util.zip.GZIPInputStream;

final class AuctionScanner {
    record Flip(String uuid, String name, long cost, long target, long profit, int comparables,
                int soldReferences, String volumeInfo) {}
    private record Auction(String uuid, String seller, String name, String key, String itemId,
                           boolean recombobulated, long start, long cost) {}
    private record ItemIdentity(String key, String id, boolean recombobulated) {}
    private record Page(JsonObject json, String lastModified, String date, long receivedAt) {}

    private static final String URL = "https://api.hypixel.net/v2/skyblock/auctions?page=";
    private static final String ENDED_URL = "https://api.hypixel.net/v2/skyblock/auctions_ended";
    private static final Set<String> IGNORE_NBT = Set.of(
        "id", "uuid", "timestamp", "petInfo", "enchantments", "drill_fuel", "stored_drill_fuel",
        "originTag", "anvil_uses", "dungeon_skill_req", "spawnedFor", "bossId"
    );
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12)).build();
    private final ExecutorService pageExecutor = Executors.newFixedThreadPool(6, Thread.ofVirtual().name("local-ah-page-", 0).factory());
    private final Supplier<LocalAhFlipper.Config> settings;
    private final Consumer<Flip> onFlip;
    private final SaleHistory sales = new SaleHistory();
    private final ItemCategories categories = new ItemCategories();
    private final CoflVolume coflVolume = new CoflVolume();
    private final Object wake = new Object();
    private volatile boolean active;
    private volatile boolean scanRequested;
    private volatile String lastError = "none";
    private volatile Instant lastScan;
    private volatile int listingCount;
    private volatile int lastAlerts;
    private volatile int lastNew;
    private volatile long lastUpdate;
    private volatile long lastEndedUpdate;
    private Map<String, Auction> previous = Map.of();
    private Map<String, List<Auction>> previousGroups = Map.of();
    private String lastModified;
    private String lastServerDate;
    private long lastPageReceivedAt;
    private long nextProbeAt;
    private long burstUntil;
    private long announcedUpdate;
    private final Set<String> announcedIds = new HashSet<>();

    AuctionScanner(Supplier<LocalAhFlipper.Config> settings, Consumer<Flip> onFlip) {
        this.settings = settings;
        this.onFlip = onFlip;
    }

    void start() {
        Thread.ofVirtual().name("local-ah-item-categories").start(categories::refresh);
        Thread.ofVirtual().name("local-ah-flipper-scanner").start(this::run);
        Thread.ofVirtual().name("local-ah-flipper-sales").start(this::runEnded);
    }

    void setActive(boolean value) {
        if (value && !active) nextProbeAt = 0;
        active = value;
        synchronized (wake) { wake.notifyAll(); }
    }

    void scanSoon() {
        scanRequested = true;
        synchronized (wake) { wake.notifyAll(); }
    }

    String status() {
        return (active ? "Scanning Hypixel" : "Idle") + ", " + listingCount + " BIN listings, " +
            lastNew + " newly listed, " + sales.count() + " recorded sales, volume coverage " + sales.volumeCoveragePercent() +
            "% (local fallback " + (sales.volumeReady() ? "ready" : "warming up") + "), " +
            coflVolume.status() + ", " + lastAlerts + " candidates in last scan, updated " +
            (lastScan == null ? "never" : lastScan) +
            ("none".equals(lastError) ? "" : ", last error: " + lastError);
    }

    private void run() {
        while (true) {
            if (!active) {
                synchronized (wake) {
                    try { wake.wait(3_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                }
                continue;
            }
            long plannedProbeAt;
            boolean manualProbe;
            synchronized (wake) {
                try {
                    long delay = nextProbeAt - System.currentTimeMillis();
                    if (delay > 0 && !scanRequested) wake.wait(delay);
                    if (!active) continue;
                    if (!scanRequested && System.currentTimeMillis() < nextProbeAt) continue;
                    plannedProbeAt = nextProbeAt;
                    manualProbe = scanRequested;
                    scanRequested = false;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            try {
                if (scan()) {
                    // The next cached public snapshot is normally released about 60 seconds later.
                    long untilExpected = nextRefreshDelay();
                    nextProbeAt = System.currentTimeMillis() + Math.max(500, untilExpected - 900);
                    burstUntil = nextProbeAt + 8_000;
                } else {
                    long now = System.currentTimeMillis();
                    nextProbeAt = manualProbe && now < plannedProbeAt ? plannedProbeAt :
                        now + (now < burstUntil ? 350 : 2_000);
                }
                lastError = "none";
            } catch (Exception e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                LocalAhFlipper.LOG.warn("Auction scan failed", e);
                nextProbeAt = System.currentTimeMillis() + (lastError.contains("HTTP 429") ? 10_000 : 2_000);
            }
        }
    }

    private long nextRefreshDelay() {
        if (lastModified == null) return settings.get().pollSeconds * 1_000L;
        try {
            long next = ZonedDateTime.parse(lastModified, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() +
                settings.get().pollSeconds * 1_000L;
            if (lastServerDate != null) {
                long serverDate = ZonedDateTime.parse(lastServerDate, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
                return next - serverDate - (System.currentTimeMillis() - lastPageReceivedAt);
            }
            return next - System.currentTimeMillis();
        } catch (Exception e) {
            return settings.get().pollSeconds * 1_000L;
        }
    }

    private void runEnded() {
        sales.load();
        while (true) {
            if (active) {
                try {
                    fetchEnded();
                } catch (Exception e) {
                    LocalAhFlipper.LOG.warn("Could not refresh sold auction references", e);
                }
            }
            synchronized (wake) {
                try { wake.wait(active ? 30_000 : 3_000); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    private void fetchEnded() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(ENDED_URL))
            .timeout(Duration.ofSeconds(25)).header("User-Agent", "LocalAhFlipper/0.1 personal")
            .GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("Hypixel ended-auction HTTP " + response.statusCode());
        JsonObject result = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!result.get("success").getAsBoolean()) throw new IllegalStateException("Hypixel ended-auction success=false");
        long update = result.get("lastUpdated").getAsLong();
        if (update == lastEndedUpdate) return;
        int added = 0;
        for (var element : result.getAsJsonArray("auctions")) {
            JsonObject raw = element.getAsJsonObject();
            if (!raw.has("bin") || !raw.get("bin").getAsBoolean() || raw.get("price").getAsLong() <= 0) continue;
            try {
                ItemIdentity item = identityForItem(raw.get("item_bytes").getAsString(), null);
                if (item == null) continue;
                if (sales.add(raw.get("auction_id").getAsString(), item.key(), raw.get("price").getAsLong(),
                    raw.get("timestamp").getAsLong(), raw.get("seller").getAsString())) added++;
            } catch (Exception e) {
                LocalAhFlipper.LOG.debug("Skipped malformed ended auction", e);
            }
        }
        sales.markCovered(update);
        lastEndedUpdate = update;
        if (added > 0) LocalAhFlipper.LOG.info("Recorded {} new sold BIN references ({} retained)", added, sales.count());
    }

    private boolean scan() throws Exception {
        long scanStarted = System.currentTimeMillis();
        Map<String, Auction> now = new HashMap<>(50_000);
        Map<String, String> newItemBytes = new ConcurrentHashMap<>();
        Set<String> earlyAlerts = new HashSet<>();
        long[] firstAlertAt = {0};
        Page firstPage = fetchPage(0, lastModified, (update, raw) -> {
            if (update == lastUpdate || previous.isEmpty() || !raw.has("uuid") || !raw.has("bin") ||
                !raw.get("bin").getAsBoolean() || previous.containsKey(raw.get("uuid").getAsString())) return;
            if (announcedUpdate != update) {
                announcedIds.clear();
                announcedUpdate = update;
            }
            Auction auction;
            try { auction = decode(raw); }
            catch (Exception e) {
                LocalAhFlipper.LOG.debug("Skipped malformed new auction", e);
                return;
            }
            if (auction == null) return;
            now.put(auction.uuid, auction);
            newItemBytes.put(auction.uuid, raw.get("item_bytes").getAsString());
            Flip flip = evaluate(auction, previousGroups.get(auction.key), settings.get(), sales, categories);
            if (flip != null && announcedIds.add(auction.uuid)) {
                if (firstAlertAt[0] == 0) firstAlertAt[0] = System.currentTimeMillis();
                emitIfVolume(auction, newItemBytes.get(auction.uuid), flip);
                earlyAlerts.add(auction.uuid);
            }
        });
        if (firstPage == null) return false;
        JsonObject first = firstPage.json();
        if (!first.get("success").getAsBoolean()) throw new IllegalStateException("Hypixel returned success=false");
        long update = first.get("lastUpdated").getAsLong();
        if (update == lastUpdate) return false;
        int pages = first.get("totalPages").getAsInt();
        if (pages <= 0 || pages > 200) throw new IllegalStateException("Unexpected auction page count " + pages);
        if (announcedUpdate != update) {
            announcedIds.clear();
            announcedUpdate = update;
        }
        List<Future<Map<String, Auction>>> pending = new ArrayList<>(pages - 1);
        for (int page = 1; page < pages; page++) {
            final int pageNumber = page;
            pending.add(pageExecutor.submit(() -> {
                Page response = fetchPage(pageNumber, null, null);
                if (response == null || !response.json().get("success").getAsBoolean() ||
                    response.json().get("lastUpdated").getAsLong() != update)
                    throw new IllegalStateException("Auction snapshot changed during scan on page " + pageNumber);
                Map<String, Auction> listings = new HashMap<>();
                addPage(response.json(), listings, newItemBytes);
                return listings;
            }));
        }
        long earlyMs = firstAlertAt[0] == 0 ? -1 : firstAlertAt[0] - scanStarted;
        addPage(first, now, newItemBytes);
        long firstPageMs = System.currentTimeMillis() - scanStarted;
        for (Future<Map<String, Auction>> future : pending) {
            if (!active) return false;
            now.putAll(future.get());
        }
        if (!active) return false;
        Map<String, List<Auction>> groups = group(now);
        int alerts = earlyAlerts.size();
        int newListings = 0;
        if (!previous.isEmpty()) {
            for (Auction auction : now.values()) {
                if (previous.containsKey(auction.uuid)) continue;
                newListings++;
                Flip flip = evaluate(auction, groups.get(auction.key), settings.get(), sales, categories);
                if (flip != null && announcedIds.add(auction.uuid)) {
                    emitIfVolume(auction, newItemBytes.get(auction.uuid), flip);
                    alerts++;
                }
            }
        }
        previous = now;
        previousGroups = groups;
        listingCount = now.size();
        lastAlerts = alerts;
        lastNew = newListings;
        lastUpdate = update;
        lastModified = firstPage.lastModified();
        lastServerDate = firstPage.date();
        lastPageReceivedAt = firstPage.receivedAt();
        lastScan = Instant.now();
        LocalAhFlipper.LOG.info("Scanned {} Hypixel auction pages, {} BIN listings, {} new, {} candidates ({} from page 0, public lag {} ms, first candidate {} ms, page 0 {} ms, full {} ms)",
            pages, now.size(), newListings, alerts, earlyAlerts.size(), firstPage.receivedAt() - update,
            earlyMs, firstPageMs, System.currentTimeMillis() - scanStarted);
        return true;
    }

    private static Map<String, List<Auction>> group(Map<String, Auction> listings) {
        Map<String, List<Auction>> groups = new HashMap<>();
        for (Auction auction : listings.values())
            groups.computeIfAbsent(auction.key, ignored -> new ArrayList<>()).add(auction);
        return groups;
    }

    private Page fetchPage(int page, String unchangedSince, BiConsumer<Long, JsonObject> onAuction) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(URL + page))
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", "LocalAhFlipper/0.1 personal")
            .header("Accept-Encoding", "gzip");
        if (unchangedSince != null) request.header("If-Modified-Since", unchangedSince);
        HttpResponse<InputStream> response = http.send(request.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() == 304) {
            response.body().close();
            return null;
        }
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IllegalStateException("Hypixel HTTP " + response.statusCode() + " on page " + page);
        }
        try (InputStream body = response.headers().firstValue("Content-Encoding").orElse("").equalsIgnoreCase("gzip")
            ? new GZIPInputStream(response.body()) : response.body()) {
            if (onAuction != null) {
                JsonReader reader = new JsonReader(new InputStreamReader(body, StandardCharsets.UTF_8));
                JsonObject json = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if ("auctions".equals(name)) {
                        if (!json.has("success") || !json.get("success").getAsBoolean())
                            throw new IllegalStateException("Hypixel returned success=false");
                        if (!json.has("lastUpdated")) throw new IllegalStateException("Hypixel omitted lastUpdated before auctions");
                        long update = json.get("lastUpdated").getAsLong();
                        JsonArray auctions = new JsonArray();
                        reader.beginArray();
                        while (reader.hasNext()) {
                            JsonObject raw = JsonParser.parseReader(reader).getAsJsonObject();
                            auctions.add(raw);
                            onAuction.accept(update, raw);
                        }
                        reader.endArray();
                        json.add(name, auctions);
                    } else {
                        json.add(name, JsonParser.parseReader(reader));
                    }
                }
                reader.endObject();
                return new Page(json, response.headers().firstValue("Last-Modified").orElse(null),
                    response.headers().firstValue("Date").orElse(null), System.currentTimeMillis());
            }
            byte[] bytes = body.readNBytes(8_000_001);
            if (bytes.length > 8_000_000) throw new IllegalStateException("Hypixel page " + page + " exceeds 8 MB");
            return new Page(JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject(),
                response.headers().firstValue("Last-Modified").orElse(null), response.headers().firstValue("Date").orElse(null),
                System.currentTimeMillis());
        }
    }

    private void addPage(JsonObject page, Map<String, Auction> now, Map<String, String> newItemBytes) {
        for (var element : page.getAsJsonArray("auctions")) {
            JsonObject raw = element.getAsJsonObject();
            if (!raw.has("bin") || !raw.get("bin").getAsBoolean()) continue;
            if (raw.has("uuid") && now.containsKey(raw.get("uuid").getAsString())) continue;
            try {
                Auction auction = decode(raw);
                if (auction != null) {
                    now.put(auction.uuid, auction);
                    if (!previous.containsKey(auction.uuid))
                        newItemBytes.put(auction.uuid, raw.get("item_bytes").getAsString());
                }
            } catch (Exception e) {
                // A malformed item must not prevent all other auctions from being checked.
                LocalAhFlipper.LOG.debug("Skipped malformed auction {}", raw.has("uuid") ? raw.get("uuid") : "unknown", e);
            }
        }
    }

    private static Auction decode(JsonObject raw) throws Exception {
        long cost = raw.get("starting_bid").getAsLong();
        if (cost <= 0) return null;
        ItemIdentity item = identityForItem(raw.get("item_bytes").getAsString(), raw.get("tier").getAsString());
        if (item == null) return null;
        return new Auction(raw.get("uuid").getAsString(), raw.get("auctioneer").getAsString(),
            raw.get("item_name").getAsString(), item.key(), item.id(), item.recombobulated(),
            raw.get("start").getAsLong(), cost);
    }

    private static ItemIdentity identityForItem(String itemBytes, String tier) throws Exception {
        byte[] bytes = Base64.getDecoder().decode(itemBytes);
        if (bytes.length > 1_000_000) return null;
        CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.create(4_000_000));
        if (!(root.get("i") instanceof ListTag items) || items.isEmpty() || !(items.get(0) instanceof CompoundTag stack))
            return null;
        if (!(stack.get("tag") instanceof CompoundTag tag) || !(tag.get("ExtraAttributes") instanceof CompoundTag extra))
            return null;
        String id = extra.getStringOr("id", "");
        if (id.isBlank()) return null;
        if (tier == null) {
            if (!(stack.get("components") instanceof CompoundTag components)) return null;
            String style = components.getStringOr("minecraft:tooltip_style", "");
            if (!style.startsWith("hypixel_skyblock:")) return null;
            tier = style.substring("hypixel_skyblock:".length()).toUpperCase(java.util.Locale.ROOT);
        }
        StringBuilder key = new StringBuilder(id).append('|').append(tier).append('|').append(stack.getIntOr("Count", 1));
        if ("PET".equals(id)) {
            String petInfo = extra.getStringOr("petInfo", "");
            if (petInfo.isBlank()) return null;
            JsonObject pet = JsonParser.parseString(petInfo).getAsJsonObject();
            key.append("|pet=").append(pet.get("type").getAsString());
            key.append("|petTier=").append(pet.get("tier").getAsString());
            if (pet.has("heldItem")) key.append("|held=").append(pet.get("heldItem").getAsString());
            if (pet.has("skin")) key.append("|skin=").append(pet.get("skin").getAsString());
            if (pet.has("exp")) {
                double exp = pet.get("exp").getAsDouble();
                key.append("|expBand=").append(exp < 1_000_000 ? 0 : exp < 10_000_000 ? 1 : exp < 25_000_000 ? 2 : exp < 100_000_000 ? 3 : 4);
            }
        }
        TreeMap<String, String> modifiers = new TreeMap<>();
        for (String name : extra.keySet()) {
            if (IGNORE_NBT.contains(name)) continue;
            Tag value = extra.get(name);
            if (value != null) modifiers.put(name, canonical(value));
        }
        if (extra.get("enchantments") instanceof CompoundTag enchants) {
            for (String name : enchants.keySet())
                modifiers.put("enchant:" + name, String.valueOf(enchants.getIntOr(name, 0)));
        }
        for (var entry : modifiers.entrySet())
            key.append('|').append(entry.getKey()).append('=').append(entry.getValue());
        return new ItemIdentity(key.toString(), id, extra.getIntOr("rarity_upgrades", 0) > 0);
    }

    private static String canonical(Tag tag) {
        if (tag instanceof CompoundTag compound) {
            TreeMap<String, String> values = new TreeMap<>();
            for (String key : compound.keySet()) values.put(key, canonical(compound.get(key)));
            return values.toString();
        }
        if (tag instanceof ListTag list) {
            List<String> values = new ArrayList<>(list.size());
            for (Tag member : list) values.add(canonical(member));
            values.sort(String::compareTo);
            return values.toString();
        }
        return tag == null ? "" : tag.toString();
    }

    private void emitIfVolume(Auction auction, String itemBytes, Flip flip) {
        int minimum = settings.get().minDailySales;
        if (minimum <= 0) {
            onFlip.accept(new Flip(flip.uuid, flip.name, flip.cost, flip.target, flip.profit,
                flip.comparables, flip.soldReferences, "off"));
            return;
        }
        if (itemBytes == null) {
            emitWithLocalVolume(auction, flip, minimum);
            return;
        }
        coflVolume.check(auction.key, itemBytes, estimate -> {
            if (estimate != null) {
                if (estimate.volume() >= minimum) {
                    long target = estimate.exactPriceKey() && estimate.median() > 0
                        ? Math.min(flip.target, estimate.median()) : flip.target;
                    long profit = (long) (target * 0.95) - flip.cost;
                    if (matchesProfitRule(profit, flip.cost, settings.get()))
                        onFlip.accept(new Flip(flip.uuid, flip.name, flip.cost, target, profit,
                            flip.comparables, flip.soldReferences,
                            String.format(java.util.Locale.US, "Cofl %.1f/day", estimate.volume())));
                }
            } else {
                emitWithLocalVolume(auction, flip, minimum);
            }
        });
    }

    private void emitWithLocalVolume(Auction auction, Flip flip, int minimum) {
        double local = sales.salesPerDay(auction.key);
        if (Double.isFinite(local) && local < minimum) return;
        String info = Double.isFinite(local) ? String.format(java.util.Locale.US, "local %.0f/day", local) : "unverified";
        onFlip.accept(new Flip(flip.uuid, flip.name, flip.cost, flip.target, flip.profit,
            flip.comparables, flip.soldReferences, info));
    }

    private static Flip evaluate(Auction candidate, List<Auction> group, LocalAhFlipper.Config config,
                                 SaleHistory sales, ItemCategories categories) {
        // The saved Cofl blacklist is one AND group: recombobulated AND accessory.
        if (config.blockRecombobulatedAccessories && candidate.recombobulated && categories.isAccessory(candidate.itemId)) return null;
        if ((config.maxCost > 0 && candidate.cost > config.maxCost) || group == null || group.size() < 3) return null;
        List<Auction> comparables = group.stream()
            .filter(a -> !a.uuid.equals(candidate.uuid) && !a.seller.equals(candidate.seller))
            .filter(a -> a.start <= candidate.start - 120_000)
            .sorted(Comparator.comparingLong(Auction::cost))
            .toList();
        if (comparables.size() < 3) return null;
        Set<String> sellers = new HashSet<>();
        for (int i = 0; i < Math.min(comparables.size(), 5); i++) sellers.add(comparables.get(i).seller);
        if (sellers.size() < 3) return null;
        long lowest = comparables.getFirst().cost;
        long third = comparables.get(2).cost;
        if (third > lowest * 2.0) return null; // wild spread: likely unreliable or manipulated
        SaleHistory.Estimate sold = sales.estimate(candidate.key);
        long target = sold == null ? lowest : Math.min(lowest, sold.price());
        long profit = (long) (target * 0.95) - candidate.cost; // conservative listing, selling and tax allowance
        if (!matchesProfitRule(profit, candidate.cost, config)) return null;
        return new Flip(candidate.uuid, candidate.name, candidate.cost, target, profit,
            comparables.size(), sold == null ? 0 : sold.references(), "pending");
    }

    private static boolean matchesProfitRule(long profit, long cost, LocalAhFlipper.Config config) {
        for (LocalAhFlipper.Config.ProfitRule rule : config.profitRules) {
            if (profit >= rule.minProfit && profit * 100.0 >= cost * rule.minProfitPercent) return true;
        }
        return false;
    }
}
