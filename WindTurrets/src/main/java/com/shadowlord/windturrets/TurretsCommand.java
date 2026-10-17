package shadowlord.windturrets;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class TurretsCommand implements CommandExecutor {

    private final WindTurrets plugin;

    public TurretsCommand(WindTurrets plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(color("&cOnly players can open the admin GUI."));
            return true;
        }
        if (!sender.hasPermission("windturrets.admin")) {
            sender.sendMessage(color("&cYou do not have permission to use the admin GUI."));
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("gui")) {
            plugin.getTurretGUI().openAdminList((Player) sender, 0);
            return true;
        }
        sender.sendMessage(color("&eUsage: /turrets admin gui"));
        return true;
    }

    private String color(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }
}