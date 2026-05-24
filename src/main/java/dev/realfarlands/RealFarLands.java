package dev.realfarlands;

import dev.realfarlands.commands.FarLandsCommand;
import dev.realfarlands.config.FarLandsConfig;
import dev.realfarlands.generator.ChunkCache;
import dev.realfarlands.generator.FarLandsGenerator;
import dev.realfarlands.listener.DebugParticleListener;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.PluginCommand;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * RealFarLands – Main plugin class
 * =================================
 * Entry point for the RealFarLands Paper plugin.
 *
 * ─── Setup ───────────────────────────────────────────────────────────────────
 * 1. Drop the compiled JAR into your server's plugins/ folder.
 * 2. Add the generator to bukkit.yml for your world:
 *
 *    worlds:
 *      world:           # replace with your world name
 *        generator: RealFarLands
 *
 *    OR create a new world with Multiverse:
 *    /mv create farlands normal -g RealFarLands
 *
 * 3. Set start-coordinate in config.yml to a low value (e.g. 1000) for testing.
 * 4. Use /farlands tp to teleport to the Far Lands border.
 *
 * ─── Architecture ────────────────────────────────────────────────────────────
 *  RealFarLands (this class)
 *    ├── FarLandsConfig         – parsed config.yml snapshot
 *    ├── FarLandsGenerator      – Paper ChunkGenerator implementation
 *    │     ├── FarLandsNoise    – core corruption algorithm
 *    │     └── ChunkCache       – LRU cache for computed noise grids
 *    ├── FarLandsCommand        – /farlands command handler
 *    └── DebugParticleListener  – optional particle visualisation
 */
public final class RealFarLands extends JavaPlugin {

    private FarLandsConfig    farLandsConfig;
    private FarLandsGenerator generator;
    private ChunkCache        chunkCache;
    private boolean           debugParticles;

    // ─── Enable / Disable ────────────────────────────────────────────────────

    @Override
    public void onEnable() {
        // Save default config if not present
        saveDefaultConfig();

        // Load configuration
        loadFarLandsConfig();

        // Register generator (must be done before worlds load; bukkit.yml hooks it)
        // The generator is returned via getDefaultWorldGenerator() below.
        // We pre-create it here so it's ready when the world is initialised.
        recreateGenerator();

        // Register commands
        PluginCommand cmd = getCommand("farlands");
        if (cmd != null) {
            FarLandsCommand handler = new FarLandsCommand(this);
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }

        // Register debug particle listener
        Bukkit.getPluginManager().registerEvents(new DebugParticleListener(this), this);

        // Set initial debug state from config
        debugParticles = farLandsConfig.showParticles;

        getLogger().info("RealFarLands enabled. Far Lands side: "
            + farLandsConfig.side + " @ ±" + farLandsConfig.startCoordinate);
        getLogger().info("Preset: " + farLandsConfig.preset);
        if (farLandsConfig.startCoordinate <= 100_000) {
            getLogger().warning("start-coordinate is set very low ("
                + farLandsConfig.startCoordinate + "). This is fine for testing.");
        }
    }

    @Override
    public void onDisable() {
        if (chunkCache != null) {
            chunkCache.clear();
        }
        getLogger().info("RealFarLands disabled.");
    }

    // ─── Generator hook (called by Bukkit when loading worlds) ───────────────

    /**
     * Bukkit calls this method when loading a world that specifies
     * "generator: RealFarLands" in bukkit.yml or via WorldCreator.
     *
     * @param worldName name of the world being loaded
     * @param id        optional generator ID (ignored)
     * @return our FarLandsGenerator, or null if this world is not configured
     */
    @Override
    @Nullable
    public ChunkGenerator getDefaultWorldGenerator(@NotNull String worldName, @Nullable String id) {
        // Provide the generator for the configured world (or any world if id matches)
        if (worldName.equals(farLandsConfig.world) || "farlands".equalsIgnoreCase(id)) {
            return getOrCreateGenerator();
        }
        // For any world that specifies us as generator but isn't the config world,
        // still provide it – useful for Multiverse multi-world setups.
        return getOrCreateGenerator();
    }

    // ─── Config management ───────────────────────────────────────────────────

    private void loadFarLandsConfig() {
        reloadConfig();
        farLandsConfig = new FarLandsConfig(getConfig());
    }

    /**
     * Called by /farlands reload – reloads config.yml and recreates generator.
     * Note: chunk generation is seeded per-world; already-generated chunks
     * are not retroactively changed.
     */
    public void reloadPluginConfig() {
        loadFarLandsConfig();
        recreateGenerator();
        debugParticles = farLandsConfig.showParticles;
        getLogger().info("Config reloaded. Side=" + farLandsConfig.side
            + " start=" + farLandsConfig.startCoordinate);
    }

    public FarLandsConfig getFarLandsConfig() {
        return farLandsConfig;
    }

    // ─── Generator management ────────────────────────────────────────────────

    private synchronized FarLandsGenerator getOrCreateGenerator() {
        if (generator == null) {
            recreateGenerator();
        }
        return generator;
    }

    private synchronized void recreateGenerator() {
        if (chunkCache != null) chunkCache.clear();
        chunkCache = farLandsConfig.chunkCache ? new ChunkCache(farLandsConfig.cacheSize) : null;
        generator  = new FarLandsGenerator(farLandsConfig, getLogger());
    }

    // ─── Debug particles ─────────────────────────────────────────────────────

    public boolean isDebugParticlesEnabled() {
        return debugParticles;
    }

    /** Toggle debug particles; returns new state. */
    public boolean toggleDebugParticles() {
        debugParticles = !debugParticles;
        return debugParticles;
    }

    // ─── Cache stats (for /farlands info) ────────────────────────────────────

    public int getCacheSize() {
        return chunkCache != null ? chunkCache.size() : 0;
    }
}
