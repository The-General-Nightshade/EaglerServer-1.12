package shadowlord.windturrets;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public class WindTurrets extends JavaPlugin implements Listener {

    private final Map<String, Turret> turrets = new HashMap<>();
    private TurretFireTask fireTask;
    private TurretGUI turretGUI;
    private ProtectionChecker protectionChecker;
    private MechManager mechManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        getConfig().set("crafting.enabled", null);
        List<String> configWarnings = validateConfig();
        for (String warning : configWarnings) getLogger().warning(warning);
        saveConfig();
        loadTurrets();
        registerCoreRecipe();

        getCommand("turret").setExecutor(new TurretCommand(this));
        getServer().getPluginManager().registerEvents(new TurretPlacementListener(this), this);
        protectionChecker = new ProtectionChecker(this);
        getServer().getPluginManager().registerEvents(this, this);
        mechManager = new MechManager(this);
        getServer().getPluginManager().registerEvents(mechManager, this);
        turretGUI = new TurretGUI(this);
        getServer().getPluginManager().registerEvents(turretGUI, this);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (turretGUI != null) turretGUI.refreshOpenInventories();
        }, 20L, 20L);

        // register wind charge listener (handles snowball hits)
        getServer().getPluginManager().registerEvents(new WindChargeListener(this), this);

        startFireTask();

        getLogger().info("WindTurrets enabled. Loaded " + turrets.size() + " turrets.");
    }

    private void registerCoreRecipe() {
        ShapedRecipe recipe = new ShapedRecipe(Util.createTurretCore(getConfig()));
        recipe.shape("DDD", "DND", "DDD");
        recipe.setIngredient('D', Material.DIAMOND_BLOCK);
        recipe.setIngredient('N', Material.NETHER_STAR);
        if (!Bukkit.addRecipe(recipe)) getLogger().warning("Could not register the Wind Turret Core recipe.");
    }

    private void startFireTask() {
        if (fireTask != null) fireTask.cancelAll();
        int interval = Math.max(1, getConfig().getInt("fire_interval_ticks", 10));
        int maxUpgradeLevel = Math.max(0, getConfig().getInt("upgrades.max_level", 5));
        int rateReduction = Math.max(0, getConfig().getInt("upgrades.fire_rate.ticks_reduction_per_level", 1));
        long minimumInterval = Math.max(1L, getConfig().getLong("upgrades.fire_rate.minimum_interval_ticks", 1L));
        String[] modeIntervalKeys = {"automatic_fire_interval_ticks", "semi_automatic_fire_interval_ticks", "single_fire_interval_ticks"};
        long[] fallbackIntervals = {2L, 10L, 10L};
        for (int index = 0; index < modeIntervalKeys.length; index++) {
            long configured = Math.max(1L, getConfig().getLong(modeIntervalKeys[index], fallbackIntervals[index]));
            long fastest = Math.max(minimumInterval, configured - (long) maxUpgradeLevel * rateReduction);
            interval = (int) Math.max(1L, Math.min(interval, fastest));
        }
        fireTask = new TurretFireTask(this);
        Bukkit.getScheduler().runTaskTimer(this, fireTask, interval, interval);
    }

    public List<String> reloadPluginConfig() {
        reloadConfig();
        getConfig().options().copyDefaults(true);
        getConfig().set("crafting.enabled", null);
        List<String> warnings = validateConfig();
        for (String warning : warnings) getLogger().warning(warning);
        loadTurrets();
        startFireTask();
        saveConfig();
        return warnings;
    }

    private List<String> validateConfig() {
        List<String> warnings = new ArrayList<>();
        validateInt("fire_interval_ticks", 10, 1, 1200, warnings);
        validateInt("automatic_fire_interval_ticks", 2, 1, 1200, warnings);
        validateInt("semi_automatic_fire_interval_ticks", 10, 1, 1200, warnings);
        validateInt("single_fire_interval_ticks", 10, 1, 1200, warnings);
        validateInt("magazine_size", 30, 1, 10000, warnings);
        validateInt("ammo_regen_seconds", 10, 0, 86400, warnings);
        validateInt("default_radius", 10, 1, 128, warnings);
        validateInt("max_targets_per_scan", 1, 1, 100, warnings);
        validateInt("limits.per_player", 5, 0, 10000, warnings);
        validateInt("limits.per_world", 50, 0, 100000, warnings);
        validateInt("limits.placement_cooldown_seconds", 3, 0, 86400, warnings);
        validateInt("upgrades.max_level", 5, 0, 100, warnings);
        validateInt("upgrades.range.cost", 8, 1, 1000000, warnings);
        validateInt("upgrades.range.blocks_per_level", 2, 1, 128, warnings);
        validateInt("upgrades.range.maximum_radius", 128, 1, 1024, warnings);
        validateInt("upgrades.magazine.cost", 8, 1, 1000000, warnings);
        validateInt("upgrades.magazine.ammo_per_level", 10, 0, 10000, warnings);
        validateInt("upgrades.fire_rate.cost", 8, 1, 1000000, warnings);
        validateInt("upgrades.fire_rate.ticks_reduction_per_level", 1, 0, 1200, warnings);
        validateInt("upgrades.fire_rate.minimum_interval_ticks", 1, 1, 1200, warnings);
        validateInt("upgrades.accuracy.cost", 12, 1, 1000000, warnings);

        validateBoolean("ammo_notifications_enabled", true, warnings);
        validateBoolean("target_creative", false, warnings);
        validateBoolean("target_spectator", false, warnings);
        validateBoolean("target_invisible", false, warnings);
        validateBoolean("respect_worldguard", true, warnings);
        validateBoolean("markers.enabled", true, warnings);
        validateBoolean("upgrades.enabled", true, warnings);
        validateInt("markers.interval_ticks", 40, 10, 1200, warnings);
        validateBoolean("mechs.enabled", true, warnings);
        validateInt("mechs.max_per_player", 1, 0, 100, warnings);
        validateInt("mechs.input_hold_timeout_ticks", 5, 2, 20, warnings);
        validateInt("mechs.machine_gun.cooldown_ticks", 4, 1, 1200, warnings);
        validateInt("mechs.rocket.cooldown_ticks", 30, 1, 1200, warnings);
        validateInt("mechs.upgrades.max_level", 5, 1, 100, warnings);
        validateInt("mechs.upgrades.armor.base_cost", 2, 1, 1000, warnings);
        validateInt("mechs.upgrades.engine.base_cost", 3, 1, 1000, warnings);
        validateInt("mechs.repair.cost", 8, 1, 10000, warnings);
        validateDouble("mechs.max_health", 120.0, 1.0, 100000.0, warnings);
        validateDouble("mechs.machine_gun.speed", 2.4, 0.1, 10.0, warnings);
        validateDouble("mechs.machine_gun.damage", 2.0, 0.0, 40.0, warnings);
        validateDouble("mechs.rocket.speed", 1.6, 0.1, 10.0, warnings);
        validateDouble("mechs.rocket.radius", 3.0, 1.0, 12.0, warnings);
        validateDouble("mechs.rocket.damage", 6.0, 0.0, 40.0, warnings);
        validateDouble("mechs.upgrades.armor.health_per_level", 40.0, 1.0, 10000.0, warnings);
        validateDouble("mechs.upgrades.engine.speed_per_level", 0.025, 0.0, 0.2, warnings);
        validateDouble("mechs.repair.health", 40.0, 1.0, 10000.0, warnings);

        String mode = getConfig().getString("default_fire_mode", "semi-automatic");
        if (Turret.FireMode.parse(mode) == null) {
            getConfig().set("default_fire_mode", "semi-automatic");
            warnings.add("Invalid default_fire_mode; reset to semi-automatic.");
        }
        String priority = getConfig().getString("default_target_priority", "nearest");
        if (Turret.TargetPriority.parse(priority) == null) {
            getConfig().set("default_target_priority", "nearest");
            warnings.add("Invalid default_target_priority; reset to nearest.");
        }

        validateMaterial("crafting.result_material", "QUARTZ_BLOCK", warnings);
        validateMaterial("upgrades.range.material", "IRON_INGOT", warnings);
        validateMaterial("upgrades.magazine.material", "GOLD_INGOT", warnings);
        validateMaterial("upgrades.fire_rate.material", "REDSTONE", warnings);
        validateMaterial("upgrades.accuracy.material", "DIAMOND", warnings);
        validateDouble("aiming.projectile_speed", 1.6, 0.1, 10.0, warnings);
        validateDouble("aiming.max_lead_seconds", 0.75, 0.0, 5.0, warnings);
        double baseSpread = validateDouble("aiming.base_spread_degrees", 8.0, 0.0, 45.0, warnings);
        validateDouble("aiming.spread_reduction_degrees_per_level", 1.5, 0.0, 45.0, warnings);
        validateDouble("aiming.minimum_spread_degrees", 0.5, 0.0, baseSpread, warnings);
        return warnings;
    }

    private int validateInt(String path, int defaultValue, int minimum, int maximum, List<String> warnings) {
        Object raw = getConfig().get(path);
        int value = raw instanceof Number ? ((Number) raw).intValue() : defaultValue;
        int validated = Math.max(minimum, Math.min(maximum, value));
        if (!(raw instanceof Number) || value != validated) {
            getConfig().set(path, validated);
            warnings.add("Invalid " + path + "; reset/clamped to " + validated + ".");
        }
        return validated;
    }

    private double validateDouble(String path, double defaultValue, double minimum, double maximum, List<String> warnings) {
        Object raw = getConfig().get(path);
        double value = raw instanceof Number ? ((Number) raw).doubleValue() : defaultValue;
        double validated = Double.isNaN(value) || Double.isInfinite(value)
                ? defaultValue : Math.max(minimum, Math.min(maximum, value));
        if (!(raw instanceof Number) || Double.compare(value, validated) != 0) {
            getConfig().set(path, validated);
            warnings.add("Invalid " + path + "; reset/clamped to " + validated + ".");
        }
        return validated;
    }

    private void validateBoolean(String path, boolean defaultValue, List<String> warnings) {
        Object raw = getConfig().get(path);
        if (!(raw instanceof Boolean)) {
            getConfig().set(path, defaultValue);
            warnings.add("Invalid " + path + "; reset to " + defaultValue + ".");
        }
    }

    private void validateMaterial(String path, String defaultValue, List<String> warnings) {
        String materialName = getConfig().getString(path, defaultValue);
        Material material = materialName == null ? null : Material.getMaterial(materialName.toUpperCase(Locale.ROOT));
        if (material == null || material == Material.AIR) {
            getConfig().set(path, defaultValue);
            warnings.add("Invalid " + path + "; reset to " + defaultValue + ".");
        }
    }

    @Override
    public void onDisable() {
        // persist
        if (mechManager != null) mechManager.shutdown();
        saveTurrets();
        if (fireTask != null) fireTask.cancelAll();
        getLogger().info("WindTurrets disabled.");
    }

    public Map<String, Turret> getTurrets() {
        return Collections.unmodifiableMap(turrets);
    }

    public TurretGUI getTurretGUI() {
        return turretGUI;
    }

    public MechManager getMechManager() {
        return mechManager;
    }

    public boolean allowsPvP(Location location) {
        return protectionChecker == null || protectionChecker.allowsPvP(location);
    }

    public Turret getTurret(String id) {
        return turrets.get(id);
    }

    public Turret getTurret(String owner, String id) {
        for (Turret turret : turrets.values()) {
            if (turret.getOwner().equalsIgnoreCase(owner) && turret.getId().equals(id)) return turret;
        }
        return null;
    }

    public Turret getTurret(UUID ownerUuid, String owner, String id) {
        for (Turret turret : turrets.values()) {
            if (turret.getId().equals(id) && turret.isOwnedBy(ownerUuid, owner)) return turret;
        }
        return null;
    }

    public String getNextTurretId(String owner) {
        return getNextTurretId(null, owner);
    }

    public String getNextTurretId(UUID ownerUuid, String owner) {
        return getNextTurretId(ownerUuid, owner, turrets.values());
    }

    private String getNextTurretId(UUID ownerUuid, String owner, Collection<Turret> existingTurrets) {
        Set<Integer> usedIds = new HashSet<>();
        for (Turret turret : existingTurrets) {
            if (!turret.isOwnedBy(ownerUuid, owner)) continue;
            try {
                usedIds.add(Integer.parseInt(turret.getId()));
            } catch (NumberFormatException ignored) {
                // Legacy IDs are migrated during load.
            }
        }
        int nextId = 1;
        while (usedIds.contains(nextId)) nextId++;
        return Integer.toString(nextId);
    }

    public Player getOwnerPlayer(Turret turret) {
        if (turret == null) return null;
        UUID ownerUuid = turret.getOwnerUuid();
        return ownerUuid == null ? Bukkit.getPlayerExact(turret.getOwner()) : Bukkit.getPlayer(ownerUuid);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        boolean changed = false;
        for (Turret turret : turrets.values()) changed |= turret.migrateIdentity(event.getPlayer());
        if (changed) {
            reindexTurrets();
            saveTurrets();
        }
    }

    private void reindexTurrets() {
        Map<String, Turret> reindexed = new HashMap<>();
        for (Turret turret : new ArrayList<>(turrets.values())) {
            String storageKey = turret.getStorageKey();
            if (reindexed.containsKey(storageKey)) {
                turret.setId(getNextTurretId(turret.getOwnerUuid(), turret.getOwner(), reindexed.values()));
                storageKey = turret.getStorageKey();
            }
            reindexed.put(storageKey, turret);
        }
        turrets.clear();
        turrets.putAll(reindexed);
    }

    public void addTurret(Turret t) {
        turrets.put(t.getStorageKey(), t);
        saveTurrets();
    }

    public Turret removeTurret(String storageKey) {
        Turret removed = turrets.remove(storageKey);
        saveTurrets();
        return removed;
    }

    public void resetFireState(Turret turret) {
        if (fireTask != null && turret != null) fireTask.resetTurret(turret.getStorageKey());
    }

    private void loadTurrets() {
        turrets.clear();
        FileConfiguration cfg = getConfig();
        if (!cfg.isConfigurationSection("turrets")) return;
        List<String> keys = new ArrayList<>(cfg.getConfigurationSection("turrets").getKeys(false));
        Collections.sort(keys);
        boolean migrated = false;
        int magazineSize = Math.max(1, cfg.getInt("magazine_size", 30));
        int ammoPerUpgrade = Math.max(0, cfg.getInt("upgrades.magazine.ammo_per_level", 10));
        String defaultFireMode = cfg.getString("default_fire_mode", "semi-automatic");
        String defaultTargetPriority = cfg.getString("default_target_priority", "nearest");
        for (String key : keys) {
            String path = "turrets." + key;
            try {
                    Turret t = Turret.fromConfig(key, cfg.getConfigurationSection(path), defaultFireMode,
                        defaultTargetPriority, magazineSize, ammoPerUpgrade);
                if (t == null) continue;
                if (!isPositiveInteger(t.getId()) || getTurret(t.getOwner(), t.getId()) != null) {
                    t.setId(getNextTurretId(t.getOwner()));
                    migrated = true;
                }
                String storageKey = t.getStorageKey();
                if (!key.equals(storageKey)) migrated = true;
                turrets.put(storageKey, t);
            } catch (Exception e) {
                getLogger().warning("Failed to load turret: " + key + " -> " + e.getMessage());
            }
        }
        if (migrated) saveTurrets();
    }

    private boolean isPositiveInteger(String value) {
        try {
            return Integer.parseInt(value) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public void saveTurrets() {
        FileConfiguration cfg = getConfig();
        cfg.set("turrets", null);
        for (Map.Entry<String, Turret> e : turrets.entrySet()) {
            String path = "turrets." + e.getValue().getStorageKey();
            e.getValue().toConfigSection(cfg.createSection(path));
        }
        saveConfig();
    }
}
