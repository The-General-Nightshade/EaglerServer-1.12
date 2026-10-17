package shadowlord.windturrets;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BlockIterator;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.stream.Collectors;

public class TurretFireTask extends BukkitRunnable {

    private final WindTurrets plugin;
    private final Map<String, Long> nextFireNanos = new HashMap<>();
    private final Map<String, Long> nextMarkerAtMillis = new HashMap<>();
    private final Map<String, Set<UUID>> singleShotTargets = new HashMap<>();

    public TurretFireTask(WindTurrets plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        Collection<Turret> all = plugin.getTurrets().values();
        if (all.isEmpty()) {
            nextFireNanos.clear();
            nextMarkerAtMillis.clear();
            singleShotTargets.clear();
            return;
        }

        boolean ammoChanged = false;
        boolean markersEnabled = plugin.getConfig().getBoolean("markers.enabled", true);
        long markerIntervalMillis = Math.max(10L, plugin.getConfig().getLong("markers.interval_ticks", 40L)) * 50L;
        long nowMillis = System.currentTimeMillis();
        int magazineSize = Math.max(1, plugin.getConfig().getInt("magazine_size", 30));
        int ammoPerUpgrade = Math.max(0, plugin.getConfig().getInt("upgrades.magazine.ammo_per_level", 10));
        long reloadDelayMillis = Math.max(0L, plugin.getConfig().getLong("ammo_regen_seconds", 10L)) * 1000L;
        for (Turret t : all) {
            String storageKey = t.getStorageKey();
            Location loc = t.getLocation();
            if (markersEnabled && loc != null && nowMillis >= nextMarkerAtMillis.getOrDefault(storageKey, 0L)) {
                loc.getWorld().spawnParticle(Particle.CLOUD, loc.clone().add(0.0, 1.5, 0.0), 1, 0.12, 0.2, 0.12, 0.0);
                nextMarkerAtMillis.put(storageKey, nowMillis + markerIntervalMillis);
            }
            int magazineCapacity = t.getMagazineCapacity(magazineSize, ammoPerUpgrade);
            if (t.regenerateAmmoIfReady(magazineCapacity)) {
                ammoChanged = true;
                nextFireNanos.remove(storageKey);
                singleShotTargets.remove(storageKey);
            }
            if (processAmmoNotices(t)) ammoChanged = true;
            if (!t.isEnabled()) continue;
            if (t.getAmmo() <= 0) continue;

            if (loc == null) continue;
            Turret.FireMode fireMode = t.getFireMode();
            long now = System.nanoTime();
            if (now < nextFireNanos.getOrDefault(storageKey, 0L)) continue;
            Location eye = loc.clone().add(0, 1.2, 0);
            List<Player> targets = loc.getWorld().getPlayers().stream()
                    .filter(pl -> !t.isOwnedBy(pl.getUniqueId(), pl.getName()))
                    .filter(pl -> !t.isTrusted(pl))
                    .filter(pl -> !pl.hasPermission("windturrets.bypass"))
                    .filter(pl -> plugin.getConfig().getBoolean("target_creative", false) || pl.getGameMode() != GameMode.CREATIVE)
                    .filter(pl -> plugin.getConfig().getBoolean("target_spectator", false) || pl.getGameMode() != GameMode.SPECTATOR)
                    .filter(pl -> plugin.getConfig().getBoolean("target_invisible", false) || !pl.hasPotionEffect(PotionEffectType.INVISIBILITY))
                    .filter(pl -> pl.getLocation().distance(loc) <= t.getRadius())
                    .filter(pl -> plugin.allowsPvP(pl.getLocation()))
                    .filter(pl -> hasLineOfSight(eye, pl.getEyeLocation()))
                    .collect(Collectors.toList());

            if (fireMode == Turret.FireMode.SINGLE) {
                Set<UUID> activeTargets = new HashSet<>();
                for (Player target : targets) activeTargets.add(target.getUniqueId());
                Set<UUID> firedTargets = singleShotTargets.computeIfAbsent(storageKey, key -> new HashSet<>());
                firedTargets.retainAll(activeTargets);
                targets.removeIf(target -> firedTargets.contains(target.getUniqueId()));
            }

            if (targets.isEmpty()) {
                if (fireMode == Turret.FireMode.SINGLE) nextFireNanos.remove(storageKey);
                continue;
            }

            int max = Math.max(1, plugin.getConfig().getInt("max_targets_per_scan", 1));
            if (t.getTargetPriority() == Turret.TargetPriority.RANDOM) {
                Collections.shuffle(targets);
            } else if (t.getTargetPriority() == Turret.TargetPriority.NEAREST) {
                targets.sort(Comparator.comparingDouble(target -> target.getLocation().distanceSquared(loc)));
            } else {
                targets.sort(Comparator.comparingDouble(Player::getHealth));
            }
            List<Player> chosen = targets.stream().limit(max).collect(Collectors.toList());

            int shotsFired = 0;
            for (Player target : chosen) {
                if (!t.consumeAmmo(reloadDelayMillis)) break;
                // Fire a snowball with velocity towards target (acts as wind charge)
                Snowball s = loc.getWorld().spawn(eye, Snowball.class);
                double projectileSpeed = Math.max(0.1, plugin.getConfig().getDouble("aiming.projectile_speed", 1.6));
                Vector direction = getAimDirection(eye, target, t, projectileSpeed);
                s.setVelocity(direction.multiply(projectileSpeed));
                // tag as wind charge so listener can identify it
                s.setMetadata("windcharge", new FixedMetadataValue(plugin, storageKey));
                // optional: set shooter if desired
                try { s.setShooter(null); } catch (Throwable ignored) {}
                shotsFired++;
                if (fireMode == Turret.FireMode.SINGLE) {
                    singleShotTargets.computeIfAbsent(storageKey, key -> new HashSet<>()).add(target.getUniqueId());
                }

                // particles along firing
                for (int i = 0; i < 6; i++) {
                    eye.getWorld().spawnParticle(org.bukkit.Particle.SPELL_WITCH, eye.clone().add(Math.random()-0.5, Math.random()-0.5, Math.random()-0.5), 1, 0,0,0, 0.01);
                }
            }

            if (shotsFired > 0) {
                nextFireNanos.put(storageKey, System.nanoTime() + getFireIntervalNanos(t, fireMode));
                if (t.getAmmo() == 0) ammoChanged = true;
            }
        }

        Set<String> activeTurrets = new HashSet<>();
        for (Turret turret : all) activeTurrets.add(turret.getStorageKey());
        nextFireNanos.keySet().retainAll(activeTurrets);
        nextMarkerAtMillis.keySet().retainAll(activeTurrets);
        singleShotTargets.keySet().retainAll(activeTurrets);
        if (ammoChanged) plugin.saveTurrets();
    }

    private Vector getAimDirection(Location origin, Player target, Turret turret, double projectileSpeed) {
        Location targetEye = target.getEyeLocation();
        double travelTicks = origin.distance(targetEye) / projectileSpeed;
        double maxLeadTicks = Math.max(0.0, plugin.getConfig().getDouble("aiming.max_lead_seconds", 0.75)) * 20.0;
        travelTicks = Math.min(travelTicks, maxLeadTicks);
        Vector predictedTarget = targetEye.toVector().add(target.getVelocity().clone().multiply(travelTicks));
        Vector direction = predictedTarget.subtract(origin.toVector()).normalize();

        double baseSpread = Math.max(0.0, plugin.getConfig().getDouble("aiming.base_spread_degrees", 8.0));
        double reductionPerLevel = Math.max(0.0,
                plugin.getConfig().getDouble("aiming.spread_reduction_degrees_per_level", 1.5));
        double minimumSpread = Math.max(0.0,
                plugin.getConfig().getDouble("aiming.minimum_spread_degrees", 0.5));
        double spreadDegrees = Math.max(minimumSpread,
                baseSpread - turret.getAccuracyUpgradeLevel() * reductionPerLevel);
        return applySpread(direction, spreadDegrees);
    }

    private Vector applySpread(Vector direction, double spreadDegrees) {
        if (spreadDegrees <= 0.0) return direction;
        Vector reference = Math.abs(direction.getY()) > 0.99 ? new Vector(1, 0, 0) : new Vector(0, 1, 0);
        Vector right = direction.clone().crossProduct(reference).normalize();
        Vector up = right.clone().crossProduct(direction).normalize();
        double angle = Math.random() * Math.PI * 2.0;
        double spread = Math.tan(Math.toRadians(spreadDegrees)) * Math.sqrt(Math.random());
        Vector offset = right.multiply(Math.cos(angle) * spread).add(up.multiply(Math.sin(angle) * spread));
        return direction.clone().add(offset).normalize();
    }

    private long getFireIntervalNanos(Turret turret, Turret.FireMode fireMode) {
        String key;
        long fallback;
        if (fireMode == Turret.FireMode.AUTOMATIC) {
            key = "automatic_fire_interval_ticks";
            fallback = 2L;
        } else if (fireMode == Turret.FireMode.SINGLE) {
            key = "single_fire_interval_ticks";
            fallback = 10L;
        } else {
            key = "semi_automatic_fire_interval_ticks";
            fallback = plugin.getConfig().getLong("fire_cooldown_ticks", 10L);
        }
        long configuredTicks = Math.max(1L, plugin.getConfig().getLong(key, fallback));
        long reduction = (long) turret.getFireRateUpgradeLevel()
                * Math.max(0, plugin.getConfig().getInt("upgrades.fire_rate.ticks_reduction_per_level", 1));
        long minimumTicks = Math.max(1L, plugin.getConfig().getLong("upgrades.fire_rate.minimum_interval_ticks", 1L));
        long ticks = Math.max(minimumTicks, configuredTicks - reduction);
        return ticks * 50_000_000L;
    }

    private boolean processAmmoNotices(Turret turret) {
        if (!plugin.getConfig().getBoolean("ammo_notifications_enabled", true)) {
            boolean changed = turret.isEmptyNoticePending() || turret.isReloadNoticePending();
            turret.clearEmptyNoticePending();
            turret.clearReloadNoticePending();
            return changed;
        }

        Player owner = plugin.getOwnerPlayer(turret);
        if (owner == null) return false;
        boolean changed = false;
        if (turret.isEmptyNoticePending()) {
            owner.sendMessage("Wind Turret " + turret.getId() + " is out of ammo. Reloading in "
                    + turret.getReloadSecondsRemaining() + " seconds.");
            turret.clearEmptyNoticePending();
            changed = true;
        }
        if (turret.isReloadNoticePending()) {
            owner.sendMessage("Wind Turret " + turret.getId() + " has reloaded ("
                    + turret.getAmmo() + " shots).");
            turret.clearReloadNoticePending();
            changed = true;
        }
        return changed;
    }

    public void resetTurret(String storageKey) {
        nextFireNanos.remove(storageKey);
        singleShotTargets.remove(storageKey);
    }

    private boolean hasLineOfSight(Location from, Location to) {
        Vector direction = to.toVector().subtract(from.toVector());
        double distance = direction.length();
        if (distance < 0.001) return true;

        BlockIterator blocks = new BlockIterator(from.getWorld(), from.toVector(), direction.normalize(), 0, (int) Math.ceil(distance));
        while (blocks.hasNext()) {
            Block block = blocks.next();
            if (block.getType().isSolid()) return false;
        }
        return true;
    }

    public void cancelAll() {
        this.cancel();
    }
}
