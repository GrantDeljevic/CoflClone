package local.ahflipper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class LocalAhFlipper implements ClientModInitializer {
    static final Logger LOG = LoggerFactory.getLogger("localahflipper");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("local-ah-flipper.json");
    private volatile Config config = new Config();
    private AuctionScanner scanner;
    private boolean lastActive;

    @Override
    public void onInitializeClient() {
        loadConfig();
        scanner = new AuctionScanner(() -> config, this::announce);
        scanner.start();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean active = config.enabled && onHypixel(client);
            if (active != lastActive) {
                scanner.setActive(active);
                lastActive = active;
            }
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommands.literal("localflip")
                .executes(context -> { say(scanner.status()); return 1; })
                .then(ClientCommands.literal("status").executes(context -> { say(scanner.status()); return 1; }))
                .then(ClientCommands.literal("on").executes(context -> {
                    config.enabled = true;
                    saveConfig();
                    say("Enabled. Scanning starts while connected to Hypixel.");
                    return 1;
                }))
                .then(ClientCommands.literal("off").executes(context -> {
                    config.enabled = false;
                    saveConfig();
                    say("Disabled.");
                    return 1;
                }))
                .then(ClientCommands.literal("reload").executes(context -> {
                    loadConfig();
                    say("Settings reloaded: " + config.profitRulesDescription() + ", " +
                        (config.maxCost == 0 ? "no max cost" : config.maxCost + " max cost") + ", " + config.pollSeconds + "s polling.");
                    return 1;
                }))
                .then(ClientCommands.literal("scan").executes(context -> {
                    scanner.scanSoon();
                    say("Requested a scan. Hypixel's auction feed may still be cached for about a minute.");
                    return 1;
                }))
            )
        );
        LOG.info("Local AH Flipper initialized");
    }

    private static boolean onHypixel(Minecraft client) {
        return client.player != null && client.getCurrentServer() != null &&
            client.getCurrentServer().ip != null &&
            client.getCurrentServer().ip.toLowerCase(Locale.ROOT).contains("hypixel.net");
    }

    private void announce(AuctionScanner.Flip flip) {
        Minecraft.getInstance().execute(() -> {
            var player = Minecraft.getInstance().player;
            if (player == null || !onHypixel(Minecraft.getInstance()) || !config.enabled) return;
            String line = String.format(Locale.US,
                "§d§l[LocalFlip]§r §d%s §7BIN §e%,d§7 | resale estimate §e%,d§7 | profit §d§l%,d§r§7 | %d asks, %d sales | vol %s",
                flip.name(), flip.cost(), flip.target(), flip.profit(), flip.comparables(), flip.soldReferences(), flip.volumeInfo());
            Minecraft.getInstance().gui.getChat().addServerSystemMessage(Component.literal(line).withStyle(style ->
                style.withClickEvent(new ClickEvent.RunCommand("/viewauction " + flip.uuid()))
                    .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(Component.literal("Open auction in Hypixel")))));
        });
    }

    private static void say(String message) {
        Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().player != null)
                Minecraft.getInstance().gui.getChat().addServerSystemMessage(Component.literal("§d§l[LocalFlip]§r §f" + message));
        });
    }

    private void loadConfig() {
        try {
            if (Files.exists(CONFIG_PATH)) {
                Config loaded = GSON.fromJson(Files.readString(CONFIG_PATH), Config.class);
                if (loaded != null) config = loaded.validated();
            } else {
                saveConfig();
            }
        } catch (Exception e) {
            LOG.warn("Could not read local flip config; using defaults", e);
            config = new Config();
        }
    }

    private void saveConfig() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(config), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("Could not save local flip config", e);
        }
    }

    static final class Config {
        boolean enabled = true;
        ProfitRule[] profitRules = {
            new ProfitRule(500_000, 25),
            new ProfitRule(1_500_000, 10)
        };
        int pollSeconds = 60;
        long maxCost = 0;
        int minDailySales = 3;
        boolean blockRecombobulatedAccessories = true;

        Config validated() {
            if (profitRules == null || profitRules.length == 0) profitRules = new Config().profitRules;
            for (int i = 0; i < profitRules.length; i++) {
                if (profitRules[i] == null) profitRules[i] = new ProfitRule(500_000, 25);
                profitRules[i].minProfit = Math.max(0, profitRules[i].minProfit);
                profitRules[i].minProfitPercent = Math.max(0, Math.min(90, profitRules[i].minProfitPercent));
            }
            pollSeconds = Math.max(60, pollSeconds);
            maxCost = Math.max(0, maxCost);
            minDailySales = Math.max(0, minDailySales);
            return this;
        }

        String profitRulesDescription() {
            StringBuilder result = new StringBuilder();
            for (ProfitRule rule : profitRules) {
                if (!result.isEmpty()) result.append(" OR ");
                result.append(rule.minProfitPercent).append("% and ").append(rule.minProfit).append(" profit");
            }
            return result.toString();
        }

        static final class ProfitRule {
            long minProfit;
            int minProfitPercent;

            ProfitRule(long minProfit, int minProfitPercent) {
                this.minProfit = minProfit;
                this.minProfitPercent = minProfitPercent;
            }
        }
    }
}
