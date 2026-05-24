package dev.realfarlands.listener;

import dev.realfarlands.RealFarLands;
import dev.realfarlands.config.FarLandsConfig;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * Shows particle visualisation of the Far Lands corruption zone
 * when debug particles are enabled (config or /farlands debug).
 *
 * Draws a curtain of particles at the start-coordinate boundary
 * on the configured side.
 */
public final class DebugParticleListener implements Listener {

    private final RealFarLands plugin;

    // Rate-limit: only render once every N move events per player
    private static final int RENDER_INTERVAL = 20;
    private int tick = 0;

    public DebugParticleListener(RealFarLands plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!plugin.isDebugParticlesEnabled()) return;
        if (++tick % RENDER_INTERVAL != 0) return;

        Player player = event.getPlayer();
        FarLandsConfig cfg = plugin.getFarLandsConfig();
        World world = player.getWorld();

        // Only show in the configured world
        if (!world.getName().equals(cfg.world)) return;

        Location pLoc = player.getLocation();
        long start = cfg.startCoordinate;

        // Only render if player is within 1000 blocks of the boundary
        long axisCoord = switch (cfg.side) {
            case POSITIVE_X ->  (long) pLoc.getBlockX();
            case NEGATIVE_X -> -(long) pLoc.getBlockX();
            case POSITIVE_Z ->  (long) pLoc.getBlockZ();
            case NEGATIVE_Z -> -(long) pLoc.getBlockZ();
        };

        if (Math.abs(axisCoord - start) > 1000) return;

        // Draw a vertical column of particles at the boundary
        for (int y = world.getMinHeight(); y < world.getMaxHeight(); y += 8) {
            Location particleLoc = switch (cfg.side) {
                case POSITIVE_X -> new Location(world, start, y, pLoc.getBlockZ());
                case NEGATIVE_X -> new Location(world, -start, y, pLoc.getBlockZ());
                case POSITIVE_Z -> new Location(world, pLoc.getBlockX(), y, start);
                case NEGATIVE_Z -> new Location(world, pLoc.getBlockX(), y, -start);
            };
            world.spawnParticle(Particle.DUST,
                particleLoc, 1,
                new Particle.DustOptions(
                    org.bukkit.Color.fromRGB(255, 100, 0), 1.5f));
        }
    }
}
