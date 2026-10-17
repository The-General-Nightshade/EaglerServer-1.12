package shadowlord.windturrets;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.Locale;

public class Turret {

    private String id;                 // numeric id scoped to owner
    private String owner;              // last known player name
    private String ownerUuid;
    private final String worldName;
    private final int x, y, z;         // turret base location (quartz pillar block)
    private int radius;
    private boolean enabled;
    private final Set<String> trustedPlayers = new HashSet<>();
    private final Set<String> trustedUuids = new HashSet<>();
    private final Map<String, String> trustedUuidByName = new HashMap<>();
    private FireMode fireMode;
    private TargetPriority targetPriority;
    private int ammo;
    private long reloadAtMillis;
    private boolean emptyNoticePending;
    private boolean reloadNoticePending;
    private int rangeUpgradeLevel;
    private int magazineUpgradeLevel;
    private int fireRateUpgradeLevel;
    private int accuracyUpgradeLevel;

    public Turret(String id, String owner, Location loc, int radius, boolean enabled) {
        this(id, owner, loc, radius, enabled, FireMode.SEMI_AUTOMATIC, 30);
    }

    public Turret(String id, String owner, Location loc, int radius, boolean enabled, FireMode fireMode, int magazineSize) {
        this(id, owner, null, loc, radius, enabled, fireMode, magazineSize, TargetPriority.NEAREST);
    }

    public Turret(String id, String owner, UUID ownerUuid, Location loc, int radius, boolean enabled,
                  FireMode fireMode, int magazineSize, TargetPriority targetPriority) {
        this(id, owner, ownerUuid, loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
            radius, enabled, fireMode, magazineSize, targetPriority);
        }

        private Turret(String id, String owner, UUID ownerUuid, String worldName, int x, int y, int z,
               int radius, boolean enabled, FireMode fireMode, int magazineSize, TargetPriority targetPriority) {
        this.id = id;
        this.owner = owner;
        this.ownerUuid = ownerUuid == null ? null : ownerUuid.toString();
        this.worldName = worldName;
        this.x = x;
        this.y = y;
        this.z = z;
        this.radius = radius;
        this.enabled = enabled;
        this.fireMode = fireMode == null ? FireMode.SEMI_AUTOMATIC : fireMode;
        this.targetPriority = targetPriority == null ? TargetPriority.NEAREST : targetPriority;
        this.ammo = Math.max(1, magazineSize);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getOwner() { return owner; }
    public String getWorldName() { return worldName; }
    public UUID getOwnerUuid() {
        try { return ownerUuid == null ? null : UUID.fromString(ownerUuid); }
        catch (IllegalArgumentException ignored) { return null; }
    }
    public boolean isOwnedBy(UUID uuid, String playerName) {
        UUID storedUuid = getOwnerUuid();
        if (storedUuid != null) return storedUuid.equals(uuid);
        return playerName != null && owner.equalsIgnoreCase(playerName);
    }
    public boolean migrateIdentity(Player player) {
        boolean changed = false;
        String playerUuid = player.getUniqueId().toString();
        if (ownerUuid == null && owner.equalsIgnoreCase(player.getName())) {
            ownerUuid = playerUuid;
            changed = true;
        } else if (playerUuid.equals(ownerUuid) && !owner.equals(player.getName())) {
            owner = player.getName();
            changed = true;
        }
        String currentName = player.getName().toLowerCase(Locale.ROOT);
        Iterator<Map.Entry<String, String>> trustedEntries = trustedUuidByName.entrySet().iterator();
        while (trustedEntries.hasNext()) {
            Map.Entry<String, String> entry = trustedEntries.next();
            if (entry.getValue().equals(playerUuid) && !entry.getKey().equals(currentName)) {
                trustedPlayers.remove(entry.getKey());
                trustedPlayers.add(currentName);
                trustedEntries.remove();
                changed = true;
            }
        }
        if (trustedPlayers.contains(currentName)) {
            changed |= trustedUuids.add(playerUuid);
            String previousUuid = trustedUuidByName.put(currentName, playerUuid);
            changed |= !playerUuid.equals(previousUuid);
        }
        return changed;
    }
    public String getStorageKey() { return (ownerUuid == null ? owner.toLowerCase(Locale.ROOT) : ownerUuid) + ":" + id; }
    public int getRadius() { return radius; }
    public void setRadius(int r) { this.radius = r; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public FireMode getFireMode() { return fireMode; }
    public void setFireMode(FireMode fireMode) {
        if (fireMode != null) this.fireMode = fireMode;
    }
    public TargetPriority getTargetPriority() { return targetPriority; }
    public void setTargetPriority(TargetPriority priority) {
        if (priority != null) targetPriority = priority;
    }
    public int getAmmo() { return ammo; }
    public int getMagazineUpgradeLevel() { return magazineUpgradeLevel; }
    public int getRangeUpgradeLevel() { return rangeUpgradeLevel; }
    public int getFireRateUpgradeLevel() { return fireRateUpgradeLevel; }
    public int getAccuracyUpgradeLevel() { return accuracyUpgradeLevel; }
    public int getMagazineCapacity(int baseCapacity, int ammoPerUpgrade) {
        return Math.max(1, baseCapacity + magazineUpgradeLevel * Math.max(0, ammoPerUpgrade));
    }
    public int getReloadSecondsRemaining() {
        if (ammo > 0 || reloadAtMillis <= 0) return 0;
        return (int) Math.max(0L, (reloadAtMillis - System.currentTimeMillis() + 999L) / 1000L);
    }
    public boolean isEmptyNoticePending() { return emptyNoticePending; }
    public boolean isReloadNoticePending() { return reloadNoticePending; }
    public void clearEmptyNoticePending() { emptyNoticePending = false; }
    public void clearReloadNoticePending() { reloadNoticePending = false; }
    public long getReloadAtMillis() { return reloadAtMillis; }
    public boolean consumeAmmo(long reloadDelayMillis) {
        if (ammo <= 0) return false;
        ammo--;
        if (ammo == 0) {
            reloadAtMillis = System.currentTimeMillis() + Math.max(0L, reloadDelayMillis);
            emptyNoticePending = true;
            reloadNoticePending = false;
        }
        return true;
    }
    public boolean regenerateAmmoIfReady(int magazineSize) {
        if (ammo > 0 || reloadAtMillis <= 0 || System.currentTimeMillis() < reloadAtMillis) return false;
        ammo = Math.max(1, magazineSize);
        reloadAtMillis = 0;
        emptyNoticePending = false;
        reloadNoticePending = true;
        return true;
    }
    public void upgradeRange(int radiusIncrease) {
        radius = Math.max(1, radius + Math.max(0, radiusIncrease));
        rangeUpgradeLevel++;
    }
    public void upgradeMagazine(int ammoIncrease) {
        magazineUpgradeLevel++;
        if (ammo > 0) ammo += Math.max(0, ammoIncrease);
    }
    public void upgradeFireRate() { fireRateUpgradeLevel++; }
    public void upgradeAccuracy() { accuracyUpgradeLevel++; }
    public boolean trustPlayer(Player player) {
        if (player == null) return false;
        boolean changed = trustPlayer(player.getName());
        String playerUuid = player.getUniqueId().toString();
        changed |= trustedUuids.add(playerUuid);
        String previousUuid = trustedUuidByName.put(player.getName().toLowerCase(Locale.ROOT), playerUuid);
        changed |= !playerUuid.equals(previousUuid);
        return changed;
    }
    public boolean isTrusted(Player player) {
        return player != null && (trustedUuids.contains(player.getUniqueId().toString()) || isTrusted(player.getName()));
    }
    private void restoreAmmo(int storedAmmo, long storedReloadAtMillis, int magazineSize) {
        int capacity = Math.max(1, magazineSize);
        ammo = Math.max(0, Math.min(storedAmmo, capacity));
        reloadAtMillis = ammo == 0 ? storedReloadAtMillis : 0;
        if (ammo == 0 && reloadAtMillis <= 0) ammo = capacity;
        emptyNoticePending = ammo == 0;
    }
    public boolean isTrusted(String playerName) {
        return playerName != null && trustedPlayers.contains(playerName.toLowerCase(Locale.ROOT));
    }
    public Set<String> getTrustedPlayers() { return Collections.unmodifiableSet(trustedPlayers); }
    public boolean trustPlayer(String playerName) {
        return playerName != null && !playerName.trim().isEmpty()
                && trustedPlayers.add(playerName.toLowerCase(Locale.ROOT));
    }
    public boolean untrustPlayer(String playerName) {
        if (playerName == null) return false;
        String normalized = playerName.toLowerCase(Locale.ROOT);
        boolean changed = trustedPlayers.remove(normalized);
        String uuid = trustedUuidByName.remove(normalized);
        if (uuid != null && !trustedUuidByName.containsValue(uuid)) changed |= trustedUuids.remove(uuid);
        Player online = org.bukkit.Bukkit.getPlayerExact(playerName);
        if (online != null) changed |= trustedUuids.remove(online.getUniqueId().toString());
        return changed;
    }
    public boolean untrustPlayer(Player player) {
        if (player == null) return false;
        return untrustPlayer(player.getName());
    }

    public Location getLocation() {
        return org.bukkit.Bukkit.getWorld(worldName) == null ? null :
                new Location(org.bukkit.Bukkit.getWorld(worldName), x + 0.5, y + 0.5, z + 0.5);
    }

    public boolean isAt(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        if (!loc.getWorld().getName().equals(worldName)) return false;
        return loc.getBlockX() == x && loc.getBlockY() == y && loc.getBlockZ() == z;
    }

    public void toConfigSection(ConfigurationSection sec) {
        sec.set("id", id);
        sec.set("owner", owner);
        sec.set("owner_uuid", ownerUuid);
        sec.set("world", worldName);
        sec.set("x", x);
        sec.set("y", y);
        sec.set("z", z);
        sec.set("radius", radius);
        sec.set("enabled", enabled);
        sec.set("trusted_players", new ArrayList<>(new TreeSet<>(trustedPlayers)));
        sec.set("trusted_uuids", new ArrayList<>(new TreeSet<>(trustedUuids)));
        sec.set("trusted_player_uuids", new TreeMap<>(trustedUuidByName));
        sec.set("fire_mode", fireMode.getConfigName());
        sec.set("target_priority", targetPriority.getConfigName());
        sec.set("ammo", ammo);
        sec.set("reload_at", reloadAtMillis);
        sec.set("empty_notice_pending", emptyNoticePending);
        sec.set("reload_notice_pending", reloadNoticePending);
        sec.set("range_upgrade_level", rangeUpgradeLevel);
        sec.set("magazine_upgrade_level", magazineUpgradeLevel);
        sec.set("fire_rate_upgrade_level", fireRateUpgradeLevel);
        sec.set("accuracy_upgrade_level", accuracyUpgradeLevel);
    }

    public static Turret fromConfig(String id, ConfigurationSection sec) {
        return fromConfig(id, sec, "semi-automatic", 30);
    }

    public static Turret fromConfig(String id, ConfigurationSection sec, String defaultFireMode, int magazineSize) {
        return fromConfig(id, sec, defaultFireMode, "nearest", magazineSize);
    }

    public static Turret fromConfig(String id, ConfigurationSection sec, String defaultFireMode,
                                    String defaultTargetPriority, int magazineSize) {
        return fromConfig(id, sec, defaultFireMode, defaultTargetPriority, magazineSize, 0);
    }

    public static Turret fromConfig(String id, ConfigurationSection sec, String defaultFireMode,
                                    String defaultTargetPriority, int magazineSize, int ammoPerUpgrade) {
        if (sec == null) return null;
        id = sec.getString("id", id);
        String owner = sec.getString("owner", "unknown");
        String world = sec.getString("world", null);
        if (world == null) return null;
        int x = sec.getInt("x"), y = sec.getInt("y"), z = sec.getInt("z");
        int radius = sec.getInt("radius", 10);
        boolean enabled = sec.getBoolean("enabled", true);
        FireMode fireMode = FireMode.parse(sec.getString("fire_mode", defaultFireMode));
        if (fireMode == null) fireMode = FireMode.parse(defaultFireMode);
        if (fireMode == null) fireMode = FireMode.SEMI_AUTOMATIC;
        TargetPriority priority = TargetPriority.parse(sec.getString("target_priority", defaultTargetPriority));
        if (priority == null) priority = TargetPriority.parse(defaultTargetPriority);
        if (priority == null) priority = TargetPriority.NEAREST;
        UUID ownerUuid = null;
        try {
            String storedUuid = sec.getString("owner_uuid");
            if (storedUuid != null) ownerUuid = UUID.fromString(storedUuid);
        } catch (IllegalArgumentException ignored) {}
        Turret turret = new Turret(id, owner, ownerUuid, world, x, y, z,
            radius, enabled, fireMode, magazineSize, priority);
        turret.rangeUpgradeLevel = Math.max(0, sec.getInt("range_upgrade_level", 0));
        turret.magazineUpgradeLevel = Math.max(0, sec.getInt("magazine_upgrade_level", 0));
        turret.fireRateUpgradeLevel = Math.max(0, sec.getInt("fire_rate_upgrade_level", 0));
        turret.accuracyUpgradeLevel = Math.max(0, sec.getInt("accuracy_upgrade_level", 0));
        if (sec.contains("ammo")) {
            int ammoCapacity = turret.getMagazineCapacity(magazineSize, ammoPerUpgrade);
            turret.restoreAmmo(sec.getInt("ammo"), sec.getLong("reload_at", 0L), ammoCapacity);
            turret.emptyNoticePending = sec.getBoolean("empty_notice_pending", turret.emptyNoticePending);
            turret.reloadNoticePending = sec.getBoolean("reload_notice_pending", false);
        }
        for (String playerName : sec.getStringList("trusted_players")) {
            turret.trustPlayer(playerName);
        }
        turret.trustedUuids.addAll(sec.getStringList("trusted_uuids"));
        ConfigurationSection trustedUuidSection = sec.getConfigurationSection("trusted_player_uuids");
        if (trustedUuidSection != null) {
            for (String playerName : trustedUuidSection.getKeys(false)) {
                String trustedUuid = trustedUuidSection.getString(playerName);
                try {
                    UUID.fromString(trustedUuid);
                    turret.trustedUuidByName.put(playerName.toLowerCase(Locale.ROOT), trustedUuid);
                    turret.trustedUuids.add(trustedUuid);
                } catch (IllegalArgumentException | NullPointerException ignored) {}
            }
        }
        return turret;
    }

    public enum FireMode {
        AUTOMATIC("automatic"),
        SEMI_AUTOMATIC("semi-automatic"),
        SINGLE("single");

        private final String configName;

        FireMode(String configName) {
            this.configName = configName;
        }

        public String getConfigName() { return configName; }

        public static FireMode parse(String value) {
            if (value == null) return null;
            String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
            if (normalized.equals("automatic")) return AUTOMATIC;
            if (normalized.equals("semi") || normalized.equals("semiautomatic") || normalized.equals("semi-automatic")) return SEMI_AUTOMATIC;
            if (normalized.equals("single")) return SINGLE;
            return null;
        }
    }

    public enum TargetPriority {
        NEAREST("nearest"),
        WEAKEST("weakest"),
        RANDOM("random");

        private final String configName;

        TargetPriority(String configName) { this.configName = configName; }
        public String getConfigName() { return configName; }

        public static TargetPriority parse(String value) {
            if (value == null) return null;
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (TargetPriority priority : values()) {
                if (priority.configName.equals(normalized)) return priority;
            }
            return null;
        }
    }
}
