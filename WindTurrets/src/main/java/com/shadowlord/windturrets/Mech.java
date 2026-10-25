package shadowlord.windturrets;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.UUID;

public class Mech {

    private final String id;
    private final UUID ownerUuid;
    private String ownerName;
    private String worldName;
    private double x;
    private double y;
    private double z;
    private float yaw;
    private double health;
    private double maxHealth;
    private int armorLevel;
    private int engineLevel;
    private Weapon leftWeapon;
    private Weapon rightWeapon;

    public Mech(UUID id, Player owner, Location location, double maxHealth) {
        this.id = id.toString();
        this.ownerUuid = owner.getUniqueId();
        this.ownerName = owner.getName();
        this.maxHealth = Math.max(1.0, maxHealth);
        this.health = this.maxHealth;
        this.leftWeapon = Weapon.MACHINE_GUN;
        this.rightWeapon = Weapon.EMPTY;
        setLocation(location);
    }

    private Mech(String id, UUID ownerUuid, String ownerName, String worldName,
                 double x, double y, double z, float yaw, double health,
                 double maxHealth, int armorLevel, int engineLevel,
                 Weapon leftWeapon, Weapon rightWeapon) {
        this.id = id;
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName;
        this.worldName = worldName;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.maxHealth = Math.max(1.0, maxHealth);
        this.health = Math.max(0.0, Math.min(health, this.maxHealth));
        this.armorLevel = Math.max(0, armorLevel);
        this.engineLevel = Math.max(0, engineLevel);
        this.leftWeapon = leftWeapon == null ? Weapon.EMPTY : leftWeapon;
        this.rightWeapon = rightWeapon == null ? Weapon.EMPTY : rightWeapon;
    }

    public String getId() { return id; }
    public UUID getOwnerUuid() { return ownerUuid; }
    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
    public double getHealth() { return health; }
    public double getMaxHealth() { return maxHealth; }
    public int getArmorLevel() { return armorLevel; }
    public int getEngineLevel() { return engineLevel; }
    public Weapon getWeapon(Arm arm) { return arm == Arm.LEFT ? leftWeapon : rightWeapon; }

    public void setWeapon(Arm arm, Weapon weapon) {
        if (weapon == null) return;
        if (arm == Arm.LEFT) leftWeapon = weapon;
        else rightWeapon = weapon;
    }

    public boolean isOwner(Player player) {
        return player != null && ownerUuid.equals(player.getUniqueId());
    }

    public Location getLocation() {
        org.bukkit.World world = Bukkit.getWorld(worldName);
        return world == null ? null : new Location(world, x, y, z, yaw, 0.0f);
    }

    public String getWorldName() { return worldName; }
    public int getChunkX() { return ((int) Math.floor(x)) >> 4; }
    public int getChunkZ() { return ((int) Math.floor(z)) >> 4; }

    public void setLocation(Location location) {
        if (location == null || location.getWorld() == null) return;
        worldName = location.getWorld().getName();
        x = location.getX();
        y = location.getY();
        z = location.getZ();
        yaw = location.getYaw();
    }

    public void applyDamage(double damage) {
        health = Math.max(0.0, health - Math.max(0.0, damage));
    }

    public boolean isDestroyed() { return health <= 0.0; }

    public void upgradeArmor(double healthIncrease) {
        double increase = Math.max(1.0, healthIncrease);
        maxHealth += increase;
        health = Math.min(maxHealth, health + increase);
        armorLevel++;
    }

    public void upgradeEngine() { engineLevel++; }

    public void repair(double amount) {
        health = Math.min(maxHealth, health + Math.max(0.0, amount));
    }

    public void toConfigSection(ConfigurationSection section) {
        section.set("owner_uuid", ownerUuid.toString());
        section.set("owner_name", ownerName);
        section.set("world", worldName);
        section.set("x", x);
        section.set("y", y);
        section.set("z", z);
        section.set("yaw", yaw);
        section.set("health", health);
        section.set("max_health", maxHealth);
        section.set("armor_level", armorLevel);
        section.set("engine_level", engineLevel);
        section.set("left_weapon", leftWeapon.getConfigName());
        section.set("right_weapon", rightWeapon.getConfigName());
    }

    public static Mech fromConfig(String id, ConfigurationSection section, double defaultHealth) {
        if (section == null) return null;
        try {
            UUID ownerUuid = UUID.fromString(section.getString("owner_uuid", ""));
            String ownerName = section.getString("owner_name", "unknown");
            String world = section.getString("world", null);
            if (world == null || world.trim().isEmpty()) return null;
            double maxHealth = Math.max(1.0, section.getDouble("max_health", defaultHealth));
            return new Mech(id, ownerUuid, ownerName, world,
                    section.getDouble("x"), section.getDouble("y"), section.getDouble("z"),
                    (float) section.getDouble("yaw", 0.0),
                    section.getDouble("health", maxHealth), maxHealth,
                    section.getInt("armor_level", 0), section.getInt("engine_level", 0),
                    Weapon.parse(section.getString("left_weapon", "machine_gun")),
                    Weapon.parse(section.getString("right_weapon", "empty")));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public enum Arm { LEFT, RIGHT }

    public enum Weapon {
        EMPTY("empty"),
        MACHINE_GUN("machine_gun"),
        ROCKET_LAUNCHER("rocket_launcher");

        private final String configName;

        Weapon(String configName) { this.configName = configName; }
        public String getConfigName() { return configName; }

        public static Weapon parse(String value) {
            if (value == null) return EMPTY;
            String normalized = value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
            for (Weapon weapon : values()) {
                if (weapon.configName.equals(normalized)) return weapon;
            }
            return EMPTY;
        }
    }
}