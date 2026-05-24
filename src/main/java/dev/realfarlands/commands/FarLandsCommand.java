package dev.realfarlands.commands;

import dev.realfarlands.RealFarLands;
import dev.realfarlands.config.FarLandsConfig;
import dev.realfarlands.generator.ChunkCache;
import dev.realfarlands.generator.FarLandsGenerator;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles all /farlands sub-commands:
 *   /farlands reload  – reloads config (admin)
 *   /farlands info    – displays current config summary
 *   /farlands tp      – teleports to the Far Lands border
 *   /farlands debug   – toggles debug particles (admin)
 */
public final class FarLandsCommand implements CommandExecutor, TabCompleter {

    private final RealFarLands plugin;

    public FarLandsCommand(RealFarLands plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {

        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        return switch (args[0].toLowerCase()) {
            case "reload" -> handleReload(sender);
            case "info"   -> handleInfo(sender);
            case "tp"     -> handleTp(sender);
            case "debug"  -> handleDebug(sender);
            default       -> { sendHelp(sender, label); yield true; }
        };
    }

    // ─── /farlands reload ─────────────────────────────────────────────────────

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("farlands.admin")) {
            sender.sendMessage(Component.text("You don't have permission to do that.", NamedTextColor.RED));
            return true;
        }
        plugin.reloadPluginConfig();
        sender.sendMessage(Component.text()
            .append(Component.text("[RealFarLands] ", NamedTextColor.GOLD, TextDecoration.BOLD))
            .append(Component.text("Configuration reloaded successfully.", NamedTextColor.GREEN))
            .build());
        return true;
    }

    // ─── /farlands info ───────────────────────────────────────────────────────

    private boolean handleInfo(CommandSender sender) {
        FarLandsConfig cfg = plugin.getFarLandsConfig();

        sender.sendMessage(Component.text("─── RealFarLands Info ───", NamedTextColor.GOLD, TextDecoration.BOLD));
        info(sender, "Enabled",       String.valueOf(cfg.enabled));
        info(sender, "World",         cfg.world);
        info(sender, "Side",          cfg.side.name());
        info(sender, "Start coord",   String.format("%,d", cfg.startCoordinate));
        info(sender, "Preset",        cfg.preset.name());
        info(sender, "Height mult",   String.format("%.2f", cfg.effectiveHeightMultiplier()));
        info(sender, "Corruption",    String.format("%.2f", cfg.effectiveCorruptionStrength()));
        info(sender, "Blend dist",    String.format("%,d blocks", cfg.effectiveBlendingDistance()));
        info(sender, "Void tearing",  String.valueOf(cfg.effectiveVoidTearing()));
        info(sender, "Folding",       String.valueOf(cfg.effectiveTerrainFolding()));
        info(sender, "Float terrain", String.valueOf(cfg.effectiveFloatingTerrain()));
        info(sender, "Extreme caves", String.valueOf(cfg.effectiveExtremeCaves()));

        if (cfg.chunkCache) {
            int size = plugin.getCacheSize();
            info(sender, "Cache entries", size + " / " + cfg.cacheSize);
        }
        return true;
    }

    private void info(CommandSender s, String key, String val) {
        s.sendMessage(Component.text()
            .append(Component.text("  " + key + ": ", NamedTextColor.YELLOW))
            .append(Component.text(val, NamedTextColor.WHITE))
            .build());
    }

    // ─── /farlands tp ─────────────────────────────────────────────────────────

    private boolean handleTp(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
            return true;
        }
        if (!player.hasPermission("farlands.tp")) {
            player.sendMessage(Component.text("You don't have permission to do that.", NamedTextColor.RED));
            return true;
        }

        FarLandsConfig cfg = plugin.getFarLandsConfig();
        World world = Bukkit.getWorld(cfg.world);
        if (world == null) {
            player.sendMessage(Component.text(
                "World '" + cfg.world + "' is not loaded or does not use the RealFarLands generator.",
                NamedTextColor.RED));
            return true;
        }

        // Compute teleport coordinates: at start-coordinate + 200 blocks on the
        // far axis, centred on the perpendicular axis.
        long dest = cfg.startCoordinate + 200;
        double tpX, tpZ;
        switch (cfg.side) {
            case POSITIVE_X -> { tpX =  dest; tpZ = 0.5; }
            case NEGATIVE_X -> { tpX = -dest; tpZ = 0.5; }
            case POSITIVE_Z -> { tpX = 0.5; tpZ =  dest; }
            case NEGATIVE_Z -> { tpX = 0.5; tpZ = -dest; }
            default         -> { tpX = dest; tpZ = 0.5;   }
        }

        double tpY = world.getMaxHeight() - 10;
        Location loc = new Location(world, tpX, tpY, tpZ, 0f, 0f);

        // Teleport async-safe via Paper's teleport API
        player.teleportAsync(loc).thenAccept(success -> {
            if (success) {
                player.sendMessage(Component.text()
                    .append(Component.text("[RealFarLands] ", NamedTextColor.GOLD, TextDecoration.BOLD))
                    .append(Component.text("Teleported to the Far Lands border! (",
                        NamedTextColor.GREEN))
                    .append(Component.text(
                        String.format("%.0f, %.0f, %.0f", tpX, tpY, tpZ),
                        NamedTextColor.YELLOW))
                    .append(Component.text(")", NamedTextColor.GREEN))
                    .build());
            } else {
                player.sendMessage(Component.text("Teleport failed.", NamedTextColor.RED));
            }
        });

        return true;
    }

    // ─── /farlands debug ──────────────────────────────────────────────────────

    private boolean handleDebug(CommandSender sender) {
        if (!sender.hasPermission("farlands.admin")) {
            sender.sendMessage(Component.text("You don't have permission to do that.", NamedTextColor.RED));
            return true;
        }
        boolean newState = plugin.toggleDebugParticles();
        sender.sendMessage(Component.text()
            .append(Component.text("[RealFarLands] ", NamedTextColor.GOLD, TextDecoration.BOLD))
            .append(Component.text("Debug particles: ", NamedTextColor.YELLOW))
            .append(Component.text(newState ? "ON" : "OFF",
                newState ? NamedTextColor.GREEN : NamedTextColor.RED, TextDecoration.BOLD))
            .build());
        return true;
    }

    // ─── Help ─────────────────────────────────────────────────────────────────

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage(Component.text("─── RealFarLands Commands ───", NamedTextColor.GOLD, TextDecoration.BOLD));
        help(sender, "/" + label + " info",   "Show current configuration");
        help(sender, "/" + label + " tp",     "Teleport to the Far Lands border");
        help(sender, "/" + label + " reload", "Reload config (admin)");
        help(sender, "/" + label + " debug",  "Toggle debug particles (admin)");
    }

    private void help(CommandSender s, String cmd, String desc) {
        s.sendMessage(Component.text()
            .append(Component.text("  " + cmd, NamedTextColor.YELLOW))
            .append(Component.text(" – " + desc, NamedTextColor.GRAY))
            .build());
    }

    // ─── Tab Completion ───────────────────────────────────────────────────────

    @Override
    @Nullable
    public List<String> onTabComplete(@NotNull CommandSender sender,
                                      @NotNull Command command,
                                      @NotNull String alias,
                                      @NotNull String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            for (String sub : List.of("reload", "info", "tp", "debug")) {
                if (sub.startsWith(args[0].toLowerCase())) {
                    completions.add(sub);
                }
            }
            return completions;
        }
        return List.of();
    }
}
