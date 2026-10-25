package shadowlord.shadowspears;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.material.MaterialData;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class ShadowSpears extends JavaPlugin implements Listener, CommandExecutor {

    private static final String ITEM_MARKER = ChatColor.DARK_GRAY + "ShadowSpears:";
    private final Map<UUID, Long> lastJabTick = new HashMap<>();
    private final Map<UUID, Long> lastChargeTick = new HashMap<>();
    private final Map<UUID, Long> chargeStartedTick = new HashMap<>();
    private final Map<UUID, Long> handledAttackTick = new HashMap<>();
    private final Map<UUID, PendingHit> pendingHits = new HashMap<>();
    private long currentTick;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("spear").setExecutor(this);
        registerRecipes();
        Bukkit.getScheduler().runTaskTimer(this, () -> currentTick++, 1L, 1L);
        getLogger().info("ShadowSpears enabled.");
    }

    private void registerRecipes() {
        for (byte plankData = 0; plankData < 6; plankData++) {
            registerRecipe(SpearTier.WOOD, new MaterialData(Material.WOOD, plankData));
        }
        registerRecipe(SpearTier.STONE, Material.COBBLESTONE);
        registerRecipe(SpearTier.GOLD, Material.GOLD_INGOT);
        registerRecipe(SpearTier.IRON, Material.IRON_INGOT);
        registerRecipe(SpearTier.DIAMOND, Material.DIAMOND);
    }

    private void registerRecipe(SpearTier tier, Material tip) {
        registerRecipe(tier, new MaterialData(tip));
    }

    private void registerRecipe(SpearTier tier, MaterialData tip) {
        ShapedRecipe recipe = new ShapedRecipe(createSpear(tier));
        recipe.shape("  T", " S ", "S  ");
        recipe.setIngredient('T', tip);
        recipe.setIngredient('S', Material.STICK);
        addRecipe(recipe, tier);
    }

    private void addRecipe(ShapedRecipe recipe, SpearTier tier) {
        if (!Bukkit.addRecipe(recipe)) {
            getLogger().warning("Could not register the " + tier.displayName + " Spear recipe.");
        }
    }

    private ItemStack createSpear(SpearTier tier) {
        ItemStack item = new ItemStack(tier.icon);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + tier.displayName + " Spear");
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "A long-reach weapon.");
        lore.add(ITEM_MARKER + tier.key);
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private SpearTier getSpearTier(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        if (!meta.hasLore()) return null;
        for (String line : meta.getLore()) {
            if (line.startsWith(ITEM_MARKER)) {
                return SpearTier.fromKey(line.substring(ITEM_MARKER.length()));
            }
        }
        return null;
    }

    @EventHandler
    public void onSpearUse(PlayerInteractEvent event) {
        if (event.getAction().name().startsWith("RIGHT_CLICK")
                && getSpearTier(event.getItem()) != null) {
            chargeStartedTick.put(event.getPlayer().getUniqueId(), currentTick);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player) || !(event.getEntity() instanceof LivingEntity)) return;

        Player player = (Player) event.getDamager();
        SpearTier tier = getHeldSpear(player);
        if (tier == null) return;

        UUID playerId = player.getUniqueId();
        PendingHit pending = pendingHits.get(playerId);
        if (pending != null && pending.targetId.equals(event.getEntity().getUniqueId())) {
            pendingHits.remove(playerId);
            event.setDamage(pending.damage);
            return;
        }
        if (isHandledThisTick(playerId)) {
            event.setCancelled(true);
            return;
        }
        handledAttackTick.put(playerId, currentTick);

        LivingEntity target = (LivingEntity) event.getEntity();
        double distance = player.getEyeLocation().distance(target.getLocation().add(0, target.getHeight() / 2.0, 0));
        if (distance < getConfig().getDouble("range.minimum", 2.0)) {
            event.setCancelled(true);
            return;
        }

        Long chargeStart = chargeStartedTick.remove(playerId);
        if (isChargeActive(chargeStart)) {
            long lastCharge = lastChargeTick.containsKey(playerId) ? lastChargeTick.get(playerId) : Long.MIN_VALUE;
            if (currentTick - lastCharge < 10L) {
                event.setCancelled(true);
                return;
            }
            double speed = relativeApproachSpeed(player, target);
            if (speed < getConfig().getDouble("charge.minimum_speed", 4.6)) {
                event.setCancelled(true);
                return;
            }
            event.setDamage(tier.chargeMultiplier * speed);
            applyChargeKnockback(player, target, speed);
            lastChargeTick.put(playerId, currentTick);
        } else {
            long lastJab = lastJabTick.containsKey(playerId) ? lastJabTick.get(playerId) : Long.MIN_VALUE;
            if (currentTick - lastJab < tier.jabCooldownTicks) {
                event.setCancelled(true);
                return;
            }
            lastJabTick.put(playerId, currentTick);
            event.setDamage(tier.jabDamage);
        }
    }

    @EventHandler
    public void onArmSwing(PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        SpearTier tier = getHeldSpear(player);
        if (tier == null || isHandledThisTick(playerId)) return;

        List<LivingEntity> targets = findTargets(player);
        if (targets.isEmpty()) return;

        Long chargeStart = chargeStartedTick.remove(playerId);
        boolean charging = isChargeActive(chargeStart);
        if (charging) {
            long lastCharge = lastChargeTick.containsKey(playerId) ? lastChargeTick.get(playerId) : Long.MIN_VALUE;
            if (currentTick - lastCharge < 10L) return;
        } else {
            long lastJab = lastJabTick.containsKey(playerId) ? lastJabTick.get(playerId) : Long.MIN_VALUE;
            if (currentTick - lastJab < tier.jabCooldownTicks) return;
        }

        handledAttackTick.put(playerId, currentTick);
        if (charging) {
            lastChargeTick.put(playerId, currentTick);
        } else {
            lastJabTick.put(playerId, currentTick);
        }
        for (LivingEntity target : targets) {
            double damage = tier.jabDamage;
            double speed = 0.0;
            if (charging) {
                speed = relativeApproachSpeed(player, target);
                if (speed < getConfig().getDouble("charge.minimum_speed", 4.6)) continue;
                damage = tier.chargeMultiplier * speed;
            }
            pendingHits.put(playerId, new PendingHit(target.getUniqueId(), damage));
            target.damage(damage, player);
            pendingHits.remove(playerId);
            if (charging) {
                applyChargeKnockback(player, target, speed);
            }
        }
    }

    private SpearTier getHeldSpear(Player player) {
        SpearTier mainHand = getSpearTier(player.getItemInHand());
        return mainHand == null ? getSpearTier(player.getInventory().getItemInOffHand()) : mainHand;
    }

    private List<LivingEntity> findTargets(Player player) {
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        double maximum = getConfig().getDouble("range.maximum", 4.5);
        double minimum = getConfig().getDouble("range.minimum", 2.0);
        double blockLimit = getBlockObstructionDistance(eye, direction, maximum);
        List<TargetHit> hits = new ArrayList<>();

        for (Entity entity : player.getNearbyEntities(maximum + 1.0, maximum + 1.0, maximum + 1.0)) {
            if (!(entity instanceof LivingEntity) || entity == player || entity.isDead()) continue;
            LivingEntity target = (LivingEntity) entity;
            double hitDistance = rayIntersection(eye, direction, target);
            if (hitDistance < minimum || hitDistance > maximum || hitDistance > blockLimit) continue;
            hits.add(new TargetHit(target, hitDistance));
        }

        Collections.sort(hits, Comparator.comparingDouble(hit -> hit.distance));
        List<LivingEntity> targets = new ArrayList<>();
        for (TargetHit hit : hits) targets.add(hit.entity);
        return targets;
    }

    private double rayIntersection(Location origin, Vector direction, LivingEntity entity) {
        Location base = entity.getLocation();
        double halfWidth = entity.getWidth() / 2.0 + 0.125;
        double minX = base.getX() - halfWidth;
        double maxX = base.getX() + halfWidth;
        double minY = base.getY() - 0.125;
        double maxY = base.getY() + entity.getHeight() + 0.125;
        double minZ = base.getZ() - halfWidth;
        double maxZ = base.getZ() + halfWidth;
        double near = 0.0;
        double far = getConfig().getDouble("range.maximum", 4.5);

        double[] interval = intersectAxis(origin.getX(), direction.getX(), minX, maxX, near, far);
        if (interval == null) return Double.POSITIVE_INFINITY;
        near = interval[0];
        far = interval[1];
        interval = intersectAxis(origin.getY(), direction.getY(), minY, maxY, near, far);
        if (interval == null) return Double.POSITIVE_INFINITY;
        near = interval[0];
        far = interval[1];
        interval = intersectAxis(origin.getZ(), direction.getZ(), minZ, maxZ, near, far);
        return interval == null ? Double.POSITIVE_INFINITY : interval[0];
    }

    private double[] intersectAxis(double origin, double direction, double minimum, double maximum,
                                   double near, double far) {
        if (Math.abs(direction) < 1.0E-9) {
            return origin < minimum || origin > maximum ? null : new double[]{near, far};
        }
        double first = (minimum - origin) / direction;
        double second = (maximum - origin) / direction;
        if (first > second) {
            double swap = first;
            first = second;
            second = swap;
        }
        near = Math.max(near, first);
        far = Math.min(far, second);
        return near <= far ? new double[]{near, far} : null;
    }

    private double getBlockObstructionDistance(Location origin, Vector direction, double maximum) {
        World world = origin.getWorld();
        for (double distance = 0.1; distance <= maximum; distance += 0.1) {
            Location point = origin.clone().add(direction.clone().multiply(distance));
            if (world.getBlockAt(point).getType().isSolid()) return distance;
        }
        return maximum;
    }

    private double relativeApproachSpeed(Player player, LivingEntity target) {
        Vector towardTarget = target.getLocation().add(0, target.getHeight() / 2.0, 0)
                .subtract(player.getLocation()).toVector();
        if (towardTarget.lengthSquared() < 1.0E-9) return 0.0;
        towardTarget.normalize();
        double playerToward = player.getVelocity().dot(towardTarget);
        double targetToward = target.getVelocity().dot(towardTarget.clone().multiply(-1.0));
        return Math.max(0.0, playerToward + targetToward) * 20.0;
    }

    private void applyChargeKnockback(Player player, LivingEntity target, double speed) {
        if (speed < getConfig().getDouble("charge.knockback_speed", 5.1)) return;
        Vector knockback = target.getLocation().toVector().subtract(player.getLocation().toVector());
        if (knockback.lengthSquared() == 0.0) return;
        knockback.normalize().multiply(0.8).setY(0.15);
        target.setVelocity(knockback);
    }

    private boolean isChargeActive(Long startedTick) {
        return startedTick != null
                && currentTick - startedTick <= getConfig().getLong("charge.timeout_ticks", 40L);
    }

    private boolean isHandledThisTick(UUID playerId) {
        Long handledTick = handledAttackTick.get(playerId);
        return handledTick != null && handledTick == currentTick;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(color("&6ShadowSpears"));
            sender.sendMessage(color("&eCraft spears from a tier material and two sticks."));
            sender.sendMessage(color("&eRight-click to ready a charge, then hit while moving toward your target."));
            sender.sendMessage(color("&7/spear give <wood|stone|gold|iron|diamond> [player]"));
            return true;
        }
        if (!args[0].equalsIgnoreCase("give") || args.length < 2 || args.length > 3) {
            sender.sendMessage(color("&cUsage: /spear give <wood|stone|gold|iron|diamond> [player]"));
            return true;
        }
        if (!sender.hasPermission("shadowspears.admin")) {
            sender.sendMessage(color("&cYou do not have permission to give spears."));
            return true;
        }

        SpearTier tier = SpearTier.fromKey(args[1].toLowerCase(Locale.ROOT));
        if (tier == null) {
            sender.sendMessage(color("&cUnknown tier. Choose wood, stone, gold, iron, or diamond."));
            return true;
        }
        Player recipient;
        if (args.length == 3) {
            recipient = Bukkit.getPlayerExact(args[2]);
            if (recipient == null) {
                sender.sendMessage(color("&cThat player is not online."));
                return true;
            }
        } else if (sender instanceof Player) {
            recipient = (Player) sender;
        } else {
            sender.sendMessage(color("&cSpecify an online player when using this command from console."));
            return true;
        }

        ItemStack spear = createSpear(tier);
        Map<Integer, ItemStack> leftovers = recipient.getInventory().addItem(spear);
        for (ItemStack leftover : leftovers.values()) {
            recipient.getWorld().dropItemNaturally(recipient.getLocation(), leftover);
        }
        sender.sendMessage(color("&aGave a " + tier.displayName + " Spear to " + recipient.getName() + "."));
        return true;
    }

    private String color(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    private enum SpearTier {
        WOOD("wood", "Wooden", Material.WOOD_SWORD, 2.0, 0.70, 13),
        STONE("stone", "Stone", Material.STONE_SWORD, 4.0, 0.82, 15),
        GOLD("gold", "Golden", Material.GOLD_SWORD, 2.0, 0.70, 19),
        IRON("iron", "Iron", Material.IRON_SWORD, 6.0, 0.95, 19),
        DIAMOND("diamond", "Diamond", Material.DIAMOND_SWORD, 8.0, 1.075, 21);

        private final String key;
        private final String displayName;
        private final Material icon;
        private final double jabDamage;
        private final double chargeMultiplier;
        private final int jabCooldownTicks;

        SpearTier(String key, String displayName, Material icon, double jabDamage,
                  double chargeMultiplier, int jabCooldownTicks) {
            this.key = key;
            this.displayName = displayName;
            this.icon = icon;
            this.jabDamage = jabDamage;
            this.chargeMultiplier = chargeMultiplier;
            this.jabCooldownTicks = jabCooldownTicks;
        }

        private static SpearTier fromKey(String key) {
            for (SpearTier tier : values()) {
                if (tier.key.equalsIgnoreCase(key)) return tier;
            }
            return null;
        }
    }

    private static class PendingHit {
        private final UUID targetId;
        private final double damage;

        private PendingHit(UUID targetId, double damage) {
            this.targetId = targetId;
            this.damage = damage;
        }
    }

    private static class TargetHit {
        private final LivingEntity entity;
        private final double distance;

        private TargetHit(LivingEntity entity, double distance) {
            this.entity = entity;
            this.distance = distance;
        }
    }
}
