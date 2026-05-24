package dev.realfarlands.generator;

/**
 * FarLandsNoise
 * =============
 * Replicates the original Minecraft Beta 1.7.3 Far Lands noise corruption.
 *
 * ─── Background ───────────────────────────────────────────────────────────────
 * In Beta Minecraft, world generation used a multi-octave Perlin noise system
 * ("OverworldNoiseRouter") that accumulated floating-point offsets per column.
 * At large coordinates the accumulator values exceeded float precision limits
 * (around 12,550,821 blocks from origin), causing extreme alternating high/low
 * density values – the "Far Lands" overflow artifact.
 *
 * The result: huge, near-vertical pillars and overhangs of solid stone
 * stretching from bedrock to sky, riddled with caves.
 *
 * ─── Modern Recreation ────────────────────────────────────────────────────────
 * Modern Paper no longer uses that legacy noise system, so we cannot trigger
 * the original overflow. Instead we mathematically recreate the *visual result*:
 *
 *   1. Use standard simplex/Perlin octave noise for base terrain (like normal).
 *   2. Near the far-lands coordinate, inject a "corruption" value derived from
 *      a high-frequency, high-amplitude noise pattern that collapses into near-
 *      solid walls with void striations – matching the visual appearance.
 *   3. Blend smoothly from normal → corrupted based on distance.
 *
 * ─── The Algorithm ────────────────────────────────────────────────────────────
 *
 *  normalDensity  = standard octave noise at (x, y, z)              [−1 .. 1]
 *  progress       = smoothstep( (coord − start) / blendDist )       [ 0 .. 1]
 *  corruption     = corruptNoise(x, y, z, progress)                 [−1 .. 1]
 *  finalDensity   = lerp(normalDensity, corruption, progress)
 *
 *  isSolid = finalDensity > threshold(y, worldHeight)
 *
 * The corruption noise uses:
 *   - A low-frequency primary wave that creates the massive vertical walls
 *   - High-frequency secondary noise for the random striations
 *   - A y-bias term that keeps terrain rooted to the ground (like Beta)
 */
public final class FarLandsNoise {

    // -----------------------------------------------------------------------
    // Simplex Noise Implementation (self-contained, no external deps)
    // -----------------------------------------------------------------------
    // Based on Stefan Gustavson's public-domain simplex noise.

    private final long seed;
    private final int[] perm = new int[512];

    private static final int[][] GRAD3 = {
        {1,1,0},{-1,1,0},{1,-1,0},{-1,-1,0},
        {1,0,1},{-1,0,1},{1,0,-1},{-1,0,-1},
        {0,1,1},{0,-1,1},{0,1,-1},{0,-1,-1}
    };

    public FarLandsNoise(long seed) {
        this.seed = seed;
        // Build a seeded permutation table
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) p[i] = i;
        // Fisher-Yates shuffle seeded
        java.util.Random rng = new java.util.Random(seed);
        for (int i = 255; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int tmp = p[i]; p[i] = p[j]; p[j] = tmp;
        }
        for (int i = 0; i < 512; i++) perm[i] = p[i & 255];
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Determine whether a block at (blockX, blockY, blockZ) should be solid.
     *
     * @param blockX      absolute world X coordinate
     * @param blockY      absolute world Y coordinate (0..319 for modern overworld)
     * @param blockZ      absolute world Z coordinate
     * @param farCoord    the axis coordinate driving the Far Lands effect
     * @param startCoord  coordinate where corruption begins
     * @param endCoord    coordinate where corruption is maximal
     * @param blendDist   blending zone width in blocks
     * @param heightMult  height scale multiplier
     * @param strength    corruption noise strength
     * @param floatTerrain  enable floating island bands
     * @param voidTear    enable void tear effect
     * @param terrainFold enable terrain folding overhangs
     * @param worldMinY   world minimum Y (typically -64)
     * @param worldMaxY   world maximum Y (typically 320)
     * @return true if this block position should be stone
     */
    public boolean isSolid(
            int blockX, int blockY, int blockZ,
            long farCoord,
            long startCoord, long endCoord, int blendDist,
            double heightMult, double strength,
            boolean floatTerrain, boolean voidTear, boolean terrainFold,
            int worldMinY, int worldMaxY
    ) {
        int worldHeight = worldMaxY - worldMinY;
        // Normalise Y to [0..1] within the world
        double normY = (double)(blockY - worldMinY) / worldHeight;

        // ── Normal terrain density ──────────────────────────────────────────
        double normalDensity = normalTerrainDensity(blockX, blockY, blockZ, normY, worldMinY, worldMaxY);

        // ── Corruption progress [0..1] ──────────────────────────────────────
        double dist = Math.abs(farCoord) - Math.abs(startCoord);
        double blendRange = Math.abs(endCoord - startCoord);
        double rawProgress = blendRange > 0 ? dist / blendRange : 1.0;
        double progress = smoothstep(Math.max(0.0, Math.min(1.0, rawProgress)));

        if (progress <= 0.0) {
            // Fully inside normal terrain – just use normal density
            double seaThreshold = normalThreshold(normY);
            return normalDensity > seaThreshold;
        }

        // ── Far Lands corruption density ───────────────────────────────────
        double corrupt = corruptionDensity(
            blockX, blockY, blockZ,
            farCoord, startCoord,
            progress, strength,
            floatTerrain, voidTear, terrainFold,
            normY, worldMinY, worldMaxY
        );

        // ── Blend normal → corrupt ──────────────────────────────────────────
        double finalDensity = lerp(normalDensity, corrupt, progress);

        // ── Threshold ───────────────────────────────────────────────────────
        // In the Far Lands the threshold collapses toward 0 (near-solid),
        // matching the near-infinite density overflow of the original.
        double normalThresh  = normalThreshold(normY);
        double corruptThresh = corruptThreshold(normY, progress, heightMult);
        double threshold     = lerp(normalThresh, corruptThresh, progress);

        return finalDensity > threshold;
    }

    // -----------------------------------------------------------------------
    // Normal terrain density (vanilla-like octave noise)
    // -----------------------------------------------------------------------

    private double normalTerrainDensity(int x, int y, int z, double normY, int minY, int maxY) {
        // 5-octave noise for normal overworld terrain
        double nx = x * 0.005;
        double ny = y * 0.008;
        double nz = z * 0.005;

        double density = 0;
        double amp = 1.0, freq = 1.0, total = 0;
        for (int o = 0; o < 5; o++) {
            density += octaveNoise3D(nx * freq + o * 17.3, ny * freq + o * 31.7, nz * freq + o * 13.9) * amp;
            total   += amp;
            amp     *= 0.5;
            freq    *= 2.0;
        }
        density /= total;

        // Y-bias: positive density near ground → terrain bulges upward from floor
        double yBias = 1.0 - normY * 2.5;
        return density + yBias * 0.4;
    }

    private double normalThreshold(double normY) {
        // Terrain is solid below ~40% of world height (sea-level-like)
        // Above that it becomes increasingly rare → sky is mostly air
        return normY * 1.2 - 0.35;
    }

    // -----------------------------------------------------------------------
    // Far Lands corruption density
    // -----------------------------------------------------------------------

    /**
     * Generates the classic Beta Far Lands visual: near-vertical walls of stone
     * with alternating void stripes, all rooted to the ground.
     *
     * Original mechanism: the noise accumulators overflowed float32, causing
     * their derivatives to flip rapidly between +MAX and -MAX. We reproduce
     * this by using a very high-frequency primary wave on the Far Lands axis,
     * combined with low-frequency noise in the perpendicular axes.
     */
    private double corruptionDensity(
            int blockX, int blockY, int blockZ,
            long farCoord, long startCoord,
            double progress, double strength,
            boolean floatTerrain, boolean voidTear, boolean terrainFold,
            double normY, int worldMinY, int worldMaxY
    ) {
        // --- Primary overflow wave ---
        // Simulates float32 mantissa wrapping: a very high-frequency sine-like
        // oscillation on the far-axis coordinate, clamped to ±1.
        // Beta used ~9 octaves of Perlin; at overflow the octave offsets wrap
        // back to zero, making all octaves constructively/destructively interfere.
        double axisScale = 0.00012 * strength;  // frequency grows with strength
        double primaryWave = Math.sin(farCoord * axisScale * Math.PI);
        // Saturate the wave – in beta the overflow was clipped by bit-width
        primaryWave = Math.signum(primaryWave) * Math.pow(Math.abs(primaryWave), 0.15);

        // --- Secondary low-frequency noise (cross-axis variation) ---
        // This breaks the repetitive wall into the irregular columns seen in beta.
        double perpScale = 0.002;
        double perpCoord1, perpCoord2;
        // Perpendicular coordinates to the far-axis
        perpCoord1 = blockX;
        perpCoord2 = blockZ;

        double perpNoise  = octaveNoise2D(perpCoord1 * perpScale, perpCoord2 * perpScale, 4);
        double perpNoise2 = octaveNoise2D(perpCoord1 * perpScale * 4, perpCoord2 * perpScale * 4, 3) * 0.25;

        // --- Y density (ground bias – columns are rooted to bedrock) ---
        // In beta the noise Y axis was stretched, causing the overflow density to
        // remain high near the bottom (solid ground) and taper near the top.
        double groundBias = 1.0 - normY * 2.0;  // +1 at y=0, -1 at y=top
        double skyFalloff = Math.max(0, 1.0 - Math.pow(normY * 1.6, 3));  // hard sky limit

        // --- Floating terrain bands ---
        double floatBonus = 0;
        if (floatTerrain && normY > 0.35 && normY < 0.75) {
            // Isolated floating islands / overhangs above the main mass
            double fNoise = octaveNoise3D(
                blockX * 0.004, blockY * 0.012, blockZ * 0.004, 3);
            double fBand  = 1.0 - Math.abs(normY - 0.5) * 4.0;
            floatBonus = Math.max(0, fNoise * fBand * 0.6);
        }

        // --- Void tearing ---
        // Random vertical void slots through the Far Lands (very rare in beta,
        // but a spectacular visual when they appear).
        double voidTearPenalty = 0;
        if (voidTear) {
            double vNoise = octaveNoise2D(perpCoord1 * 0.0008, perpCoord2 * 0.0008, 2);
            if (vNoise > 0.7) {
                voidTearPenalty = (vNoise - 0.7) * 8.0;
            }
        }

        // --- Terrain folding (overhangs / inverted spikes) ---
        double foldBonus = 0;
        if (terrainFold && normY > 0.25 && normY < 0.65) {
            double foldNoise = octaveNoise3D(
                blockX * 0.006, blockY * 0.006 + 100, blockZ * 0.006, 3);
            foldBonus = foldNoise * 0.35 * progress;
        }

        // --- Assemble ---
        // The primary wave carries most of the corruption.
        // Secondary noise modulates which wall-sections are solid vs void.
        double corruption = primaryWave * (0.6 + perpNoise * 0.4 + perpNoise2);
        corruption *= skyFalloff;
        corruption += groundBias * 0.5;
        corruption += floatBonus;
        corruption -= voidTearPenalty;
        corruption += foldBonus;

        return corruption;
    }

    /**
     * Threshold used in the Far Lands region.
     * As progress → 1.0, the threshold approaches zero (near-solid behavior),
     * which is what happens when the original noise overflowed:
     * positive and negative infinities cancel, leaving ~0 or alternating sign.
     */
    private double corruptThreshold(double normY, double progress, double heightMult) {
        // Stretch out the solid region vertically (heightMult > 1 = taller walls)
        double stretchedY = normY / heightMult;
        double base = normalThreshold(stretchedY);
        // Collapse threshold toward zero with progress (more solid)
        return lerp(base, 0.0, progress * 0.85);
    }

    // -----------------------------------------------------------------------
    // Octave noise helpers (Perlin-like via simplex)
    // -----------------------------------------------------------------------

    /** 3D octave noise, normalised to roughly [−1, 1]. */
    private double octaveNoise3D(double x, double y, double z) {
        return octaveNoise3D(x, y, z, 5);
    }
    private double octaveNoise3D(double x, double y, double z, int octaves) {
        double val = 0, amp = 1, freq = 1, total = 0;
        for (int o = 0; o < octaves; o++) {
            val   += noise3D(x * freq, y * freq, z * freq) * amp;
            total += amp;
            amp   *= 0.5;
            freq  *= 2.0;
        }
        return val / total;
    }

    /** 2D octave noise, normalised to roughly [−1, 1]. */
    private double octaveNoise2D(double x, double z, int octaves) {
        double val = 0, amp = 1, freq = 1, total = 0;
        for (int o = 0; o < octaves; o++) {
            val   += noise3D(x * freq, 0, z * freq) * amp;
            total += amp;
            amp   *= 0.5;
            freq  *= 2.0;
        }
        return val / total;
    }

    // -----------------------------------------------------------------------
    // Core simplex-style 3D noise (Stefan Gustavson algorithm, public domain)
    // -----------------------------------------------------------------------

    private double noise3D(double xin, double yin, double zin) {
        double n0, n1, n2, n3;
        final double F3 = 1.0 / 3.0;
        final double G3 = 1.0 / 6.0;
        double s = (xin + yin + zin) * F3;
        int i = fastFloor(xin + s);
        int j = fastFloor(yin + s);
        int k = fastFloor(zin + s);
        double t = (i + j + k) * G3;
        double X0 = i - t;
        double Y0 = j - t;
        double Z0 = k - t;
        double x0 = xin - X0;
        double y0 = yin - Y0;
        double z0 = zin - Z0;
        int i1, j1, k1, i2, j2, k2;
        if (x0 >= y0) {
            if (y0 >= z0)      { i1=1; j1=0; k1=0; i2=1; j2=1; k2=0; }
            else if (x0 >= z0) { i1=1; j1=0; k1=0; i2=1; j2=0; k2=1; }
            else               { i1=0; j1=0; k1=1; i2=1; j2=0; k2=1; }
        } else {
            if (y0 < z0)       { i1=0; j1=0; k1=1; i2=0; j2=1; k2=1; }
            else if (x0 < z0)  { i1=0; j1=1; k1=0; i2=0; j2=1; k2=1; }
            else               { i1=0; j1=1; k1=0; i2=1; j2=1; k2=0; }
        }
        double x1 = x0 - i1 + G3;
        double y1 = y0 - j1 + G3;
        double z1 = z0 - k1 + G3;
        double x2 = x0 - i2 + 2.0*G3;
        double y2 = y0 - j2 + 2.0*G3;
        double z2 = z0 - k2 + 2.0*G3;
        double x3 = x0 - 1.0 + 3.0*G3;
        double y3 = y0 - 1.0 + 3.0*G3;
        double z3 = z0 - 1.0 + 3.0*G3;
        int ii = i & 255, jj = j & 255, kk = k & 255;
        int gi0 = perm[ii +      perm[jj +      perm[kk   ]]] % 12;
        int gi1 = perm[ii + i1 + perm[jj + j1 + perm[kk+k1]]] % 12;
        int gi2 = perm[ii + i2 + perm[jj + j2 + perm[kk+k2]]] % 12;
        int gi3 = perm[ii + 1  + perm[jj + 1  + perm[kk+1 ]]] % 12;
        double t0 = 0.6 - x0*x0 - y0*y0 - z0*z0;
        n0 = t0 < 0 ? 0 : (t0*t0*t0*t0) * dot(GRAD3[gi0], x0, y0, z0);
        double t1 = 0.6 - x1*x1 - y1*y1 - z1*z1;
        n1 = t1 < 0 ? 0 : (t1*t1*t1*t1) * dot(GRAD3[gi1], x1, y1, z1);
        double t2 = 0.6 - x2*x2 - y2*y2 - z2*z2;
        n2 = t2 < 0 ? 0 : (t2*t2*t2*t2) * dot(GRAD3[gi2], x2, y2, z2);
        double t3 = 0.6 - x3*x3 - y3*y3 - z3*z3;
        n3 = t3 < 0 ? 0 : (t3*t3*t3*t3) * dot(GRAD3[gi3], x3, y3, z3);
        return 32.0 * (n0 + n1 + n2 + n3);
    }

    // -----------------------------------------------------------------------
    // Math utilities
    // -----------------------------------------------------------------------

    private static double dot(int[] g, double x, double y, double z) {
        return g[0]*x + g[1]*y + g[2]*z;
    }

    private static int fastFloor(double x) {
        int xi = (int) x;
        return x < xi ? xi - 1 : xi;
    }

    /** Smooth Hermite interpolation: t² (3 − 2t) */
    private static double smoothstep(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    private static double lerp(double a, double b, double t) {
        return a + t * (b - a);
    }
}
