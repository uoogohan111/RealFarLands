# RealFarLands

A production-quality Paper/Spigot/Purpur plugin that recreates the classic
**Minecraft Beta Far Lands** terrain corruption effect using modern chunk
generation APIs. No fake walls — the terrain is algorithmically generated to
resemble the original overflow behavior.

---

## What Are the Far Lands?

In Minecraft Beta (≤1.7.3), world generation used a multi-octave Perlin noise
system. At extreme distances (≈12,550,821 blocks from the origin), the
floating-point accumulators in that system overflowed their precision limits,
causing near-infinite alternating +/− density values. The visual result: vast,
near-vertical walls of stone stretching from bedrock to sky, riddled with
enormous cave systems and floating overhangs.

This plugin mathematically recreates that visual result on modern servers.

---

## Compatibility

| Server software | Supported |
|----------------|-----------|
| Paper 1.21+    | ✅ Full support |
| Spigot 1.21+   | ✅ Supported (some async features may be limited) |
| Purpur 1.21+   | ✅ Full support |
| Folia          | ⚠️ Not tested |

**Requires Java 21+.**

---

## Installation

1. **Build or download** the plugin JAR (see [Compilation](#compilation)).
2. **Drop** `RealFarLands-1.0.0.jar` into your server's `plugins/` folder.
3. **Configure** the generator in `bukkit.yml`:

```yaml
# bukkit.yml
worlds:
  world:                     # replace with your world name
    generator: RealFarLands
```

> **Multiverse** users: `/mv create farlands normal -g RealFarLands`

4. **Start the server.** The plugin will generate `plugins/RealFarLands/config.yml`.
5. **Edit config.yml** — set `farlands.start-coordinate` to a low value (e.g. `1000`) for testing.
6. **Reload:** `/farlands reload`
7. **Teleport:** `/farlands tp`

---

## Configuration

`plugins/RealFarLands/config.yml`:

```yaml
enabled: true
world: world

farlands:
  side: POSITIVE_X          # POSITIVE_X | NEGATIVE_X | POSITIVE_Z | NEGATIVE_Z
  start-coordinate: 12500000 # where corruption begins (~12.5M = classic Beta)
  end-coordinate: 30000000   # where corruption is fully maximized
  blending-distance: 5000    # blocks of smooth transition
  height-multiplier: 2.5     # taller = higher walls (classic Beta = 2.5)
  noise-corruption-strength: 1.0  # higher = more chaotic
  preserve-biomes: true      # keep vanilla biome layout
  preserve-structures: false # disable structures inside Far Lands

  floating-terrain: true     # floating island bands above main mass
  void-tearing: false        # random void slots through terrain
  terrain-folding: true      # inverted overhangs
  extreme-caves: false       # large cave systems inside Far Lands

  # Override everything with a preset:
  # NONE | CLASSIC_BETA | EXTREME | CURSED | SMOOTH
  preset: NONE

performance:
  async-pre-generation: true
  chunk-cache: true
  cache-size: 4096

debug:
  show-particles: false      # orange particle wall at start-coordinate
  verbose-logging: false
```

### Presets

| Preset | Description |
|--------|-------------|
| `NONE` | Use values from config |
| `CLASSIC_BETA` | Closely matches Beta 1.7.3 behavior |
| `EXTREME` | Double corruption, taller walls |
| `CURSED` | Maximum chaos: void tears, folding, extreme caves |
| `SMOOTH` | Very gentle blending over 20k blocks |

---

## Commands

| Command | Permission | Description |
|---------|------------|-------------|
| `/farlands info` | — | Show current settings summary |
| `/farlands tp` | `farlands.tp` | Teleport to the Far Lands border |
| `/farlands reload` | `farlands.admin` | Reload config.yml |
| `/farlands debug` | `farlands.admin` | Toggle debug particles |

Aliases: `/fl`, `/rfl`

---

## Compilation

### Requirements

- Java 21+ JDK (`JAVA_HOME` set)
- Internet access (to download Gradle and Paper API during first build)

### Steps

```bash
# Clone the repository
git clone https://github.com/your-org/RealFarLands.git
cd RealFarLands

# Build (downloads Gradle automatically)
./gradlew shadowJar

# Output: build/libs/RealFarLands-1.0.0.jar
```

### GitHub CLI (gh)

```bash
# Install GitHub CLI, then:
gh repo clone your-org/RealFarLands
cd RealFarLands
./gradlew shadowJar
```

---

## GitHub Actions

The included `.github/workflows/build.yml` automatically:

- Triggers on every push and pull request
- Uses **Ubuntu latest** with **Temurin JDK 21**
- Caches Gradle dependencies for fast rebuilds
- Uploads the compiled JAR as a workflow artifact (retained 30 days)

```yaml
# .github/workflows/build.yml
# Customize the JDK version if needed:
      - name: Set up JDK 21 (Temurin)
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"
```

To trigger a manual build:
```bash
gh workflow run build.yml
```

---

## How It Works

### The Original Beta Bug

Beta Minecraft used 9-octave Perlin noise with floating-point accumulation.
When the coordinate axis value exceeded ~12.5 million, the `float` mantissa
bits could no longer represent the integer part of the offset calculation,
causing the fractional part to repeat or collapse — every noise query returned
either maximum or minimum density, creating near-solid walls alternating with
void stripes.

### The Recreation Algorithm

```
normalDensity  = 5-octave simplex noise(x, y, z)
axisCoord      = block coordinate on the configured axis
progress       = smoothstep( (axisCoord − start) / blendRange )  ∈ [0,1]
primaryWave    = sin(axisCoord × frequency)^0.15   ← simulates sign-flipping overflow
perpNoise      = 4-octave noise(perpX, perpZ)       ← column-by-column variation
corruptDensity = primaryWave × (0.6 + perpNoise×0.4) + groundBias + optional features
finalDensity   = lerp(normalDensity, corruptDensity, progress)
isSolid        = finalDensity > lerp(normalThreshold, corruptThreshold, progress)
```

The **corrupt threshold** collapses toward zero as `progress → 1`, matching
the overflow behavior where positive and negative infinities cancel out,
leaving density near zero (solid by default).

### Key Differences from Original Beta

| Feature | Original Beta | This Plugin |
|---------|--------------|-------------|
| Noise algorithm | Integer Perlin with float32 overflow | Simplex + mathematical simulation |
| Trigger | Hardware float precision limit | Configurable coordinate threshold |
| One side only | No (all four sides) | Yes (configurable) |
| Blending | Instant wall | Configurable smooth transition |
| Performance | N/A (native engine) | Chunk cache + async support |

---

## Performance Notes

- The chunk cache significantly reduces CPU usage. Leave `chunk-cache: true`.
- At very high coordinates (>30M) double-precision arithmetic is heavier.
  Pre-generate chunks with a tool like Chunky before players explore.
- Use `async-pre-generation: true` to reduce main-thread stalls.
- On Paper 1.21+, chunk generation is largely off the main thread already.

---

## License

MIT License — see `LICENSE` file.
