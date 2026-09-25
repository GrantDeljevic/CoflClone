package local.ahflipper;

import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/** SkyBlock item category, distinct from the broad auction-house category. */
final class ItemCategories {
    private static final String URL = "https://api.hypixel.net/v2/resources/skyblock/items";
    private final Path cache = FabricLoader.getInstance().getConfigDir().resolve("local-ah-accessory-ids.txt");
    private volatile Set<String> accessoryIds;

    ItemCategories() {
        try (InputStream bundled = ItemCategories.class.getResourceAsStream("/local-ah-accessory-ids.txt")) {
            if (bundled == null) throw new IllegalStateException("Bundled accessory categories missing");
            accessoryIds = parseIds(new String(bundled.readAllBytes(), StandardCharsets.UTF_8));
            if (Files.exists(cache)) {
                try { accessoryIds = parseIds(Files.readString(cache)); }
                catch (Exception e) { LocalAhFlipper.LOG.warn("Ignoring invalid cached accessory categories", e); }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not load SkyBlock item categories", e);
        }
    }

    boolean isAccessory(String itemId) {
        return accessoryIds.contains(itemId);
    }

    void refresh() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(URL)).timeout(Duration.ofSeconds(30))
                .header("User-Agent", "LocalAhFlipper/0.1 personal").GET().build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());
            Set<String> current = new HashSet<>();
            var items = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("items");
            for (var element : items) {
                var item = element.getAsJsonObject();
                if (item.has("category") && "ACCESSORY".equals(item.get("category").getAsString()))
                    current.add(item.get("id").getAsString());
            }
            if (current.size() < 100) throw new IllegalStateException("Unexpected accessory count " + current.size());
            Files.writeString(cache, String.join("\n", current) + "\n", StandardCharsets.UTF_8);
            accessoryIds = Set.copyOf(current);
            LocalAhFlipper.LOG.info("Loaded {} accessory item IDs from Hypixel", current.size());
        } catch (Exception e) {
            LocalAhFlipper.LOG.warn("Could not refresh SkyBlock categories; using bundled/cached IDs", e);
        }
    }

    private static Set<String> parseIds(String lines) {
        Set<String> ids = new HashSet<>();
        for (String line : lines.split("\\R")) if (!line.isBlank()) ids.add(line.strip());
        if (ids.size() < 100) throw new IllegalStateException("Accessory category file is incomplete");
        return Set.copyOf(ids);
    }
}
