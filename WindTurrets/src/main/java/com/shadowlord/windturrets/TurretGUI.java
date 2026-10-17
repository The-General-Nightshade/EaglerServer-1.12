package shadowlord.windturrets;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class TurretGUI implements Listener {

    private static final int LIST_PAGE_SIZE = 45;
    private static final int TRUST_PAGE_SIZE = 45;
    private final WindTurrets plugin;

    public TurretGUI(WindTurrets plugin) {
        this.plugin = plugin;
    }

    public void openPlayerList(Player player, int page) {
        openList(player, false, page);
    }

    public void openAdminList(Player player, int page) {
        openList(player, true, page);
    }

    public void refreshOpenInventories() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Inventory inventory = player.getOpenInventory().getTopInventory();
            if (!(inventory.getHolder() instanceof MenuHolder)) continue;
            MenuHolder holder = (MenuHolder) inventory.getHolder();
            if (holder.admin && !player.hasPermission("windturrets.admin")) {
                player.closeInventory();
                continue;
            }

            if (holder.view == View.LIST) {
                for (Map.Entry<Integer, String> entry : holder.turretKeys.entrySet()) {
                    Turret turret = plugin.getTurrets().get(entry.getValue());
                    if (canAccess(player, turret, holder.admin)) {
                        inventory.setItem(entry.getKey(), turretListItem(turret, holder.admin));
                    } else {
                        inventory.setItem(entry.getKey(), null);
                    }
                }
            } else if (holder.view == View.DETAILS) {
                Turret turret = plugin.getTurrets().get(holder.turretKey);
                if (!canAccess(player, turret, holder.admin)) {
                    player.closeInventory();
                    continue;
                }
                inventory.setItem(14, item(Material.PAPER, "&eRange: " + turret.getRadius() + " blocks",
                        list("&7Improve range from Upgrades")));
                inventory.setItem(15, ammoStatusItem(turret));
            }
        }
    }

    private void openList(Player player, boolean admin, int requestedPage) {
        List<Turret> turrets = getTurrets(player, admin);
        int pageCount = Math.max(1, (turrets.size() + LIST_PAGE_SIZE - 1) / LIST_PAGE_SIZE);
        int page = Math.max(0, Math.min(requestedPage, pageCount - 1));
        MenuHolder holder = new MenuHolder(View.LIST, admin, page, null);
        Inventory inventory = Bukkit.createInventory(holder, 54, admin ? "Admin Turrets" : "Your Turrets");
        holder.inventory = inventory;

        int start = page * LIST_PAGE_SIZE;
        int end = Math.min(start + LIST_PAGE_SIZE, turrets.size());
        for (int index = start; index < end; index++) {
            Turret turret = turrets.get(index);
            int slot = index - start;
            inventory.setItem(slot, turretListItem(turret, admin));
            holder.turretKeys.put(slot, turret.getStorageKey());
        }

        if (page > 0) inventory.setItem(45, item(Material.ARROW, "&ePrevious page", null));
        inventory.setItem(49, item(Material.PAPER, "&7Page " + (page + 1) + "/" + pageCount, null));
        if (page + 1 < pageCount) inventory.setItem(53, item(Material.ARROW, "&eNext page", null));
        player.openInventory(inventory);
    }

    private void openDetails(Player player, String storageKey, boolean admin, int page) {
        Turret turret = plugin.getTurrets().get(storageKey);
        if (!canAccess(player, turret, admin)) {
            player.closeInventory();
            player.sendMessage(color("&cYou cannot manage that turret."));
            return;
        }

        MenuHolder holder = new MenuHolder(View.DETAILS, admin, page, storageKey);
        Inventory inventory = Bukkit.createInventory(holder, 27, "Turret Settings");
        holder.inventory = inventory;
        inventory.setItem(4, item(Material.DISPENSER, "&eTurret " + turret.getId(),
                list("&7Owner: &f" + turret.getOwner(), "&7Location: &f" + Util.locString(turret.getLocation()))));
        inventory.setItem(10, item(Material.LEVER, turret.isEnabled() ? "&aDisable turret" : "&cEnable turret",
                list("&7Current: " + (turret.isEnabled() ? "&aEnabled" : "&cDisabled"), "&8Click to toggle")));
        inventory.setItem(12, item(Material.BOOK, "&eFiring mode: " + turret.getFireMode().getConfigName(),
                list("&7Automatic: rapid continuous fire", "&7Semi-automatic: slower repeat fire", "&7Single: one shot per target acquisition", "&8Click to cycle modes")));
        inventory.setItem(13, item(Material.COMPASS, "&eTarget priority: " + turret.getTargetPriority().getConfigName(),
            list("&7Nearest: closest player", "&7Weakest: lowest health", "&7Random: choose randomly", "&8Click to cycle priorities")));
        inventory.setItem(14, item(Material.PAPER, "&eRange: " + turret.getRadius() + " blocks",
            list("&7Improve range from Upgrades")));
        inventory.setItem(15, ammoStatusItem(turret));
        inventory.setItem(16, item(Material.EMERALD, "&aUpgrades",
            list("&7Range, magazine, firing speed, and accuracy", "&7Purchase with configurable items")));
        inventory.setItem(20, item(Material.NAME_TAG, "&eManage trusted players",
                list("&7Trusted players are not targeted", "&8Click to add or remove trust")));
        inventory.setItem(22, item(Material.TNT, "&cRemove turret",
                list("&7Refunds a core to the owner", "&8Click to confirm removal")));
        inventory.setItem(26, item(Material.ARROW, "&eBack to list", null));
        player.openInventory(inventory);
    }

    private void openTrust(Player player, String storageKey, boolean admin, int turretPage, int requestedPage) {
        Turret turret = plugin.getTurrets().get(storageKey);
        if (!canAccess(player, turret, admin)) {
            player.closeInventory();
            player.sendMessage(color("&cYou cannot manage that turret."));
            return;
        }

        List<TrustOption> options = getTrustOptions(turret);
        int pageCount = Math.max(1, (options.size() + TRUST_PAGE_SIZE - 1) / TRUST_PAGE_SIZE);
        int page = Math.max(0, Math.min(requestedPage, pageCount - 1));
        MenuHolder holder = new MenuHolder(View.TRUST, admin, turretPage, storageKey);
        holder.trustPage = page;
        Inventory inventory = Bukkit.createInventory(holder, 54, "Trusted Players");
        holder.inventory = inventory;

        int start = page * TRUST_PAGE_SIZE;
        int end = Math.min(start + TRUST_PAGE_SIZE, options.size());
        for (int index = start; index < end; index++) {
            TrustOption option = options.get(index);
            int slot = index - start;
            inventory.setItem(slot, item(option.trust ? Material.EMERALD : Material.REDSTONE,
                    (option.trust ? "&aTrust " : "&cUntrust ") + option.playerName,
                    list(option.trust ? "&7Add this online player to trust" : "&7Remove this player from trust")));
            holder.trustOptions.put(slot, option);
        }

        if (page > 0) inventory.setItem(45, item(Material.ARROW, "&ePrevious page", null));
        inventory.setItem(49, item(Material.ARROW, "&eBack to turret settings", null));
        if (page + 1 < pageCount) inventory.setItem(53, item(Material.ARROW, "&eNext page", null));
        player.openInventory(inventory);
    }

    private List<TrustOption> getTrustOptions(Turret turret) {
        List<TrustOption> options = new ArrayList<>();
        Set<String> trusted = new HashSet<>(turret.getTrustedPlayers());
        for (String playerName : trusted) options.add(new TrustOption(playerName, false));
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!turret.isOwnedBy(online.getUniqueId(), online.getName()) && !turret.isTrusted(online)) {
                options.add(new TrustOption(online.getName(), true));
            }
        }
        options.sort(Comparator.comparing(option -> option.playerName.toLowerCase(Locale.ROOT)));
        return options;
    }

    private void openConfirm(Player player, String storageKey, boolean admin, int page) {
        Turret turret = plugin.getTurrets().get(storageKey);
        if (!canAccess(player, turret, admin)) {
            player.closeInventory();
            player.sendMessage(color("&cYou cannot manage that turret."));
            return;
        }

        MenuHolder holder = new MenuHolder(View.CONFIRM_REMOVE, admin, page, storageKey);
        Inventory inventory = Bukkit.createInventory(holder, 27, "Confirm Removal");
        holder.inventory = inventory;
        inventory.setItem(13, item(Material.TNT, "&cRemove turret " + turret.getId() + "?",
                list("&7The owner will receive a turret core.")));
        inventory.setItem(11, item(Material.BARRIER, "&eCancel", null));
        inventory.setItem(15, item(Material.EMERALD, "&aConfirm removal", null));
        player.openInventory(inventory);
    }

    private void openUpgrades(Player player, Turret turret, boolean admin, int page) {
        if (!canAccess(player, turret, admin)) {
            player.closeInventory();
            player.sendMessage(color("&cYou cannot manage that turret."));
            return;
        }
        MenuHolder holder = new MenuHolder(View.UPGRADES, admin, page, turret.getStorageKey());
        Inventory inventory = Bukkit.createInventory(holder, 27, "Turret Upgrades");
        holder.inventory = inventory;
        inventory.setItem(10, upgradeItem(turret, UpgradeType.RANGE));
        inventory.setItem(13, upgradeItem(turret, UpgradeType.MAGAZINE));
        inventory.setItem(16, upgradeItem(turret, UpgradeType.FIRE_RATE));
        inventory.setItem(22, upgradeItem(turret, UpgradeType.ACCURACY));
        inventory.setItem(26, item(Material.ARROW, "&eBack to turret settings", null));
        player.openInventory(inventory);
    }

    private ItemStack upgradeItem(Turret turret, UpgradeType type) {
        String path = "upgrades." + type.configKey;
        String materialName = plugin.getConfig().getString(path + ".material", type.defaultMaterial.name());
        Material material = Material.getMaterial(materialName.toUpperCase(Locale.ROOT));
        if (material == null) material = type.defaultMaterial;
        int level = getUpgradeLevel(turret, type);
        int cost = getUpgradeCost(path, level);
        int maxLevel = Math.max(0, plugin.getConfig().getInt("upgrades.max_level", 5));
        List<String> lore = new ArrayList<>();
        lore.add("&7Level: &f" + level + "/" + maxLevel);
        lore.add("&7Next cost: &f" + cost + " " + material.name());
        lore.add("&7Cost increases with each level");
        if (type == UpgradeType.RANGE) lore.add("&7Effect: +" + Math.max(1, plugin.getConfig().getInt("upgrades.range.blocks_per_level", 2)) + " blocks");
        else if (type == UpgradeType.MAGAZINE) lore.add("&7Effect: +" + Math.max(0, plugin.getConfig().getInt("upgrades.magazine.ammo_per_level", 10)) + " shots");
        else if (type == UpgradeType.FIRE_RATE) lore.add("&7Effect: -" + Math.max(0, plugin.getConfig().getInt("upgrades.fire_rate.ticks_reduction_per_level", 1)) + " ticks per shot");
        else lore.add("&7Effect: -" + plugin.getConfig().getDouble("aiming.spread_reduction_degrees_per_level", 1.5) + " degrees spread");
        if (level >= maxLevel) lore.add("&cMaximum level reached");
        else lore.add("&8Click to purchase");
        return item(material, type.displayName, lore);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof MenuHolder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player)) return;
        if (event.getRawSlot() < 0 || event.getRawSlot() >= top.getSize()) return;

        Player player = (Player) event.getWhoClicked();
        MenuHolder holder = (MenuHolder) top.getHolder();
        if (holder.admin && !player.hasPermission("windturrets.admin")) {
            player.closeInventory();
            return;
        }

        switch (holder.view) {
            case LIST:
                handleListClick(player, holder, event.getRawSlot());
                break;
            case DETAILS:
                handleDetailsClick(player, holder, event.getRawSlot(), event.isShiftClick());
                break;
            case TRUST:
                handleTrustClick(player, holder, event.getRawSlot());
                break;
            case UPGRADES:
                handleUpgradeClick(player, holder, event.getRawSlot());
                break;
            case CONFIRM_REMOVE:
                handleConfirmClick(player, holder, event.getRawSlot());
                break;
            default:
                break;
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuHolder) event.setCancelled(true);
    }

    private void handleListClick(Player player, MenuHolder holder, int slot) {
        if (slot == 45 && holder.page > 0) {
            openList(player, holder.admin, holder.page - 1);
        } else if (slot == 53) {
            openList(player, holder.admin, holder.page + 1);
        } else if (holder.turretKeys.containsKey(slot)) {
            openDetails(player, holder.turretKeys.get(slot), holder.admin, holder.page);
        }
    }

    private void handleDetailsClick(Player player, MenuHolder holder, int slot, boolean shiftClick) {
        Turret turret = plugin.getTurrets().get(holder.turretKey);
        if (!canAccess(player, turret, holder.admin)) {
            player.closeInventory();
            return;
        }
        if (slot == 26) {
            openList(player, holder.admin, holder.page);
        } else if (slot == 10) {
            turret.setEnabled(!turret.isEnabled());
            saveAndRefreshDetails(player, holder, turret);
        } else if (slot == 12) {
            Turret.FireMode[] modes = Turret.FireMode.values();
            turret.setFireMode(modes[(turret.getFireMode().ordinal() + 1) % modes.length]);
            plugin.resetFireState(turret);
            saveAndRefreshDetails(player, holder, turret);
        } else if (slot == 13) {
            Turret.TargetPriority[] priorities = Turret.TargetPriority.values();
            turret.setTargetPriority(priorities[(turret.getTargetPriority().ordinal() + 1) % priorities.length]);
            saveAndRefreshDetails(player, holder, turret);
        } else if (slot == 15) {
            openDetails(player, holder.turretKey, holder.admin, holder.page);
        } else if (slot == 16) {
            openUpgrades(player, turret, holder.admin, holder.page);
        } else if (slot == 20) {
            openTrust(player, holder.turretKey, holder.admin, holder.page, 0);
        } else if (slot == 22) {
            openConfirm(player, holder.turretKey, holder.admin, holder.page);
        }
    }

    private void handleTrustClick(Player player, MenuHolder holder, int slot) {
        if (slot == 45 && holder.trustPage > 0) {
            openTrust(player, holder.turretKey, holder.admin, holder.page, holder.trustPage - 1);
        } else if (slot == 53) {
            openTrust(player, holder.turretKey, holder.admin, holder.page, holder.trustPage + 1);
        } else if (slot == 49) {
            openDetails(player, holder.turretKey, holder.admin, holder.page);
        } else {
            TrustOption option = holder.trustOptions.get(slot);
            Turret turret = plugin.getTurrets().get(holder.turretKey);
            if (option == null || !canAccess(player, turret, holder.admin)) return;
            Player selectedPlayer = Bukkit.getPlayerExact(option.playerName);
            if (option.trust && selectedPlayer != null) turret.trustPlayer(selectedPlayer);
            else turret.untrustPlayer(option.playerName);
            plugin.saveTurrets();
            openTrust(player, holder.turretKey, holder.admin, holder.page, holder.trustPage);
        }
    }

    private void handleUpgradeClick(Player player, MenuHolder holder, int slot) {
        Turret turret = plugin.getTurrets().get(holder.turretKey);
        if (!canAccess(player, turret, holder.admin)) {
            player.closeInventory();
            return;
        }
        if (slot == 26) {
            openDetails(player, holder.turretKey, holder.admin, holder.page);
            return;
        }
        UpgradeType type = UpgradeType.fromSlot(slot);
        if (type == null) return;
        if (!plugin.getConfig().getBoolean("upgrades.enabled", true)) {
            player.sendMessage(color("&cTurret upgrades are disabled."));
            return;
        }

        int level = getUpgradeLevel(turret, type);
        int maxLevel = Math.max(0, plugin.getConfig().getInt("upgrades.max_level", 5));
        if (level >= maxLevel) {
            player.sendMessage(color("&cThat upgrade is already at maximum level."));
            return;
        }

        String path = "upgrades." + type.configKey;
        String materialName = plugin.getConfig().getString(path + ".material", type.defaultMaterial.name());
        Material material = Material.getMaterial(materialName.toUpperCase(Locale.ROOT));
        if (material == null) {
            player.sendMessage(color("&cThe configured upgrade item is invalid: " + materialName));
            return;
        }
        int cost = getUpgradeCost(path, level);
        if (!player.getInventory().containsAtLeast(new ItemStack(material), cost)) {
            player.sendMessage(color("&cYou need " + cost + " " + material.name() + " to buy this upgrade."));
            return;
        }

        if (type == UpgradeType.RANGE) {
            int maximumRadius = Math.max(1, plugin.getConfig().getInt("upgrades.range.maximum_radius", 128));
            int increase = Math.max(1, plugin.getConfig().getInt("upgrades.range.blocks_per_level", 2));
            if (turret.getRadius() >= maximumRadius) {
                player.sendMessage(color("&cThis turret is already at its maximum range."));
                return;
            }
            turret.setRadius(Math.min(maximumRadius, turret.getRadius() + increase));
            turret.upgradeRange(0);
        } else if (type == UpgradeType.MAGAZINE) {
            turret.upgradeMagazine(Math.max(0, plugin.getConfig().getInt("upgrades.magazine.ammo_per_level", 10)));
        } else if (type == UpgradeType.FIRE_RATE) {
            turret.upgradeFireRate();
        } else {
            turret.upgradeAccuracy();
        }

        player.getInventory().removeItem(new ItemStack(material, cost));
        plugin.saveTurrets();
        player.sendMessage(color("&aPurchased " + ChatColor.stripColor(color(type.displayName)) + " at level " + (level + 1) + "."));
        openUpgrades(player, turret, holder.admin, holder.page);
    }

    private void handleConfirmClick(Player player, MenuHolder holder, int slot) {
        if (slot == 11) {
            openDetails(player, holder.turretKey, holder.admin, holder.page);
            return;
        }
        if (slot != 15) return;

        Turret turret = plugin.getTurrets().get(holder.turretKey);
        if (!canAccess(player, turret, holder.admin)) {
            player.closeInventory();
            return;
        }
        Turret removed = plugin.removeTurret(holder.turretKey);
        if (removed == null) {
            player.closeInventory();
            player.sendMessage(color("&cThat turret no longer exists."));
            return;
        }
        boolean refunded = Util.returnTurretCore(plugin, removed);
        player.sendMessage(color(refunded
                ? "&aRemoved turret " + removed.getId() + " and returned its core to " + removed.getOwner() + "."
                : "&cRemoved turret, but its owner's world is unavailable for the core refund."));
        openList(player, holder.admin, holder.page);
    }

    private void saveAndRefreshDetails(Player player, MenuHolder holder, Turret turret) {
        plugin.saveTurrets();
        openDetails(player, turret.getStorageKey(), holder.admin, holder.page);
    }

    private boolean canAccess(Player player, Turret turret, boolean admin) {
        if (turret == null) return false;
        if (admin) return player.hasPermission("windturrets.admin");
        return turret.isOwnedBy(player.getUniqueId(), player.getName());
    }

    private List<Turret> getTurrets(Player player, boolean admin) {
        List<Turret> turrets = new ArrayList<>();
        for (Turret turret : plugin.getTurrets().values()) {
            if (admin || turret.isOwnedBy(player.getUniqueId(), player.getName())) turrets.add(turret);
        }
        turrets.sort(Comparator.comparing(Turret::getOwner, String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(turret -> parseId(turret.getId())));
        return turrets;
    }

    private int parseId(String id) {
        try {
            return Integer.parseInt(id);
        } catch (NumberFormatException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private int getMagazineSize() {
        return Math.max(1, plugin.getConfig().getInt("magazine_size", 30));
    }

    private ItemStack turretListItem(Turret turret, boolean admin) {
        String id = admin ? turret.getOwner() + ":" + turret.getId() : turret.getId();
        List<String> lore = new ArrayList<>();
        lore.add("&7Owner: &f" + turret.getOwner());
        lore.add("&7Mode: &f" + turret.getFireMode().getConfigName());
        lore.add("&7Target priority: &f" + turret.getTargetPriority().getConfigName());
        lore.add("&7Ammo: &f" + turret.getAmmo() + "/" + getMagazineCapacity(turret));
        if (turret.getAmmo() == 0) lore.add("&7Reload in: &e" + turret.getReloadSecondsRemaining() + "s");
        lore.add("&7Radius: &f" + turret.getRadius());
        lore.add(turret.isEnabled() ? "&aEnabled" : "&cDisabled");
        lore.add("&8Click to manage");
        return item(turret.isEnabled() ? Material.DISPENSER : Material.REDSTONE_BLOCK, "&eTurret " + id, lore);
    }

    private ItemStack ammoStatusItem(Turret turret) {
        String status = turret.getAmmo() == 0
                ? "&7Reloads in: &e" + turret.getReloadSecondsRemaining() + " seconds"
                : "&aReady to fire";
        return item(Material.ARROW, "&eAmmo: " + turret.getAmmo() + "/" + getMagazineCapacity(turret),
                list(status, "&8Status refreshes every second"));
    }

    private int getMagazineCapacity(Turret turret) {
        return turret.getMagazineCapacity(getMagazineSize(),
                Math.max(0, plugin.getConfig().getInt("upgrades.magazine.ammo_per_level", 10)));
    }

    private int getUpgradeCost(String path, int currentLevel) {
        long cost = (long) Math.max(1, plugin.getConfig().getInt(path + ".cost", 8)) * (currentLevel + 1L);
        return (int) Math.min(Integer.MAX_VALUE, cost);
    }

    private int getUpgradeLevel(Turret turret, UpgradeType type) {
        if (type == UpgradeType.RANGE) return turret.getRangeUpgradeLevel();
        if (type == UpgradeType.MAGAZINE) return turret.getMagazineUpgradeLevel();
        if (type == UpgradeType.FIRE_RATE) return turret.getFireRateUpgradeLevel();
        return turret.getAccuracyUpgradeLevel();
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(color(name));
        if (lore != null) {
            List<String> coloredLore = new ArrayList<>();
            for (String line : lore) coloredLore.add(color(line));
            meta.setLore(coloredLore);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private List<String> list(String... lines) {
        List<String> result = new ArrayList<>();
        for (String line : lines) result.add(line);
        return result;
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private enum View { LIST, DETAILS, TRUST, UPGRADES, CONFIRM_REMOVE }

    private enum UpgradeType {
        RANGE(10, "range", "&aRange upgrade", Material.IRON_INGOT),
        MAGAZINE(13, "magazine", "&eMagazine upgrade", Material.GOLD_INGOT),
        FIRE_RATE(16, "fire_rate", "&cFire-rate upgrade", Material.REDSTONE),
        ACCURACY(22, "accuracy", "&bAccuracy upgrade", Material.DIAMOND);

        private final int slot;
        private final String configKey;
        private final String displayName;
        private final Material defaultMaterial;

        UpgradeType(int slot, String configKey, String displayName, Material defaultMaterial) {
            this.slot = slot;
            this.configKey = configKey;
            this.displayName = displayName;
            this.defaultMaterial = defaultMaterial;
        }

        private static UpgradeType fromSlot(int slot) {
            for (UpgradeType type : values()) if (type.slot == slot) return type;
            return null;
        }
    }

    private static class TrustOption {
        private final String playerName;
        private final boolean trust;

        private TrustOption(String playerName, boolean trust) {
            this.playerName = playerName;
            this.trust = trust;
        }
    }

    private static class MenuHolder implements InventoryHolder {
        private final View view;
        private final boolean admin;
        private final int page;
        private final String turretKey;
        private final Map<Integer, String> turretKeys = new HashMap<>();
        private final Map<Integer, TrustOption> trustOptions = new HashMap<>();
        private int trustPage;
        private Inventory inventory;

        private MenuHolder(View view, boolean admin, int page, String turretKey) {
            this.view = view;
            this.admin = admin;
            this.page = page;
            this.turretKey = turretKey;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}