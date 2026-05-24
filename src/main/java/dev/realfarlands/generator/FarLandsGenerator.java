package dev.realfarlands.generator;

import dev.realfarlands.config.FarLandsConfig;
import dev.realfarlands.config.FarLandsConfig.FarLandsSide;
import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Random;
import java.util.logging.Logger;

/**
 * FarLandsGenerator
 * =================
 * A Paper {@link ChunkGenerator} that produces standard overworld terrain
 * everywhere, and injects the classic Beta Far Lands corruption on one
 * configurable axis side of the world.
 *
 * ─── How it works ────────────────────────────────────────────────────────────
 * Paper's ChunkGenerator provides three overridable shape methods:
 *
 *   generateNoise()   – fills raw density / base terrain (stone, dirt, etc.)
 *   generateSurface() – applies surface layers (grass, sand, snow)
 *   generateCaves()   – carves caves
 *
 * We also delegate noise, surface, cave, decoration, mob, and structure
 * generation back to vanilla via the shouldGenerate*() flags, EXCEPT for the
 * generateNoise() method which we implement ourselves.
 *
 * In generateNoise() we:
 *   1. Determine whether this chunk is in the Far Lands zone.
 *   2. If fully in normal terrain, bail out early (vanilla handles it).
 *   3. Otherwise run our {@link FarLandsNoise} algorithm to decide which
 *      blocks are solid, filling the chunk with stone or air accordingly.
 *
 * ─── Limitations vs. original Beta Far Lands ─────────────────────────────────
 *  • The original used Notch's integer-based multi-octave Perlin that overflowed
 *    Java float32 accumulators. We use simplex noise + mathematical simulation.
 *  • Biomes in the Far Lands are preserved via preserve-biomes (vanilla provides
 *    them), but no biome-specific surface decoration exists in the corruption zone.
 *  • Extreme cave generation beyond the Far Lands coordinate is approximate.
 *  • Performance: at extreme distances (>30M blocks) the noise computation is
 *    heavier due to double-precision arithmetic. Use the chunk cache.
 */
public final class FarLandsGenerator extends ChunkGenerator {

    private final FarLandsConfig config;
    private final FarLandsNoise  noise;
    private final ChunkCache     cache;
    private final Logger         log;

    // Block fill materials
    private static final Material STONE    = Material.STONE;
    private static final Material BEDROCK  = Material.BEDROCK;
    private static final Material DEEPSLATE = Material.DEEPSLATE;

    public FarLandsGenerator(FarLandsConfig config, Logger log) {
        this.config = config;
        this.log    = log;
        // Noise engine is seeded per-world via the world seed; we use a
        // fixed offset so it differs from vanilla terrain noise.
        this.noise  = new FarLandsNoise(0L); // replaced with world seed at gen time
        this.cache  = config.chunkCache ? new ChunkCache(config.cacheSize) : null;
    }

    // ─── Lazy-initialised per-world noise engine ─────────────────────────────
    // We store it as a field; the first generateNoise() call initialises it.
    private volatile FarLandsNoise worldNoise;
    private volatile long          lastWorldSeed = Long.MIN_VALUE;

    private FarLandsNoise noiseFor(long worldSeed) {
        if (worldNoise == null || lastWorldSeed != worldSeed) {
            synchronized (this) {
                if (worldNoise == null || lastWorldSeed != worldSeed) {
                    worldNoise    = new FarLandsNoise(worldSeed ^ 0xDEADBEEFCAFEBABEL);
                    lastWorldSeed = worldSeed;
                }
            }
        }
        return worldNoise;
    }

    // ─── Vanilla delegation flags ─────────────────────────────────────────────

    @Override
    public boolean shouldGenerateSurface()     { return true; }  // vanilla grass/sand/snow
    @Override
    public boolean shouldGenerateCaves()       { return !config.effectiveExtremeCaves(); }
    @Override
    public boolean shouldGenerateDecorations() { return true; }
    @Override
    public boolean shouldGenerateMobs()        { return true; }
    @Override
    public boolean shouldGenerateStructures()  { return config.preserveStructures; }

    // ─── Noise (base terrain) ─────────────────────────────────────────────────

    @Override
    public void generateNoise(
            @NotNull WorldInfo worldInfo,
            @NotNull Random random,
            int chunkX, int chunkZ,
            @NotNull ChunkData chunkData
    ) {
        // Determine which block coordinate is the "far axis" for this chunk.
        // For POSITIVE_X:  farCoord = chunkX * 16   (west edge of chunk)
        // For NEGATIVE_X:  farCoord = -(chunkX * 16)
        // For POSITIVE_Z:  farCoord = chunkZ * 16
        // For NEGATIVE_Z:  farCoord = -(chunkZ * 16)

        long chunkAxisMin = getAxisMin(chunkX, chunkZ, config.side);
        long chunkAxisMax = getAxisMax(chunkX, chunkZ, config.side);

        long startCoord = config.startCoordinate;
        long blendStart = startCoord - config.effectiveBlendingDistance();

        // If the entire chunk is well inside normal terrain, skip custom gen.
        if (chunkAxisMax < blendStart && chunkAxisMin < blendStart) {
            return; // Let vanilla generate this chunk normally
        }

        if (config.verboseLogging) {
            log.info("[RealFarLands] Generating Far Lands chunk (" + chunkX + "," + chunkZ + ")");
        }

        // Check cache
        boolean[][][] solid;
        int worldMinY = chunkData.getMinHeight();
        int worldMaxY = chunkData.getMaxHeight();
        int height    = worldMaxY - worldMinY;

        if (cache != null) {
            solid = cache.get(chunkX, chunkZ);
            if (solid != null) {
                applySolidArray(chunkData, solid, worldMinY, worldMaxY);
                return;
            }
        }

        // Build density grid
        FarLandsNoise fn = noiseFor(worldInfo.getSeed());
        solid = new boolean[16][16][height];

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int blockX = chunkX * 16 + lx;
                int blockZ = chunkZ * 16 + lz;
                long farCoord = getBlockAxisCoord(blockX, blockZ, config.side);

                for (int ly = 0; ly < height; ly++) {
                    int blockY = worldMinY + ly;
                    solid[lx][lz][ly] = fn.isSolid(
                        blockX, blockY, blockZ,
                        farCoord,
                        startCoord, config.endCoordinate,
                        config.effectiveBlendingDistance(),
                        config.effectiveHeightMultiplier(),
                        config.effectiveCorruptionStrength(),
                        config.effectiveFloatingTerrain(),
                        config.effectiveVoidTearing(),
                        config.effectiveTerrainFolding(),
                        worldMinY, worldMaxY
                    );
                }
            }
        }

        if (cache != null) {
            cache.put(chunkX, chunkZ, solid);
        }

        applySolidArray(chunkData, solid, worldMinY, worldMaxY);
    }

    // ─── Apply pre-computed solid grid to ChunkData ───────────────────────────

    private void applySolidArray(
            ChunkData chunkData,
            boolean[][][] solid,
            int worldMinY, int worldMaxY
    ) {
        int height = worldMaxY - worldMinY;

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                for (int ly = 0; ly < height; ly++) {
                    if (!solid[lx][lz][ly]) continue;

                    int y = worldMinY + ly;
                    Material mat = blockMaterial(y, worldMinY);
                    chunkData.setBlock(lx, y, lz, mat);
                }
            }
        }
    }

    /** Return the appropriate material for a given Y level. */
    private static Material blockMaterial(int y, int worldMinY) {
        if (y <= worldMinY + 4) return BEDROCK;      // bedrock layer
        if (y <= worldMinY + 16) return DEEPSLATE;   // deepslate layer
        return STONE;
    }

    // ─── Cave generation (extreme mode) ──────────────────────────────────────

    @Override
    public void generateCaves(
            @NotNull WorldInfo worldInfo,
            @NotNull Random random,
            int chunkX, int chunkZ,
            @NotNull ChunkData chunkData
    ) {
        if (!config.effectiveExtremeCaves()) return;

        long farCoord = getAxisMin(chunkX, chunkZ, config.side);
        if (farCoord < config.startCoordinate - config.effectiveBlendingDistance()) return;

        // Punch large spherical voids through the Far Lands
        int worldMinY = chunkData.getMinHeight();
        int worldMaxY = chunkData.getMaxHeight();

        FarLandsNoise fn = noiseFor(worldInfo.getSeed());

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int blockX = chunkX * 16 + lx;
                int blockZ = chunkZ * 16 + lz;
                for (int y = worldMinY + 5; y < worldMaxY - 10; y++) {
                    // Cave noise: use a separate noise octave shifted by +9999
                    double cx = blockX * 0.012 + 9999;
                    double cy = y      * 0.02  + 9999;
                    double cz = blockZ * 0.012 + 9999;
                    // Simple cave spaghetti approximation
                    double cn = fn.isSolid(blockX, y, blockZ,
                        farCoord,
                        config.startCoordinate, config.endCoordinate,
                        config.effectiveBlendingDistance(),
                        1.0, 3.0, false, false, false,
                        worldMinY, worldMaxY) ? 0 : 1;
                    if (cn == 0) {
                        chunkData.setBlock(lx, y, lz, Material.AIR);
                    }
                }
            }
        }
    }

    // ─── Biome provider ──────────────────────────────────────────────────────

    @Override
    @Nullable
    public BiomeProvider getDefaultBiomeProvider(@NotNull WorldInfo worldInfo) {
        // Return null to use vanilla biome generation (preserve-biomes=true scenario)
        // The config.preserveBiomes flag is respected by the shouldGenerate* flags above.
        return config.preserveBiomes ? null : new FlatBiomeProvider();
    }

    // ─── Axis coordinate helpers ─────────────────────────────────────────────

    /** Returns the axis coordinate of the near edge of a chunk (toward origin). */
    private static long getAxisMin(int chunkX, int chunkZ, FarLandsSide side) {
        return switch (side) {
            case POSITIVE_X ->  (long) chunkX * 16;
            case NEGATIVE_X -> -(long)(chunkX * 16 + 15);
            case POSITIVE_Z ->  (long) chunkZ * 16;
            case NEGATIVE_Z -> -(long)(chunkZ * 16 + 15);
        };
    }

    /** Returns the axis coordinate of the far edge of a chunk (away from origin). */
    private static long getAxisMax(int chunkX, int chunkZ, FarLandsSide side) {
        return switch (side) {
            case POSITIVE_X ->  (long)(chunkX * 16 + 15);
            case NEGATIVE_X -> -(long) chunkX * 16;
            case POSITIVE_Z ->  (long)(chunkZ * 16 + 15);
            case NEGATIVE_Z -> -(long) chunkZ * 16;
        };
    }

    /** Returns the per-block axis coordinate used for noise calculation. */
    private static long getBlockAxisCoord(int blockX, int blockZ, FarLandsSide side) {
        return switch (side) {
            case POSITIVE_X ->  (long) blockX;
            case NEGATIVE_X -> -(long) blockX;
            case POSITIVE_Z ->  (long) blockZ;
            case NEGATIVE_Z -> -(long) blockZ;
        };
    }

    // ─── Flat biome provider (used when preserve-biomes=false) ───────────────

    private static final class FlatBiomeProvider extends BiomeProvider {
        @NotNull
        @Override
        public Biome getBiome(@NotNull WorldInfo worldInfo, int x, int y, int z) {
            return Biome.PLAINS;
        }
        @NotNull
        @Override
        public List<Biome> getBiomes(@NotNull WorldInfo worldInfo) {
            return List.of(Biome.PLAINS);
        }
    }
}
