package dev.realfarlands.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Immutable snapshot of the plugin configuration.
 * Loaded from config.yml on startup and /farlands reload.
 */
public final class FarLandsConfig {

    // -------------------------------------------------------
    // Enumerations
    // -------------------------------------------------------

    /**
     * The axis-aligned side of the world where Far Lands terrain spawns.
     * The effect is one-directional: only one side is corrupted.
     */
    public enum FarLandsSide {
        POSITIVE_X, NEGATIVE_X, POSITIVE_Z, NEGATIVE_Z
    }

    /**
     * Named configuration presets that replicate famous Far Lands variants.
     */
    public enum Preset {
        NONE,
        /** Closely matches Minecraft Beta 1.7.3 Far Lands behavior. */
        CLASSIC_BETA,
        /** Doubled corruption strength and height. */
        EXTREME,
        /** Maximum chaos: void tears, terrain folding, extreme caves. */
        CURSED,
        /** Very gentle corruption that blends over a long distance. */
        SMOOTH
    }

    // -------------------------------------------------------
    // Fields
    // -------------------------------------------------------

    public final boolean enabled;
    public final String world;

    // Far Lands placement
    public final FarLandsSide side;
    public final long startCoordinate;
    public final long endCoordinate;
    public final int blendingDistance;

    // Terrain shaping
    public final double heightMultiplier;
    public final double noiseCorruptionStrength;
    public final boolean preserveBiomes;
    public final boolean preserveStructures;

    // Optional terrain features
    public final boolean floatingTerrain;
    public final boolean voidTearing;
    public final boolean terrainFolding;
    public final boolean extremeCaves;

    // Preset (may override above values when not NONE)
    public final Preset preset;

    // Performance
    public final boolean asyncPreGeneration;
    public final boolean chunkCache;
    public final int cacheSize;

    // Debug
    public final boolean showParticles;
    public final boolean verboseLogging;

    // -------------------------------------------------------
    // Constructor – load from Bukkit FileConfiguration
    // -------------------------------------------------------

    public FarLandsConfig(FileConfiguration cfg) {
        enabled = cfg.getBoolean("enabled", true);
        world   = cfg.getString("world", "world");

        // Parse side enum safely
        String sideStr = cfg.getString("farlands.side", "POSITIVE_X").toUpperCase();
        FarLandsSide parsedSide;
        try {
            parsedSide = FarLandsSide.valueOf(sideStr);
        } catch (IllegalArgumentException e) {
            parsedSide = FarLandsSide.POSITIVE_X;
        }
        side = parsedSide;

        startCoordinate      = cfg.getLong("farlands.start-coordinate", 12_500_000L);
        endCoordinate        = cfg.getLong("farlands.end-coordinate",   30_000_000L);
        blendingDistance     = cfg.getInt ("farlands.blending-distance", 5000);

        heightMultiplier         = cfg.getDouble("farlands.height-multiplier",         2.5);
        noiseCorruptionStrength  = cfg.getDouble("farlands.noise-corruption-strength", 1.0);
        preserveBiomes           = cfg.getBoolean("farlands.preserve-biomes",     true);
        preserveStructures       = cfg.getBoolean("farlands.preserve-structures", false);

        floatingTerrain = cfg.getBoolean("farlands.floating-terrain", true);
        voidTearing     = cfg.getBoolean("farlands.void-tearing",     false);
        terrainFolding  = cfg.getBoolean("farlands.terrain-folding",  true);
        extremeCaves    = cfg.getBoolean("farlands.extreme-caves",    false);

        // Parse preset enum
        String presetStr = cfg.getString("farlands.preset", "NONE").toUpperCase();
        Preset parsedPreset;
        try {
            parsedPreset = Preset.valueOf(presetStr);
        } catch (IllegalArgumentException e) {
            parsedPreset = Preset.NONE;
        }
        preset = parsedPreset;

        asyncPreGeneration = cfg.getBoolean("performance.async-pre-generation", true);
        chunkCache         = cfg.getBoolean("performance.chunk-cache", true);
        cacheSize          = cfg.getInt("performance.cache-size", 4096);

        showParticles   = cfg.getBoolean("debug.show-particles",   false);
        verboseLogging  = cfg.getBoolean("debug.verbose-logging",  false);
    }

    // -------------------------------------------------------
    // Effective values (preset may override base config)
    // -------------------------------------------------------

    public double effectiveHeightMultiplier() {
        return switch (preset) {
            case CLASSIC_BETA -> 2.5;
            case EXTREME      -> 5.0;
            case CURSED       -> 8.0;
            case SMOOTH       -> 1.5;
            case NONE         -> heightMultiplier;
        };
    }

    public double effectiveCorruptionStrength() {
        return switch (preset) {
            case CLASSIC_BETA -> 1.0;
            case EXTREME      -> 2.0;
            case CURSED       -> 4.0;
            case SMOOTH       -> 0.4;
            case NONE         -> noiseCorruptionStrength;
        };
    }

    public int effectiveBlendingDistance() {
        return switch (preset) {
            case CLASSIC_BETA -> 5000;
            case EXTREME      -> 2000;
            case CURSED       -> 500;
            case SMOOTH       -> 20000;
            case NONE         -> blendingDistance;
        };
    }

    public boolean effectiveVoidTearing() {
        return preset == Preset.CURSED || voidTearing;
    }

    public boolean effectiveTerrainFolding() {
        return preset == Preset.CURSED || preset == Preset.CLASSIC_BETA || terrainFolding;
    }

    public boolean effectiveExtremeCaves() {
        return preset == Preset.CURSED || preset == Preset.EXTREME || extremeCaves;
    }

    public boolean effectiveFloatingTerrain() {
        return preset == Preset.CLASSIC_BETA || preset == Preset.EXTREME
                || preset == Preset.CURSED || floatingTerrain;
    }
}
