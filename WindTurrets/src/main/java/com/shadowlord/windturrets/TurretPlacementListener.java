package shadowlord.windturrets;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Dispenser;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class TurretPlacementListener implements Listener {

    private final WindTurrets plugin;
    private final Map<UUID, Long> lastPlacementAt = new HashMap<>();

    public TurretPlacementListener(WindTurrets plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onRightClick(PlayerInteractEvent ev) {
        if (ev.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = ev.getPlayer();
        ItemStack item = ev.getItem();
        if (item == null) return;

        // Only react to the crafted Turret Core item
        if (!Util.isTurretCore(item, plugin.getConfig())) return;

        // clicked block must be QUARTZ_BLOCK (pillar) and block below must be DISPENSER facing UP
        org.bukkit.block.Block clicked = ev.getClickedBlock();
        if (clicked == null) return;
        if (clicked.getType() != Material.QUARTZ_BLOCK) {
            p.sendMessage(ChatColor.translateAlternateColorCodes('&', "&cYou must click a Quartz Pillar block as the turret top."));
            return;
        }

        org.bukkit.block.Block below = clicked.getRelative(BlockFace.DOWN);
        if (below == null || below.getType() != Material.DISPENSER) {
            p.sendMessage(ChatColor.translateAlternateColorCodes('&', "&cPlace the Quartz Pillar on top of a Dispenser facing up."));
            return;
        }

        // Verify dispenser facing up using 1.12 MaterialData API (org.bukkit.material.Dispenser)
        boolean facingUp = false;
        try {
            org.bukkit.material.MaterialData md = below.getState().getData();
            if (md instanceof org.bukkit.material.Dispenser) {
                org.bukkit.material.Dispenser d = (org.bukkit.material.Dispenser) md;
                if (d.getFacing() == BlockFace.UP) facingUp = true;
            } else {
                // fallback: if we can't determine facing, be permissive if quartz sits directly above
                facingUp = true;
            }
        } catch (Throwable t) {
            // permissive fallback on unexpected errors for 1.12 compatibility
            facingUp = true;
        }

        if (!facingUp) {
            p.sendMessage(ChatColor.translateAlternateColorCodes('&', "&cDispenser must be facing up."));
            return;
        }

        long now = System.currentTimeMillis();
        long cooldownMillis = Math.max(0L, plugin.getConfig().getLong("limits.placement_cooldown_seconds", 3L)) * 1000L;
        long lastPlacement = lastPlacementAt.getOrDefault(p.getUniqueId(), 0L);
        if (cooldownMillis > 0 && now - lastPlacement < cooldownMillis) {
            long remainingSeconds = (cooldownMillis - (now - lastPlacement) + 999L) / 1000L;
            p.sendMessage(ChatColor.translateAlternateColorCodes('&', "&cWait " + remainingSeconds + " seconds before placing another turret."));
            ev.setCancelled(true);
            return;
        }

        int perPlayerLimit = Math.max(0, plugin.getConfig().getInt("limits.per_player", 5));
        int playerTurrets = 0;
        int worldTurrets = 0;
        for (Turret turret : plugin.getTurrets().values()) {
            if (turret.isOwnedBy(p.getUniqueId(), p.getName())) playerTurrets++;
            if (turret.getWorldName().equals(clicked.getWorld().getName())) worldTurrets++;
        }
        if (perPlayerLimit > 0 && playerTurrets >= perPlayerLimit) {
            p.sendMessage(ChatColor.translateAlternateColorCodes('&', "&cYou have reached your turret limit of " + perPlayerLimit + "."));
            ev.setCancelled(true);
            return;
        }

        int perWorldLimit = Math.max(0, plugin.getConfig().getInt("limits.per_world", 50));
        if (perWorldLimit > 0 && worldTurrets >= perWorldLimit) {
            p.sendMessage(ChatColor.translateAlternateColorCodes('&', "&cThis world has reached its turret limit of " + perWorldLimit + "."));
            ev.setCancelled(true);
            return;
        }

        // All checks pass: register turret
        String id = plugin.getNextTurretId(p.getUniqueId(), p.getName());
        int radius = plugin.getConfig().getInt("default_radius", 10);
        int magazineSize = Math.max(1, plugin.getConfig().getInt("magazine_size", 30));
        Turret.FireMode fireMode = Turret.FireMode.parse(plugin.getConfig().getString("default_fire_mode", "semi-automatic"));
        if (fireMode == null) fireMode = Turret.FireMode.SEMI_AUTOMATIC;
        Turret.TargetPriority priority = Turret.TargetPriority.parse(plugin.getConfig().getString("default_target_priority", "nearest"));
        if (priority == null) priority = Turret.TargetPriority.NEAREST;
        Turret t = new Turret(id, p.getName(), p.getUniqueId(), clicked.getLocation(), radius, true,
            fireMode, magazineSize, priority);
        plugin.addTurret(t);
        lastPlacementAt.put(p.getUniqueId(), now);

        clicked.setType(Material.AIR);
        below.setType(Material.AIR);

        // consume one item from hand
        Util.consumeOne(p, item);

        p.sendMessage(ChatColor.translateAlternateColorCodes('&', "&aWind Turret &e" + id + " &aactivated."));
        // particle feedback
        org.bukkit.Location center = clicked.getLocation().add(0.5, 0.5, 0.5);
        for (int i = 0; i < 16; i++) {
            center.getWorld().spawnParticle(org.bukkit.Particle.CLOUD, center, 1, 0.3, 0.3, 0.3, 0.01);
        }

        ev.setCancelled(true);
    }
}
