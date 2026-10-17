package shadowlord.windturrets;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TurretCommand implements CommandExecutor {

    private final WindTurrets plugin;

    public TurretCommand(WindTurrets plugin) {
        this.plugin = plugin;
    }

    private String c(String s) { return ChatColor.translateAlternateColorCodes('&', s); }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(c("&6Turret commands:"));
            sender.sendMessage(c("&e/turret givecore &7- issue a core (admin only)"));
            sender.sendMessage(c("&e/turret gui &7- manage your turrets"));
            sender.sendMessage(c("&e/turret reload &7- reload and validate configuration"));
            sender.sendMessage(c("&e/turret list &7- list your turrets"));
            sender.sendMessage(c("&e/turret remove <id> &7- remove your turret and reclaim its core"));
            sender.sendMessage(c("&e/turret info <id> &7- show turret info"));
            sender.sendMessage(c("&e/turret <id> type <automatic|semi-automatic|single> &7- change firing mode"));
            sender.sendMessage(c("&e/turret <id> trust <player> &7- trust a player"));
            sender.sendMessage(c("&e/turret <id> untrust <player> &7- remove a trusted player"));
            if (sender.hasPermission("windturrets.admin")) {
                sender.sendMessage(c("&7Admins can specify another player's turret as <owner>:<id>."));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("gui")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(c("&cOnly players can open the turret GUI."));
                return true;
            }
            plugin.getTurretGUI().openPlayerList((Player) sender, 0);
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("windturrets.admin")) {
                sender.sendMessage(c("&cNo permission."));
                return true;
            }
            List<String> warnings = plugin.reloadPluginConfig();
            sender.sendMessage(c(warnings.isEmpty()
                    ? "&aWindTurrets configuration reloaded with no corrections."
                    : "&eWindTurrets configuration reloaded; " + warnings.size() + " invalid setting(s) were corrected. Check the server log."));
            return true;
        }

        if (args.length > 1 && args[1].equalsIgnoreCase("type")) {
            if (args.length < 3) {
                sender.sendMessage(c("&cUsage: /turret <id> type <automatic|semi-automatic|single>"));
                return true;
            }
            Turret turret = findTurret(sender, args[0]);
            if (turret == null) {
                sendLookupFailure(sender, args[0]);
                return true;
            }
            if (!canManageTurret(sender, turret)) {
                sender.sendMessage(c("&cOnly the turret owner or an admin can change its type."));
                return true;
            }
            Turret.FireMode fireMode = Turret.FireMode.parse(args[2]);
            if (fireMode == null) {
                sender.sendMessage(c("&cType must be automatic, semi-automatic, or single."));
                return true;
            }
            turret.setFireMode(fireMode);
            plugin.resetFireState(turret);
            plugin.saveTurrets();
            sender.sendMessage(c("&aTurret " + turret.getId() + " firing mode set to " + fireMode.getConfigName() + "."));
            return true;
        }

        if (args.length > 1 && (args[1].equalsIgnoreCase("trust") || args[1].equalsIgnoreCase("untrust"))) {
            boolean trust = args[1].equalsIgnoreCase("trust");
            if (args.length < 3) {
                sender.sendMessage(c("&cUsage: /turret <id> " + (trust ? "trust" : "untrust") + " <player>"));
                return true;
            }

            Turret turret = findTurret(sender, args[0]);
            if (turret == null) {
                sendLookupFailure(sender, args[0]);
                return true;
            }
            if (!canManageTurret(sender, turret)) {
                sender.sendMessage(c("&cOnly the turret owner or an admin can change its trusted players."));
                return true;
            }

            String playerName = args[2];
            if (!playerName.matches("[A-Za-z0-9_]{1,16}")) {
                sender.sendMessage(c("&cEnter a valid Minecraft player name."));
                return true;
            }

            Player trustedPlayer = org.bukkit.Bukkit.getPlayerExact(playerName);
            boolean changed = trust
                    ? (trustedPlayer == null ? turret.trustPlayer(playerName) : turret.trustPlayer(trustedPlayer))
                    : (trustedPlayer == null ? turret.untrustPlayer(playerName) : turret.untrustPlayer(trustedPlayer));
            if (changed) {
                plugin.saveTurrets();
                sender.sendMessage(c("&a" + (trust ? "Trusted " : "Untrusted ") + playerName + " for turret " + turret.getId() + "."));
            } else {
                sender.sendMessage(c("&e" + playerName + (trust ? " is already trusted." : " is not trusted.")));
            }
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("givecore")) {
            if (!sender.hasPermission("windturrets.admin")) {
                sender.sendMessage(c("&cOnly admins can issue turret cores."));
                return true;
            }
            if (!(sender instanceof Player)) {
                sender.sendMessage(c("&cOnly players can receive items."));
                return true;
            }
            Player p = (Player) sender;
            ItemStack core = Util.createTurretCore(plugin.getConfig());
            p.getInventory().addItem(core);
            p.sendMessage(c("&aGiven a Wind Turret Core."));
            return true;
        }

        if (sub.equals("list")) {
            boolean showAll = sender.hasPermission("windturrets.admin");
            String owner = sender instanceof Player ? ((Player) sender).getName() : null;
            sender.sendMessage(c(showAll ? "&6Configured turrets:" : "&6Your turrets:"));
            int count = 0;
            for (Turret t : plugin.getTurrets().values()) {
                if (!showAll && (owner == null || !(sender instanceof Player)
                    || !t.isOwnedBy(((Player) sender).getUniqueId(), owner))) continue;
                String id = showAll ? t.getOwner() + ":" + t.getId() : t.getId();
                sender.sendMessage(c("&e- " + id + " mode=" + t.getFireMode().getConfigName()
                    + " ammo=" + t.getAmmo() + " enabled=" + t.isEnabled() + " radius=" + t.getRadius()));
                count++;
            }
            if (count == 0) sender.sendMessage(c("&e(none)"));
            return true;
        }

        if (sub.equals("remove")) {
            if (args.length < 2) {
                sender.sendMessage(c("&cUsage: /turret remove <id>"));
                return true;
            }
            String id = args[1];
            Turret turret = findTurret(sender, id);
            if (turret == null) {
                sendLookupFailure(sender, id);
                return true;
            }
            if (!canManageTurret(sender, turret)) {
                sender.sendMessage(c("&cOnly the turret owner or an admin can remove it."));
                return true;
            }
            Turret removed = plugin.removeTurret(turret.getStorageKey());
            if (removed == null) {
                sender.sendMessage(c("&cNo such turret."));
                return true;
            }
                boolean refunded = Util.returnTurretCore(plugin, removed);
                sender.sendMessage(c(refunded
                    ? "&aRemoved turret " + removed.getId() + " and returned its core to " + removed.getOwner() + "."
                    : "&cRemoved turret, but its owner's world is unavailable for the core refund."));
            return true;
        }

        if (sub.equals("info")) {
            if (args.length < 2) {
                sender.sendMessage(c("&cUsage: /turret info <id>"));
                return true;
            }
            String id = args[1];
            Turret t = findTurret(sender, id);
            if (t == null) { sender.sendMessage(c("&cNo such turret.")); return true; }
            sender.sendMessage(c("&6Turret " + id));
            sender.sendMessage(c("&eOwner: &f" + t.getOwner()));
            sender.sendMessage(c("&eLoc: &f" + (t.getLocation() != null ? Util.locString(t.getLocation()) : "null")));
            sender.sendMessage(c("&eRadius: &f" + t.getRadius()));
            sender.sendMessage(c("&eEnabled: &f" + t.isEnabled()));
            sender.sendMessage(c("&eType: &f" + t.getFireMode().getConfigName()));
            sender.sendMessage(c("&eAmmo: &f" + t.getAmmo() + "/" + Math.max(1, plugin.getConfig().getInt("magazine_size", 30))));
            sender.sendMessage(c("&eTrusted: &f" + t.getTrustedPlayers()));
            return true;
        }

        if (sub.equals("toggle")) {
            if (!sender.hasPermission("windturrets.admin")) {
                sender.sendMessage(c("&cNo permission."));
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(c("&cUsage: /turret toggle <id>"));
                return true;
            }
            String id = args[1];
            Turret t = findTurret(sender, id);
            if (t == null) { sender.sendMessage(c("&cNo such turret.")); return true; }
            t.setEnabled(!t.isEnabled());
            plugin.saveTurrets();
            sender.sendMessage(c("&aTurret " + id + " enabled=" + t.isEnabled()));
            return true;
        }

        sender.sendMessage(c("&cUnknown subcommand."));
        return true;
    }

    private boolean canManageTurret(CommandSender sender, Turret turret) {
        return sender.hasPermission("windturrets.admin")
                || (sender instanceof Player && turret.isOwnedBy(((Player) sender).getUniqueId(), ((Player) sender).getName()));
    }

    private Turret findTurret(CommandSender sender, String id) {
        if (id.contains(":")) {
            String[] parts = id.split(":", -1);
            if (parts.length != 2 || !sender.hasPermission("windturrets.admin")) return null;
            return plugin.getTurret(parts[0], parts[1]);
        }

        if (sender instanceof Player) {
            Player player = (Player) sender;
            Turret owned = plugin.getTurret(player.getUniqueId(), player.getName(), id);
            if (owned != null) return owned;
        }
        if (sender.hasPermission("windturrets.admin")) {
            List<Turret> matches = findTurretsById(id);
            if (matches.size() == 1) return matches.get(0);
        }
        return null;
    }

    private List<Turret> findTurretsById(String id) {
        List<Turret> matches = new ArrayList<>();
        for (Turret turret : plugin.getTurrets().values()) {
            if (turret.getId().equals(id)) matches.add(turret);
        }
        return matches;
    }

    private void sendLookupFailure(CommandSender sender, String id) {
        if (sender.hasPermission("windturrets.admin") && !id.contains(":")) {
            List<Turret> matches = findTurretsById(id);
            if (matches.size() > 1) {
                sender.sendMessage(c("&cThat ID belongs to multiple players. Use <owner>:<id>."));
                return;
            }
        }
        sender.sendMessage(c("&cNo such turret."));
    }

}
