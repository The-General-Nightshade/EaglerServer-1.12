# ShadowSpears

ShadowSpears adds craftable, tiered spear weapons to the repository's Spigot 1.12.2 server.
Build the plugin with `mvn package` from this directory and place
`target/ShadowSpears-1.0.0.jar` in the server's `plugins` folder.

Spears are crafted from a tier material and two sticks, and can also be issued by an
administrator with `/spear give <wood|stone|gold|iron|diamond> [player]`. Jab attacks
have a 4.5-block maximum reach, a 2-block minimum reach, and a tier-specific cooldown.
Right-click a spear to ready a charge, then hit a target while moving toward it; charge
damage scales with relative approach speed and fast charges knock targets back.

Minecraft 1.12.2 has no spear item, so the plugin identifies its custom spears by lore
and uses the corresponding sword item as the inventory icon. Copper and netherite
tiers are not available on this server version. Because the 1.12 Bukkit API does not
expose the newer spear's use animation, charging is represented by right-clicking
before the hit and expires after the configured timeout.
