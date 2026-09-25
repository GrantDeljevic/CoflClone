package local.ahflipper;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Historical modifier-aware volume from Cofl, without running its client mod. */
final class CoflVolume {
    record Estimate(double volume, long median, boolean exactPriceKey) {}
    private record Cached(Estimate value, long expiresAt) {}
    private static final URI URL = URI.create("https://sky.coflnet.com/api/price/nbt");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Estimate>> pending = new ConcurrentHashMap<>();
    private final Semaphore running = new Semaphore(4);
    private final ArrayDeque<Long> minuteRequests = new ArrayDeque<>();
    private final ArrayDeque<Long> tenSecondRequests = new ArrayDeque<>();
    private final AtomicInteger successful = new AtomicInteger();
    private final AtomicInteger unavailable = new AtomicInteger();

    String status() {
        return "Cofl volume " + successful.get() + " fetched, " + unavailable.get() + " unavailable";
    }

    void check(String exactKey, String itemBytes, Consumer<Estimate> onResult) {
        Cached existing = cache.get(exactKey);
        if (existing != null && existing.expiresAt > System.currentTimeMillis()) {
            onResult.accept(existing.value);
            return;
        }
        CompletableFuture<Estimate> future = new CompletableFuture<>();
        CompletableFuture<Estimate> first = pending.putIfAbsent(exactKey, future);
        if (first != null) {
            first.thenAccept(onResult::accept);
            return;
        }
        future.thenAccept(onResult::accept);
        boolean acquired = running.tryAcquire();
        if (!acquired || !permit()) {
            if (acquired) running.release();
            unavailable.incrementAndGet();
            cache.put(exactKey, new Cached(null, System.currentTimeMillis() + 30_000L));
            pending.remove(exactKey, future);
            future.complete(null);
            return;
        }
        Thread.ofVirtual().name("local-ah-cofl-volume").start(() -> {
            Estimate estimate = null;
            try {
                estimate = fetch(itemBytes);
                successful.incrementAndGet();
            } catch (Exception e) {
                unavailable.incrementAndGet();
                LocalAhFlipper.LOG.debug("Cofl historical volume unavailable", e);
            } finally {
                running.release();
                cache.put(exactKey, new Cached(estimate, System.currentTimeMillis() +
                    (estimate != null ? 10 * 60_000L : 30_000L)));
                if (cache.size() > 5_000) cache.entrySet().removeIf(entry -> entry.getValue().expiresAt < System.currentTimeMillis());
                pending.remove(exactKey, future);
                future.complete(estimate);
            }
        });
    }

    private synchronized boolean permit() {
        long now = System.currentTimeMillis();
        while (!minuteRequests.isEmpty() && minuteRequests.peekFirst() < now - 60_000) minuteRequests.removeFirst();
        while (!tenSecondRequests.isEmpty() && tenSecondRequests.peekFirst() < now - 10_000) tenSecondRequests.removeFirst();
        if (minuteRequests.size() >= 50 || tenSecondRequests.size() >= 8) return false;
        minuteRequests.addLast(now);
        tenSecondRequests.addLast(now);
        return true;
    }

    private Estimate fetch(String itemBytes) throws Exception {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("chestName", "");
        requestBody.addProperty("fullInventoryNbt", itemBytes);
        JsonObject position = new JsonObject();
        position.addProperty("x", 0);
        position.addProperty("y", 0);
        position.addProperty("z", 0);
        requestBody.add("position", position);
        requestBody.add("jsonNbt", null);
        requestBody.addProperty("senderContactId", "local-ah-flipper");
        requestBody.addProperty("server", "hypixel");
        HttpRequest request = HttpRequest.newBuilder(URL).timeout(Duration.ofSeconds(4))
            .header("Content-Type", "application/json").header("User-Agent", "LocalAhFlipper/0.1 personal")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString())).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("Cofl HTTP " + response.statusCode());
        var estimates = JsonParser.parseString(response.body()).getAsJsonArray();
        if (estimates.isEmpty() || !estimates.get(0).getAsJsonObject().has("volume"))
            throw new IllegalStateException("Cofl did not return volume");
        JsonObject result = estimates.get(0).getAsJsonObject();
        double volume = result.get("volume").getAsDouble();
        if (!Double.isFinite(volume) || volume < 0) throw new IllegalStateException("Invalid Cofl volume");
        long median = result.has("median") ? result.get("median").getAsLong() : 0;
        boolean exactPriceKey = result.has("itemKey") && result.has("medianKey") &&
            !result.get("itemKey").getAsString().isBlank() &&
            result.get("itemKey").getAsString().equals(result.get("medianKey").getAsString());
        return new Estimate(volume, median, exactPriceKey);
    }
}
