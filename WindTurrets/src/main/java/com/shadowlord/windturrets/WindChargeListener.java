package shadowlord.windturrets;

import org.bukkit.Particle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.util.Vector;

import java.util.List;

public class WindChargeListener implements Listener {

    private final WindTurrets plugin;

    public WindChargeListener(WindTurrets plugin) {
        this.plugin = plugin;
    }

    // Handle direct hits on entities (players)
    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent ev) {
        if (!(ev.getDamager() instanceof Snowball)) return;
        Snowball s = (Snowball) ev.getDamager();

        if (!s.hasMetadata("windcharge")) return;

        // Only care when a player is hit
        if (!(ev.getEntity() instanceof Player)) return;
        Player victim = (Player) ev.getEntity();

        if (!plugin.allowsPvP(victim.getLocation())) {
            ev.setCancelled(true);
            s.remove();
            return;
        }

        // cancel any damage (wind charge only knocks players)
        ev.setCancelled(true);

        // compute knockback: push away from snowball impact location, with upward lift
        Vector dir = victim.getLocation().toVector().subtract(s.getLocation().toVector()).normalize();
        // tune these multipliers for feel
        double horizontal = 1.2;
        double vertical = 0.6;
        Vector knock = new Vector(dir.getX() * horizontal, vertical, dir.getZ() * horizontal);
        victim.setVelocity(knock);

        // particles burst at victim
        victim.getWorld().spawnParticle(Particle.SPELL_WITCH, victim.getLocation().add(0, 1, 0), 20, 0.5, 0.5, 0.5, 0.02);

        // remove the projectile
        s.remove();
    }

    // Handle hits against blocks (visual burst)
    @EventHandler
    public void onProjectileHit(ProjectileHitEvent ev) {
        Entity proj = ev.getEntity();
        if (!(proj instanceof Snowball)) return;
        Snowball s = (Snowball) proj;
        if (!s.hasMetadata("windcharge")) return;

        // spawn particles at hit location
        if (s.getWorld() != null) {
            s.getWorld().spawnParticle(Particle.SPELL_WITCH, s.getLocation(), 30, 0.6, 0.6, 0.6, 0.02);
        }

        // cleanup
        s.remove();
    }
}
