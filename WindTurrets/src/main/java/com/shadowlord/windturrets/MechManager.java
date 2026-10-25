package shadowlord.windturrets;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class MechManager implements Listener {

    private static final String CORE_MARKER = "windturrets:mech_core:v1";
    private static final String MACHINE_GUN_MARKER = "windturrets:mech_machine_gun:v1";
    private static final String ROCKET_MARKER = "windturrets:mech_rocket:v1";
    private static final int MAX_UPGRADE_LEVEL = 5;

    private final WindTurrets plugin;
    private final File dataFile;
    private final Map<String, Mech> mechs = new HashMap<>();
    private final Map<String, MechRuntime> runtime = new HashMap<>();
    private final Map<UUID, String> entityToMech = new HashMap<>();
    private final Map<UUID, ProjectileShot> projectiles = new HashMap<>();
    private final Map<String, Long> nextShotAt = new HashMap<>();
    private final Map<String, FireInput> fireInputs = new HashMap<>();
    private BukkitTask tickTask;
    private long ticks;

    public MechManager(WindTurrets plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "mechs.yml");
        loadMechs();
        registerRecipes();
        spawnInLoadedChunks();
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void handleCommand(Player player, String[] args) {
        if (args.length == 2 && args[1].equalsIgnoreCase("give")) {
            if (!player.hasPermission("windturrets.admin")) {
                player.sendMessage(color("&cOnly admins can issue mech cores."));
                return;
            }
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(createMechCore());
            for (ItemStack item : leftovers.values()) player.getWorld().dropItemNaturally(player.getLocation(), item);
            player.sendMessage(color("&aGiven a Mech Core."));
            return;
        }
        player.sendMessage(color("&6Mech controls:"));
        player.sendMessage(color("&eCraft a Mech Core, then right-click to deploy and mount it."));
        player.sendMessage(color("&eHold left-click to fire the left arm; hold right-click to fire the right arm."));
        player.sendMessage(color("&eMech weapons do not require ammo; each shot has a configurable reload delay."));
        player.sendMessage(color("&eCrouch: exit. Shift-right-click the mech: configure weapons and upgrades."));
        player.sendMessage(color("&e/turret mech give &7- issue a core (admin only)"));
    }

    public void shutdown() {
        if (tickTask != null) tickTask.cancel();
        for (Map.Entry<String, MechRuntime> entry : new ArrayList<>(runtime.entrySet())) {
            Mech mech = mechs.get(entry.getKey());
            if (mech != null && entry.getValue().vehicle.isValid()) mech.setLocation(entry.getValue().vehicle.getLocation());
            despawnRuntime(entry.getKey());
        }
        saveMechs();
    }

    private void registerRecipes() {
        ShapedRecipe core = new ShapedRecipe(createMechCore());
        core.shape("IDI", "DND", "IDI");
        core.setIngredient('I', Material.IRON_BLOCK);
        core.setIngredient('D', Material.DIAMOND_BLOCK);
        core.setIngredient('N', Material.NETHER_STAR);
        if (!Bukkit.addRecipe(core)) plugin.getLogger().warning("Could not register the Mech Core recipe.");

        ShapedRecipe machineGun = new ShapedRecipe(createWeaponModule(Mech.Weapon.MACHINE_GUN));
        machineGun.shape("IRI", "I I", "III");
        machineGun.setIngredient('I', Material.IRON_INGOT);
        machineGun.setIngredient('R', Material.REDSTONE);
        if (!Bukkit.addRecipe(machineGun)) plugin.getLogger().warning("Could not register the mech machine-gun module recipe.");

        ShapedRecipe rocket = new ShapedRecipe(createWeaponModule(Mech.Weapon.ROCKET_LAUNCHER));
        rocket.shape("TFT", " I ", "TFT");
        rocket.setIngredient('T', Material.TNT);
        rocket.setIngredient('F', Material.FIREBALL);
        rocket.setIngredient('I', Material.IRON_INGOT);
        if (!Bukkit.addRecipe(rocket)) plugin.getLogger().warning("Could not register the mech rocket module recipe.");
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        String riddenMech = findRiddenMech(player);
        if (riddenMech != null) {
            if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                event.setCancelled(true);
                registerFireInput(player, riddenMech, Mech.Arm.RIGHT);
            }
            return;
        }

        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!isMechCore(event.getItem())) return;
        event.setCancelled(true);
        deploy(player, event.getItem());
    }

    @EventHandler
    public void onPlayerSwing(PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        String mechId = findRiddenMech(event.getPlayer());
        if (mechId != null) registerFireInput(event.getPlayer(), mechId, Mech.Arm.LEFT);
    }

    @EventHandler
    public void onMechInteract(PlayerInteractAtEntityEvent event) {
        String mechId = entityToMech.get(event.getRightClicked().getUniqueId());
        if (mechId == null) return;
        event.setCancelled(true);
        Mech mech = mechs.get(mechId);
        if (!canControl(event.getPlayer(), mech)) {
            event.getPlayer().sendMessage(color("&cThis mech belongs to another player."));
            return;
        }
        if (event.getPlayer().isSneaking()) {
            openMechMenu(event.getPlayer(), mech);
            return;
        }
        MechRuntime mechRuntime = runtime.get(mechId);
        if (mechRuntime == null) return;
        Entity passenger = mechRuntime.vehicle.getPassenger();
        if (passenger == null) {
            mechRuntime.vehicle.addPassenger(event.getPlayer());
            event.getPlayer().sendMessage(color("&aMounted mech. Crouch to exit."));
        } else if (!passenger.getUniqueId().equals(event.getPlayer().getUniqueId())) {
            event.getPlayer().sendMessage(color("&cThis mech is already occupied."));
        }
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) return;
        String mechId = findRiddenMech(event.getPlayer());
        if (mechId == null) return;
        MechRuntime mechRuntime = runtime.get(mechId);
        Mech mech = mechs.get(mechId);
        if (mechRuntime != null) {
            clearFireInputs(event.getPlayer());
            mech.setLocation(mechRuntime.vehicle.getLocation());
            mechRuntime.vehicle.removePassenger(event.getPlayer());
            saveMechs();
            event.getPlayer().sendMessage(color("&eExited mech."));
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        String mechId = findRiddenMech(event.getPlayer());
        if (mechId == null) return;
        MechRuntime mechRuntime = runtime.get(mechId);
        Mech mech = mechs.get(mechId);
        if (mechRuntime != null) {
            clearFireInputs(event.getPlayer());
            mech.setLocation(mechRuntime.vehicle.getLocation());
            mechRuntime.vehicle.removePassenger(event.getPlayer());
            saveMechs();
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        boolean changed = false;
        for (Mech mech : mechs.values()) {
            if (mech.getOwnerUuid().equals(event.getPlayer().getUniqueId())
                    && !mech.getOwnerName().equals(event.getPlayer().getName())) {
                mech.setOwnerName(event.getPlayer().getName());
                changed = true;
            }
        }
        if (changed) saveMechs();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMechDamage(EntityDamageEvent event) {
        String mechId = entityToMech.get(event.getEntity().getUniqueId());
        if (mechId == null) return;
        Mech mech = mechs.get(mechId);
        if (mech == null) return;
        event.setCancelled(true);
        double damage = Math.max(1.0, event.getFinalDamage());
        mech.applyDamage(damage);
        Player rider = getRider(mechId);
        if (rider != null) rider.sendMessage(color("&cMech durability: " + Math.ceil(mech.getHealth()) + "/" + Math.ceil(mech.getMaxHealth())));
        if (mech.isDestroyed()) destroyMech(mechId);
        else saveMechs();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onWeaponDamage(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Snowball)) return;
        ProjectileShot shot = projectiles.get(event.getDamager().getUniqueId());
        if (shot == null) return;
        if (!(event.getEntity() instanceof Player)) {
            event.setCancelled(true);
            return;
        }
        Player victim = (Player) event.getEntity();
        if (victim.getUniqueId().equals(shot.shooterUuid) || !plugin.allowsPvP(victim.getLocation())) {
            event.setCancelled(true);
            return;
        }
        if (shot.weapon == Mech.Weapon.ROCKET_LAUNCHER) {
            event.setCancelled(true);
        } else {
            event.setDamage(Math.max(0.0, plugin.getConfig().getDouble("mechs.machine_gun.damage", 2.0)));
        }
    }

    @EventHandler
    public void onWeaponHit(ProjectileHitEvent event) {
        ProjectileShot shot = projectiles.remove(event.getEntity().getUniqueId());
        if (shot == null) return;
        if (shot.weapon == Mech.Weapon.ROCKET_LAUNCHER) explodeRocket(event.getEntity().getLocation(), shot);
        event.getEntity().remove();
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        for (Mech mech : mechs.values()) {
            if (mech.getWorldName().equals(event.getWorld().getName())
                    && mech.getChunkX() == event.getChunk().getX()
                    && mech.getChunkZ() == event.getChunk().getZ()) spawnMech(mech);
        }
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        for (Map.Entry<String, MechRuntime> entry : new ArrayList<>(runtime.entrySet())) {
            Mech mech = mechs.get(entry.getKey());
            if (mech == null || !mech.getWorldName().equals(event.getWorld().getName())) continue;
            Location location = entry.getValue().vehicle.getLocation();
            if ((location.getBlockX() >> 4) != event.getChunk().getX()
                    || (location.getBlockZ() >> 4) != event.getChunk().getZ()) continue;
            mech.setLocation(location);
            despawnRuntime(entry.getKey());
            saveMechs();
        }
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        for (Mech mech : mechs.values()) {
            if (mech.getWorldName().equals(event.getWorld().getName())) spawnMech(mech);
        }
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        for (Map.Entry<String, MechRuntime> entry : new ArrayList<>(runtime.entrySet())) {
            Mech mech = mechs.get(entry.getKey());
            if (mech == null || !mech.getWorldName().equals(event.getWorld().getName())) continue;
            if (entry.getValue().vehicle.isValid()) mech.setLocation(entry.getValue().vehicle.getLocation());
            despawnRuntime(entry.getKey());
        }
        saveMechs();
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof MechMenuHolder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player) || event.getRawSlot() < 0 || event.getRawSlot() >= top.getSize()) return;
        Player player = (Player) event.getWhoClicked();
        MechMenuHolder holder = (MechMenuHolder) top.getHolder();
        Mech mech = mechs.get(holder.mechId);
        if (!canControl(player, mech)) {
            player.closeInventory();
            return;
        }
        if (holder.view == MenuView.MAIN) handleMainMenu(player, mech, event.getRawSlot());
        else if (holder.view == MenuView.WEAPON) handleWeaponMenu(player, mech, holder.arm, event.getRawSlot());
        else handleUpgradeMenu(player, mech, event.getRawSlot());
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MechMenuHolder) event.setCancelled(true);
    }

    private void deploy(Player player, ItemStack core) {
        if (!plugin.getConfig().getBoolean("mechs.enabled", true)) {
            player.sendMessage(color("&cMechs are disabled on this server."));
            return;
        }
        int limit = Math.max(0, plugin.getConfig().getInt("mechs.max_per_player", 1));
        int owned = 0;
        for (Mech mech : mechs.values()) if (mech.isOwner(player)) owned++;
        if (limit > 0 && owned >= limit) {
            player.sendMessage(color("&cYou have reached the mech limit of " + limit + "."));
            return;
        }
        Location location = player.getLocation().clone();
        if (!hasRoom(location)) {
            player.sendMessage(color("&cThere is not enough room to deploy the mech."));
            return;
        }
        double health = Math.max(1.0, plugin.getConfig().getDouble("mechs.max_health", 120.0));
        Mech mech = new Mech(UUID.randomUUID(), player, location, health);
        mechs.put(mech.getId(), mech);
        if (!spawnMech(mech)) {
            mechs.remove(mech.getId());
            player.sendMessage(color("&cCould not deploy the mech here."));
            return;
        }
        MechRuntime mechRuntime = runtime.get(mech.getId());
        if (mechRuntime != null) mechRuntime.vehicle.addPassenger(player);
        Util.consumeOne(player, core);
        saveMechs();
        player.sendMessage(color("&aMech deployed. Left-click fires the left arm; right-click fires the right. Crouch to exit."));
    }

    private boolean hasRoom(Location base) {
        World world = base.getWorld();
        for (int y = 2; y <= 7; y++) {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    if (world.getBlockAt(base.getBlockX() + x, base.getBlockY() + y, base.getBlockZ() + z).getType().isSolid()) return false;
                }
            }
        }
        return true;
    }

    private void fireWeapon(Player player, String mechId, Mech.Arm arm) {
        Mech mech = mechs.get(mechId);
        MechRuntime mechRuntime = runtime.get(mechId);
        if (mech == null || mechRuntime == null) return;
        Mech.Weapon weapon = mech.getWeapon(arm);
        if (weapon == Mech.Weapon.EMPTY) {
            player.sendMessage(color("&eThat arm has no weapon installed."));
            return;
        }
        String cooldownKey = mechId + ":" + arm.name();
        long now = System.currentTimeMillis();
        long cooldown = Math.max(1L, plugin.getConfig().getLong(
            weapon == Mech.Weapon.MACHINE_GUN ? "mechs.machine_gun.cooldown_ticks" : "mechs.rocket.cooldown_ticks",
                weapon == Mech.Weapon.MACHINE_GUN ? 4L : 30L)) * 50L;
        if (now < nextShotAt.getOrDefault(cooldownKey, 0L)) return;
        nextShotAt.put(cooldownKey, now + cooldown);

        Location vehicleLocation = mechRuntime.vehicle.getLocation();
        double side = arm == Mech.Arm.LEFT ? -1.55 : 1.55;
        double radians = Math.toRadians(vehicleLocation.getYaw());
        double offsetX = side * Math.cos(radians);
        double offsetZ = side * Math.sin(radians);
        Location muzzle = vehicleLocation.clone().add(offsetX, 4.0, offsetZ);
        Snowball projectile = vehicleLocation.getWorld().spawn(muzzle, Snowball.class);
        double speed = Math.max(0.1, plugin.getConfig().getDouble(
                weapon == Mech.Weapon.MACHINE_GUN ? "mechs.machine_gun.speed" : "mechs.rocket.speed",
                weapon == Mech.Weapon.MACHINE_GUN ? 2.4 : 1.6));
        projectile.setShooter(player);
        projectile.setVelocity(player.getEyeLocation().getDirection().normalize().multiply(speed));
        projectile.setMetadata("windturrets_mech", new FixedMetadataValue(plugin, mechId));
        projectiles.put(projectile.getUniqueId(), new ProjectileShot(mechId, weapon, player.getUniqueId(), projectile));
        vehicleLocation.getWorld().spawnParticle(Particle.FLAME, muzzle, 1, 0.05, 0.05, 0.05, 0.01);
    }

    private void explodeRocket(Location location, ProjectileShot shot) {
        double radius = Math.max(1.0, plugin.getConfig().getDouble("mechs.rocket.radius", 3.0));
        double damage = Math.max(0.0, plugin.getConfig().getDouble("mechs.rocket.damage", 6.0));
        location.getWorld().spawnParticle(Particle.EXPLOSION_LARGE, location, 1);
        Player shooter = Bukkit.getPlayer(shot.shooterUuid);
        for (Entity entity : location.getWorld().getNearbyEntities(location, radius, radius, radius)) {
            if (!(entity instanceof Player)) continue;
            Player victim = (Player) entity;
            if (victim.getUniqueId().equals(shot.shooterUuid) || !plugin.allowsPvP(victim.getLocation())) continue;
            if (damage > 0.0) {
                if (shooter == null) victim.damage(damage);
                else victim.damage(damage, shooter);
            }
            Vector knockback = victim.getLocation().toVector().subtract(location.toVector());
            if (knockback.lengthSquared() > 0.0) knockback.normalize();
            knockback.multiply(1.5).setY(0.65);
            victim.setVelocity(knockback);
        }
    }

    private boolean spawnMech(Mech mech) {
        if (runtime.containsKey(mech.getId())) return true;
        Location base = mech.getLocation();
        if (base == null || !base.getWorld().isChunkLoaded(base.getBlockX() >> 4, base.getBlockZ() >> 4)) return false;

        Horse vehicle = base.getWorld().spawn(base, Horse.class);
        vehicle.setTamed(true);
        Player owner = Bukkit.getPlayer(mech.getOwnerUuid());
        if (owner != null) vehicle.setOwner(owner);
        vehicle.setDomestication(vehicle.getMaxDomestication());
        vehicle.getInventory().setSaddle(new ItemStack(Material.SADDLE));
        vehicle.setRemoveWhenFarAway(false);
        vehicle.setCustomName("WindTurrets Mech");
        vehicle.setCustomNameVisible(false);
        vehicle.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0, true, false), true);
        setEngineSpeed(vehicle, mech);

        ArmorStand hitbox = base.getWorld().spawn(base.clone().add(0.0, 3.5, 0.0), ArmorStand.class);
        hitbox.setVisible(false);
        hitbox.setMarker(false);
        hitbox.setGravity(false);
        hitbox.setInvulnerable(false);
        hitbox.setArms(false);
        hitbox.setBasePlate(false);

        MechRuntime mechRuntime = new MechRuntime(vehicle, hitbox);
        runtime.put(mech.getId(), mechRuntime);
        entityToMech.put(vehicle.getUniqueId(), mech.getId());
        entityToMech.put(hitbox.getUniqueId(), mech.getId());
        refreshModel(mech, mechRuntime);
        return true;
    }

    private void refreshModel(Mech mech, MechRuntime mechRuntime) {
        for (FallingBlock part : mechRuntime.parts) {
            entityToMech.remove(part.getUniqueId());
            if (part.isValid()) part.remove();
        }
        mechRuntime.parts.clear();
        List<PartSpec> specs = createModel(mech);
        mechRuntime.specs.clear();
        mechRuntime.specs.addAll(specs);
        Location base = mechRuntime.vehicle.getLocation();
        for (PartSpec spec : specs) {
            FallingBlock part = base.getWorld().spawnFallingBlock(modelLocation(base, spec), spec.material, (byte) 0);
            part.setGravity(false);
            part.setDropItem(false);
            part.setHurtEntities(false);
            part.setInvulnerable(true);
            mechRuntime.parts.add(part);
            entityToMech.put(part.getUniqueId(), mech.getId());
        }
    }

    private List<PartSpec> createModel(Mech mech) {
        List<PartSpec> parts = new ArrayList<>();
        parts.add(new PartSpec(-0.55, 0.25, 0.0, Material.IRON_BLOCK));
        parts.add(new PartSpec(0.55, 0.25, 0.0, Material.IRON_BLOCK));
        parts.add(new PartSpec(-0.55, 1.15, 0.0, Material.OBSIDIAN));
        parts.add(new PartSpec(0.55, 1.15, 0.0, Material.OBSIDIAN));
        parts.add(new PartSpec(0.0, 2.05, 0.0, Material.IRON_BLOCK));
        parts.add(new PartSpec(0.0, 2.95, 0.0, Material.IRON_BLOCK));
        parts.add(new PartSpec(0.0, 3.85, 0.0, Material.IRON_BLOCK));
        parts.add(new PartSpec(0.0, 4.75, 0.0, Material.IRON_BLOCK));
        parts.add(new PartSpec(-0.95, 4.55, 0.0, Material.GOLD_BLOCK));
        parts.add(new PartSpec(0.95, 4.55, 0.0, Material.GOLD_BLOCK));
        parts.add(new PartSpec(-1.25, 3.55, 0.0, Material.IRON_BLOCK));
        parts.add(new PartSpec(1.25, 3.55, 0.0, Material.IRON_BLOCK));
        parts.add(new PartSpec(-1.25, 2.65, 0.0, Material.IRON_BLOCK));
        parts.add(new PartSpec(1.25, 2.65, 0.0, Material.IRON_BLOCK));
        parts.add(new PartSpec(0.0, 5.75, 0.0, Material.OBSIDIAN));
        parts.add(new PartSpec(0.0, 6.65, 0.0, Material.SEA_LANTERN));
        parts.add(new PartSpec(-1.65, 3.45, -0.35, weaponMaterial(mech.getWeapon(Mech.Arm.LEFT))));
        parts.add(new PartSpec(1.65, 3.45, -0.35, weaponMaterial(mech.getWeapon(Mech.Arm.RIGHT))));
        return parts;
    }

    private Material weaponMaterial(Mech.Weapon weapon) {
        if (weapon == Mech.Weapon.ROCKET_LAUNCHER) return Material.TNT;
        if (weapon == Mech.Weapon.MACHINE_GUN) return Material.REDSTONE_BLOCK;
        return Material.IRON_BLOCK;
    }

    private Location modelLocation(Location base, PartSpec part) {
        double radians = Math.toRadians(base.getYaw());
        double x = part.x * Math.cos(radians) - part.z * Math.sin(radians);
        double z = part.x * Math.sin(radians) + part.z * Math.cos(radians);
        return base.clone().add(x, -0.25 + part.y, z);
    }

    private void setEngineSpeed(Horse horse, Mech mech) {
        double perLevel = Math.max(0.0, plugin.getConfig().getDouble("mechs.upgrades.engine.speed_per_level", 0.025));
        double speed = Math.min(0.45, 0.20 + mech.getEngineLevel() * perLevel);
        horse.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(speed);
    }

    private void tick() {
        ticks++;
        processHeldInputs();
        for (Mech mech : new ArrayList<>(mechs.values())) {
            MechRuntime mechRuntime = runtime.get(mech.getId());
            if (mechRuntime == null) {
                spawnMech(mech);
                continue;
            }
            if (!mechRuntime.vehicle.isValid() || mechRuntime.vehicle.isDead()) {
                despawnRuntime(mech.getId());
                continue;
            }
            Location base = mechRuntime.vehicle.getLocation();
            mech.setLocation(base);
            Location hitboxLocation = base.clone().add(0.0, 3.5, 0.0);
            teleportIfMoved(mechRuntime.hitbox, hitboxLocation);
            for (int index = 0; index < mechRuntime.parts.size(); index++) {
                FallingBlock part = mechRuntime.parts.get(index);
                if (!part.isValid()) continue;
                teleportIfMoved(part, modelLocation(base, mechRuntime.specs.get(index)));
            }
            if (!mechRuntime.vehicle.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
                mechRuntime.vehicle.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0, true, false), true);
            }
            if (ticks % 600L == 0L) saveMechs();
        }
        projectiles.entrySet().removeIf(entry -> !entry.getValue().projectile.isValid());
    }

    private void registerFireInput(Player player, String mechId, Mech.Arm arm) {
        String key = player.getUniqueId() + ":" + arm.name();
        int timeoutTicks = Math.max(2, plugin.getConfig().getInt("mechs.input_hold_timeout_ticks", 5));
        FireInput input = fireInputs.get(key);
        boolean repeated = input != null && input.mechId.equals(mechId)
                && ticks - input.lastInputTick <= timeoutTicks;
        if (!repeated) input = new FireInput(mechId, player.getUniqueId(), arm);
        input.lastInputTick = ticks;
        if (repeated) input.repeating = true;
        fireInputs.put(key, input);
        fireWeapon(player, mechId, arm);
    }

    private void processHeldInputs() {
        int timeoutTicks = Math.max(2, plugin.getConfig().getInt("mechs.input_hold_timeout_ticks", 5));
        java.util.Iterator<Map.Entry<String, FireInput>> iterator = fireInputs.entrySet().iterator();
        while (iterator.hasNext()) {
            FireInput input = iterator.next().getValue();
            Player player = Bukkit.getPlayer(input.playerUuid);
            if (ticks - input.lastInputTick > timeoutTicks || player == null
                    || !input.mechId.equals(findRiddenMech(player))) {
                iterator.remove();
                continue;
            }
            if (input.repeating) fireWeapon(player, input.mechId, input.arm);
        }
    }

    private void clearFireInputs(Player player) {
        UUID playerUuid = player.getUniqueId();
        fireInputs.entrySet().removeIf(entry -> entry.getValue().playerUuid.equals(playerUuid));
    }

    private void teleportIfMoved(Entity entity, Location destination) {
        Location current = entity.getLocation();
        if (current.getWorld() != destination.getWorld()
                || current.distanceSquared(destination) > 0.0025
                || Math.abs(current.getYaw() - destination.getYaw()) > 2.0f) entity.teleport(destination);
    }

    private void spawnInLoadedChunks() {
        for (Mech mech : mechs.values()) spawnMech(mech);
    }

    private String findRiddenMech(Player player) {
        Entity vehicle = player.getVehicle();
        if (vehicle == null) return null;
        String mechId = entityToMech.get(vehicle.getUniqueId());
        return mechId != null && runtime.containsKey(mechId) ? mechId : null;
    }

    private Player getRider(String mechId) {
        MechRuntime mechRuntime = runtime.get(mechId);
        if (mechRuntime == null) return null;
        Entity passenger = mechRuntime.vehicle.getPassenger();
        return passenger instanceof Player ? (Player) passenger : null;
    }

    private boolean canControl(Player player, Mech mech) {
        return mech != null && (mech.isOwner(player) || player.hasPermission("windturrets.admin"));
    }

    private void destroyMech(String mechId) {
        Mech mech = mechs.remove(mechId);
        if (mech == null) return;
        Location location = mech.getLocation();
        despawnRuntime(mechId);
        if (location != null) {
            location.getWorld().spawnParticle(Particle.EXPLOSION_LARGE, location.clone().add(0.0, 3.0, 0.0), 1);
            location.getWorld().dropItemNaturally(location, createMechCore());
        }
        saveMechs();
        Player owner = Bukkit.getPlayer(mech.getOwnerUuid());
        if (owner != null) owner.sendMessage(color("&cYour mech was destroyed. A replacement core was dropped at its location."));
    }

    private void despawnRuntime(String mechId) {
        MechRuntime mechRuntime = runtime.remove(mechId);
        if (mechRuntime == null) return;
        entityToMech.remove(mechRuntime.vehicle.getUniqueId());
        entityToMech.remove(mechRuntime.hitbox.getUniqueId());
        if (mechRuntime.vehicle.isValid()) mechRuntime.vehicle.remove();
        if (mechRuntime.hitbox.isValid()) mechRuntime.hitbox.remove();
        for (FallingBlock part : mechRuntime.parts) {
            entityToMech.remove(part.getUniqueId());
            if (part.isValid()) part.remove();
        }
    }

    private void loadMechs() {
        if (!dataFile.exists()) return;
        YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection section = data.getConfigurationSection("mechs");
        if (section == null) return;
        double defaultHealth = Math.max(1.0, plugin.getConfig().getDouble("mechs.max_health", 120.0));
        for (String id : section.getKeys(false)) {
            Mech mech = Mech.fromConfig(id, section.getConfigurationSection(id), defaultHealth);
            if (mech != null) mechs.put(mech.getId(), mech);
            else plugin.getLogger().warning("Skipping invalid mech record " + id + ".");
        }
    }

    private void saveMechs() {
        YamlConfiguration data = new YamlConfiguration();
        for (Mech mech : mechs.values()) mech.toConfigSection(data.createSection("mechs." + mech.getId()));
        try {
            data.save(dataFile);
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save mechs.yml: " + exception.getMessage());
        }
    }

    private void openMechMenu(Player player, Mech mech) {
        MechMenuHolder holder = new MechMenuHolder(MenuView.MAIN, mech.getId(), null);
        Inventory inventory = Bukkit.createInventory(holder, 27, "Mech Controls");
        holder.inventory = inventory;
        inventory.setItem(10, menuItem(Material.REDSTONE_BLOCK, "&eLeft arm: " + mech.getWeapon(Mech.Arm.LEFT).getConfigName(), "&7Click to change weapon"));
        inventory.setItem(13, menuItem(Material.IRON_BLOCK, "&cDurability: " + Math.ceil(mech.getHealth()) + "/" + Math.ceil(mech.getMaxHealth()), "&7Armor level " + mech.getArmorLevel(), "&7Engine level " + mech.getEngineLevel()));
        inventory.setItem(16, menuItem(Material.REDSTONE_BLOCK, "&eRight arm: " + mech.getWeapon(Mech.Arm.RIGHT).getConfigName(), "&7Click to change weapon"));
        inventory.setItem(22, menuItem(Material.ANVIL, "&aUpgrades and repairs", "&7Improve durability and movement"));
        player.openInventory(inventory);
    }

    private void openWeaponMenu(Player player, Mech mech, Mech.Arm arm) {
        MechMenuHolder holder = new MechMenuHolder(MenuView.WEAPON, mech.getId(), arm);
        Inventory inventory = Bukkit.createInventory(holder, 27, (arm == Mech.Arm.LEFT ? "Left" : "Right") + " Arm Weapon");
        holder.inventory = inventory;
        inventory.setItem(10, menuItem(Material.IRON_BLOCK, "&7Empty arm", "&8Click to remove the weapon"));
        inventory.setItem(13, menuItem(Material.REDSTONE_BLOCK, "&eMachine gun module", "&7Craft: iron and redstone", "&7Requires one module in your inventory"));
        inventory.setItem(16, menuItem(Material.TNT, "&cRocket launcher module", "&7Craft: TNT, fire charges, and iron", "&7Requires one module in your inventory"));
        inventory.setItem(26, menuItem(Material.ARROW, "&eBack"));
        player.openInventory(inventory);
    }

    private void openUpgradeMenu(Player player, Mech mech) {
        MechMenuHolder holder = new MechMenuHolder(MenuView.UPGRADES, mech.getId(), null);
        Inventory inventory = Bukkit.createInventory(holder, 27, "Mech Upgrades");
        holder.inventory = inventory;
        int maxLevel = Math.max(1, plugin.getConfig().getInt("mechs.upgrades.max_level", 5));
        int armorCost = getUpgradeCost("mechs.upgrades.armor.base_cost", mech.getArmorLevel());
        int engineCost = getUpgradeCost("mechs.upgrades.engine.base_cost", mech.getEngineLevel());
        int repairCost = Math.max(1, plugin.getConfig().getInt("mechs.repair.cost", 8));
        inventory.setItem(11, menuItem(Material.DIAMOND_BLOCK, "&bArmor: " + mech.getArmorLevel() + "/" + maxLevel,
                "&7Adds " + (int) plugin.getConfig().getDouble("mechs.upgrades.armor.health_per_level", 40.0) + " max durability",
                "&7Cost: " + armorCost + " diamond blocks"));
        inventory.setItem(13, menuItem(Material.IRON_INGOT, "&aRepair mech",
                "&7Restores " + (int) plugin.getConfig().getDouble("mechs.repair.health", 40.0) + " durability",
                "&7Cost: " + repairCost + " iron ingots"));
        inventory.setItem(15, menuItem(Material.IRON_BLOCK, "&eEngine: " + mech.getEngineLevel() + "/" + maxLevel,
                "&7Increases movement speed",
                "&7Cost: " + engineCost + " iron blocks"));
        inventory.setItem(26, menuItem(Material.ARROW, "&eBack"));
        player.openInventory(inventory);
    }

    private void handleMainMenu(Player player, Mech mech, int slot) {
        if (slot == 10) openWeaponMenu(player, mech, Mech.Arm.LEFT);
        else if (slot == 16) openWeaponMenu(player, mech, Mech.Arm.RIGHT);
        else if (slot == 22) openUpgradeMenu(player, mech);
    }

    private void handleWeaponMenu(Player player, Mech mech, Mech.Arm arm, int slot) {
        if (slot == 26) {
            openMechMenu(player, mech);
            return;
        }
        Mech.Weapon weapon;
        String marker;
        Material material;
        if (slot == 10) {
            weapon = Mech.Weapon.EMPTY;
            marker = null;
            material = null;
        } else if (slot == 13) {
            weapon = Mech.Weapon.MACHINE_GUN;
            marker = MACHINE_GUN_MARKER;
            material = Material.IRON_INGOT;
        } else if (slot == 16) {
            weapon = Mech.Weapon.ROCKET_LAUNCHER;
            marker = ROCKET_MARKER;
            material = Material.FIREBALL;
        } else return;

        if (weapon != mech.getWeapon(arm) && weapon != Mech.Weapon.EMPTY && !consumeModule(player, material, marker)) {
            player.sendMessage(color("&cYou need a crafted " + weapon.getConfigName() + " module."));
            return;
        }
        mech.setWeapon(arm, weapon);
        MechRuntime mechRuntime = runtime.get(mech.getId());
        if (mechRuntime != null) refreshModel(mech, mechRuntime);
        saveMechs();
        player.sendMessage(color("&a" + (arm == Mech.Arm.LEFT ? "Left" : "Right") + " arm set to " + weapon.getConfigName() + "."));
        openMechMenu(player, mech);
    }

    private void handleUpgradeMenu(Player player, Mech mech, int slot) {
        if (slot == 26) {
            openMechMenu(player, mech);
            return;
        }
        if (slot == 11) {
            int maxLevel = Math.max(1, plugin.getConfig().getInt("mechs.upgrades.max_level", 5));
            if (mech.getArmorLevel() >= maxLevel) {
                player.sendMessage(color("&cArmor upgrade is at maximum level."));
                return;
            }
            int cost = getUpgradeCost("mechs.upgrades.armor.base_cost", mech.getArmorLevel());
            if (!consume(player, Material.DIAMOND_BLOCK, cost)) {
                player.sendMessage(color("&cYou need " + cost + " diamond blocks."));
                return;
            }
            mech.upgradeArmor(plugin.getConfig().getDouble("mechs.upgrades.armor.health_per_level", 40.0));
        } else if (slot == 15) {
            int maxLevel = Math.max(1, plugin.getConfig().getInt("mechs.upgrades.max_level", 5));
            if (mech.getEngineLevel() >= maxLevel) {
                player.sendMessage(color("&cEngine upgrade is at maximum level."));
                return;
            }
            int cost = getUpgradeCost("mechs.upgrades.engine.base_cost", mech.getEngineLevel());
            if (!consume(player, Material.IRON_BLOCK, cost)) {
                player.sendMessage(color("&cYou need " + cost + " iron blocks."));
                return;
            }
            mech.upgradeEngine();
            MechRuntime mechRuntime = runtime.get(mech.getId());
            if (mechRuntime != null) setEngineSpeed(mechRuntime.vehicle, mech);
        } else if (slot == 13) {
            int cost = Math.max(1, plugin.getConfig().getInt("mechs.repair.cost", 8));
            if (!consume(player, Material.IRON_INGOT, cost)) {
                player.sendMessage(color("&cYou need " + cost + " iron ingots."));
                return;
            }
            mech.repair(plugin.getConfig().getDouble("mechs.repair.health", 40.0));
        } else return;
        saveMechs();
        openUpgradeMenu(player, mech);
    }

    private int getUpgradeCost(String path, int level) {
        long cost = (long) Math.max(1, plugin.getConfig().getInt(path, 2)) * (level + 1L);
        return (int) Math.min(Integer.MAX_VALUE, cost);
    }

    private boolean consume(Player player, Material material, int amount) {
        if (!player.getInventory().containsAtLeast(new ItemStack(material), amount)) return false;
        player.getInventory().removeItem(new ItemStack(material, amount));
        return true;
    }

    private boolean consumeModule(Player player, Material material, String marker) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType() != material || !item.hasItemMeta()) continue;
            if (!marker.equals(item.getItemMeta().getLocalizedName())) continue;
            if (item.getAmount() > 1) item.setAmount(item.getAmount() - 1);
            else player.getInventory().removeItem(item);
            return true;
        }
        return false;
    }

    private boolean isMechCore(ItemStack item) {
        return item != null && item.hasItemMeta() && CORE_MARKER.equals(item.getItemMeta().getLocalizedName());
    }

    private ItemStack createMechCore() {
        return markedItem(Material.IRON_BLOCK, "&bMech Core", CORE_MARKER,
                "&7Right-click to deploy an 8-block mech", "&7Crouch to exit after mounting");
    }

    private ItemStack createWeaponModule(Mech.Weapon weapon) {
        if (weapon == Mech.Weapon.MACHINE_GUN) {
            return markedItem(Material.IRON_INGOT, "&eMachine Gun Module", MACHINE_GUN_MARKER, "&7Install in either mech arm");
        }
        return markedItem(Material.FIREBALL, "&cRocket Launcher Module", ROCKET_MARKER, "&7Install in either mech arm");
    }

    private ItemStack markedItem(Material material, String name, String marker, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color(name));
        meta.setLocalizedName(marker);
        List<String> coloredLore = new ArrayList<>();
        for (String line : lore) coloredLore.add(color(line));
        meta.setLore(coloredLore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack menuItem(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color(name));
        if (lore != null && lore.length > 0 && lore[0] != null) {
            List<String> coloredLore = new ArrayList<>();
            for (String line : lore) coloredLore.add(color(line));
            meta.setLore(coloredLore);
        }
        item.setItemMeta(meta);
        return item;
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private enum MenuView { MAIN, WEAPON, UPGRADES }

    private static class MechMenuHolder implements InventoryHolder {
        private final MenuView view;
        private final String mechId;
        private final Mech.Arm arm;
        private Inventory inventory;

        private MechMenuHolder(MenuView view, String mechId, Mech.Arm arm) {
            this.view = view;
            this.mechId = mechId;
            this.arm = arm;
        }

        @Override
        public Inventory getInventory() { return inventory; }
    }

    private static class MechRuntime {
        private final Horse vehicle;
        private final ArmorStand hitbox;
        private final List<FallingBlock> parts = new ArrayList<>();
        private final List<PartSpec> specs = new ArrayList<>();

        private MechRuntime(Horse vehicle, ArmorStand hitbox) {
            this.vehicle = vehicle;
            this.hitbox = hitbox;
        }
    }

    private static class PartSpec {
        private final double x;
        private final double y;
        private final double z;
        private final Material material;

        private PartSpec(double x, double y, double z, Material material) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.material = material;
        }
    }

    private static class ProjectileShot {
        private final String mechId;
        private final Mech.Weapon weapon;
        private final UUID shooterUuid;
        private final Snowball projectile;

        private ProjectileShot(String mechId, Mech.Weapon weapon, UUID shooterUuid, Snowball projectile) {
            this.mechId = mechId;
            this.weapon = weapon;
            this.shooterUuid = shooterUuid;
            this.projectile = projectile;
        }
    }

    private static class FireInput {
        private final String mechId;
        private final UUID playerUuid;
        private final Mech.Arm arm;
        private long lastInputTick;
        private boolean repeating;

        private FireInput(String mechId, UUID playerUuid, Mech.Arm arm) {
            this.mechId = mechId;
            this.playerUuid = playerUuid;
            this.arm = arm;
        }
    }
}