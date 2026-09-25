package local.ahflipper;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A rolling, local cache of actual BIN sales from Hypixel's public ended-auctions feed. */
final class SaleHistory {
    record Estimate(long price, int references) {}
    private record Sale(long price, long timestamp, String seller) {}
    private static final long RETENTION_MS = 7L * 24 * 60 * 60 * 1_000;
    private static final long MINUTE_MS = 60_000;
    private static final int MINUTES_PER_DAY = 1_440;
    private final Path directory = FabricLoader.getInstance().getConfigDir().resolve("local-ah-flipper-sales");
    private final Path coverageFile = directory.resolve("covered-minutes.txt");
    private final Map<String, List<Sale>> byKey = new HashMap<>();
    private final Set<String> seenIds = new HashSet<>();
    private final Set<Long> coveredMinutes = new HashSet<>();
    private long latestCoveredMinute;
    private long coverageCacheMinute = -1;
    private int coverageCachePercent;

    synchronized int count() {
        return seenIds.size();
    }

    synchronized int volumeCoveragePercent() {
        if (latestCoveredMinute == 0 || latestCoveredMinute < System.currentTimeMillis() / MINUTE_MS - 3)
            return 0;
        if (coverageCacheMinute == latestCoveredMinute) return coverageCachePercent;
        int covered = 0;
        for (long minute = latestCoveredMinute - MINUTES_PER_DAY + 1; minute <= latestCoveredMinute; minute++)
            if (coveredMinutes.contains(minute)) covered++;
        coverageCacheMinute = latestCoveredMinute;
        coverageCachePercent = covered * 100 / MINUTES_PER_DAY;
        return coverageCachePercent;
    }

    synchronized boolean volumeReady() {
        return volumeCoveragePercent() >= 98;
    }

    synchronized double salesPerDay(String key) {
        if (!volumeReady()) return Double.NaN;
        List<Sale> sales = byKey.get(key);
        if (sales == null) return 0;
        long start = (latestCoveredMinute - MINUTES_PER_DAY + 1) * MINUTE_MS;
        long end = (latestCoveredMinute + 1) * MINUTE_MS;
        long count = sales.stream().filter(s -> s.timestamp >= start && s.timestamp < end).count();
        return count;
    }

    synchronized void markCovered(long lastUpdated) {
        // The ended-auction response contains approximately the previous minute of sales.
        long minute = (lastUpdated - 30_000) / MINUTE_MS;
        if (!coveredMinutes.add(minute)) return;
        latestCoveredMinute = Math.max(latestCoveredMinute, minute);
        try {
            Files.createDirectories(directory);
            Files.writeString(coverageFile, minute + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LocalAhFlipper.LOG.warn("Could not persist sold-auction coverage", e);
        }
    }

    void load() {
        try {
            Files.createDirectories(directory);
            if (Files.exists(coverageFile)) {
                try (BufferedReader reader = Files.newBufferedReader(coverageFile, StandardCharsets.UTF_8)) {
                    String line;
                    long oldest = System.currentTimeMillis() / MINUTE_MS - 7 * MINUTES_PER_DAY;
                    while ((line = reader.readLine()) != null) {
                        try {
                            long minute = Long.parseLong(line);
                            if (minute >= oldest) {
                                coveredMinutes.add(minute);
                                latestCoveredMinute = Math.max(latestCoveredMinute, minute);
                            }
                        } catch (NumberFormatException ignored) { }
                    }
                }
                Files.writeString(coverageFile, coveredMinutes.stream().sorted()
                    .map(String::valueOf).collect(java.util.stream.Collectors.joining("\n", "", "\n")),
                    StandardCharsets.UTF_8);
            }
            LocalDate oldestDate = LocalDate.now(ZoneOffset.UTC).minusDays(7);
            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "sales-*.jsonl")) {
                for (Path file : files) {
                    String name = file.getFileName().toString();
                    LocalDate date;
                    try { date = LocalDate.parse(name.substring(6, 16)); }
                    catch (Exception ignored) { continue; }
                    if (date.isBefore(oldestDate)) {
                        Files.deleteIfExists(file);
                        continue;
                    }
                    try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            try {
                                JsonObject sale = JsonParser.parseString(line).getAsJsonObject();
                                addMemory(sale.get("id").getAsString(), sale.get("key").getAsString(),
                                    sale.get("price").getAsLong(), sale.get("time").getAsLong(), sale.get("seller").getAsString());
                            } catch (Exception ignored) {
                                // Ignore a truncated final line after an interrupted write.
                            }
                        }
                    }
                }
            }
            LocalAhFlipper.LOG.info("Loaded {} local sold BIN references", count());
        } catch (IOException e) {
            LocalAhFlipper.LOG.warn("Could not load local sold auction cache", e);
        }
    }

    synchronized boolean add(String id, String key, long price, long timestamp, String seller) {
        if (!addMemory(id, key, price, timestamp, seller)) return false;
        JsonObject line = new JsonObject();
        line.addProperty("id", id);
        line.addProperty("key", key);
        line.addProperty("price", price);
        line.addProperty("time", timestamp);
        line.addProperty("seller", seller);
        try {
            Files.createDirectories(directory);
            String date = LocalDate.ofInstant(Instant.ofEpochMilli(timestamp), ZoneOffset.UTC).toString();
            Files.writeString(directory.resolve("sales-" + date + ".jsonl"), line + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LocalAhFlipper.LOG.warn("Could not persist sold auction reference", e);
        }
        return true;
    }

    private synchronized boolean addMemory(String id, String key, long price, long timestamp, String seller) {
        if (id.isBlank() || key.isBlank() || price <= 0 || timestamp < System.currentTimeMillis() - RETENTION_MS ||
            !seenIds.add(id)) return false;
        List<Sale> sales = byKey.computeIfAbsent(key, ignored -> new ArrayList<>());
        sales.add(new Sale(price, timestamp, seller));
        if (sales.size() > 100) {
            sales.sort(Comparator.comparingLong(Sale::timestamp));
            sales.subList(0, sales.size() - 100).clear();
        }
        return true;
    }

    synchronized Estimate estimate(String key) {
        List<Sale> existing = byKey.get(key);
        if (existing == null || existing.size() < 5) return null;
        long cutoff = System.currentTimeMillis() - RETENTION_MS;
        List<Sale> recent = existing.stream().filter(s -> s.timestamp >= cutoff)
            .sorted(Comparator.comparingLong(Sale::timestamp).reversed()).limit(20).toList();
        if (recent.size() < 5 || recent.stream().map(Sale::seller).distinct().limit(3).count() < 3) return null;
        List<Long> prices = recent.stream().map(Sale::price).sorted().toList();
        long conservativePrice = prices.get((prices.size() - 1) * 2 / 5);
        return new Estimate(conservativePrice, recent.size());
    }
}
