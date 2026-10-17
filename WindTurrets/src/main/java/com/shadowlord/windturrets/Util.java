package shadowlord.windturrets;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Util {

    private static final String CORE_MARKER = "windturrets:core:v1";

    public static ItemStack createTurretCore(FileConfiguration cfg) {
        Material mat = Material.getMaterial(cfg.getString("crafting.result_material", "QUARTZ_BLOCK"));
        if (mat == null) mat = Material.QUARTZ_BLOCK;
        ItemStack item = new ItemStack(mat);
        ItemMeta im = item.getItemMeta();
        im.setDisplayName(ChatColor.translateAlternateColorCodes('&', cfg.getString("crafting.result_name", "&bWind Turret Core")));
        im.setLocalizedName(CORE_MARKER);
        List<String> lore = new ArrayList<>();
        for (String l : cfg.getStringList("crafting.result_lore")) lore.add(ChatColor.translateAlternateColorCodes('&', l));
        im.setLore(lore);
        item.setItemMeta(im);
        return item;
    }

    public static boolean isTurretCore(ItemStack item, FileConfiguration cfg) {
        if (item == null) return false;
        Material mat = Material.getMaterial(cfg.getString("crafting.result_material", "QUARTZ_BLOCK"));
        if (mat == null) mat = Material.QUARTZ_BLOCK;
        if (item.getType() != mat) return false;
        if (!item.hasItemMeta()) return false;
        ItemMeta im = item.getItemMeta();
        if (!im.hasDisplayName() || !CORE_MARKER.equals(im.getLocalizedName())) return false;
        String name = ChatColor.stripColor(im.getDisplayName());
        String cfgName = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', cfg.getString("crafting.result_name", "&bWind Turret Core")));
        return name.equals(cfgName);
    }

    public static void consumeOne(Player p, ItemStack item) {
        if (p == null || item == null) return;
        if (item.getAmount() > 1) item.setAmount(item.getAmount() - 1);
        else p.getInventory().removeItem(item);
    }

    public static boolean returnTurretCore(WindTurrets plugin, Turret turret) {
        if (plugin == null || turret == null) return false;
        ItemStack core = createTurretCore(plugin.getConfig());
        Player owner = plugin.getOwnerPlayer(turret);
        if (owner != null) {
            Map<Integer, ItemStack> leftovers = owner.getInventory().addItem(core);
            for (ItemStack leftover : leftovers.values()) {
                owner.getWorld().dropItemNaturally(owner.getLocation(), leftover);
            }
            return true;
        }

        Location location = turret.getLocation();
        if (location == null) return false;
        location.getWorld().dropItemNaturally(location, core);
        return true;
    }

    public static String locString(Location l) {
        if (l == null) return "null";
        return l.getWorld().getName() + ":" + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
    }
}
