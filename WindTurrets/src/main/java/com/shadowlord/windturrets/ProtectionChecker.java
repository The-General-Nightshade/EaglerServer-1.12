package shadowlord.windturrets;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Array;

public class ProtectionChecker {

    private final WindTurrets plugin;
    private boolean warnedAboutWorldGuard;

    public ProtectionChecker(WindTurrets plugin) {
        this.plugin = plugin;
    }

    public boolean allowsPvP(Location location) {
        if (location == null || location.getWorld() == null || !location.getWorld().getPVP()) return false;
        if (!plugin.getConfig().getBoolean("respect_worldguard", true)) return true;

        Plugin worldGuard = Bukkit.getPluginManager().getPlugin("WorldGuard");
        if (worldGuard == null || !worldGuard.isEnabled()) return true;

        try {
            ClassLoader loader = worldGuard.getClass().getClassLoader();
            Class<?> worldGuardClass = Class.forName("com.sk89q.worldguard.bukkit.WorldGuardPlugin", true, loader);
            Object worldGuardInstance = invokeStatic(worldGuardClass, "inst", "getInstance");
            if (worldGuardInstance == null) return failedWorldGuardCheck();

            Method getRegionManager = findMethod(worldGuardClass, "getRegionManager", World.class);
            if (getRegionManager == null) return failedWorldGuardCheck();
            Object regionManager = getRegionManager.invoke(worldGuardInstance, location.getWorld());
            if (regionManager == null) return true;

            Class<?> bukkitUtil = Class.forName("com.sk89q.worldguard.bukkit.BukkitUtil", true, loader);
            Method toVector = findMethod(bukkitUtil, "toVector", Location.class);
            if (toVector == null) return failedWorldGuardCheck();
            Object vector = toVector.invoke(null, location);

            Method getApplicableRegions = findCompatibleMethod(regionManager.getClass(), "getApplicableRegions", vector.getClass());
            if (getApplicableRegions == null) return failedWorldGuardCheck();
            Object regions = getApplicableRegions.invoke(regionManager, vector);

            Class<?> defaultFlag = Class.forName("com.sk89q.worldguard.protection.flags.DefaultFlag", true, loader);
            Field pvpField = defaultFlag.getField("PVP");
            Object pvpFlag = pvpField.get(null);
            Method queryState = findQueryStateMethod(regions.getClass());
            if (queryState == null) return failedWorldGuardCheck();
            Object flags = Array.newInstance(queryState.getParameterTypes()[1].getComponentType(), 1);
            Array.set(flags, 0, pvpFlag);
            Object state = queryState.invoke(regions, new Object[]{null, flags});
            return state == null || !"DENY".equals(state.toString());
        } catch (Exception exception) {
            return failedWorldGuardCheck();
        }
    }

    private Object invokeStatic(Class<?> type, String... methodNames) throws Exception {
        for (String methodName : methodNames) {
            try {
                Method method = type.getMethod(methodName);
                return method.invoke(null);
            } catch (NoSuchMethodException ignored) {}
        }
        return null;
    }

    private Method findMethod(Class<?> type, String name, Class<?> parameterType) {
        try { return type.getMethod(name, parameterType); }
        catch (NoSuchMethodException ignored) { return null; }
    }

    private Method findCompatibleMethod(Class<?> type, String name, Class<?> argumentType) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterTypes().length == 1
                    && method.getParameterTypes()[0].isAssignableFrom(argumentType)) return method;
        }
        return null;
    }

    private Method findQueryStateMethod(Class<?> type) {
        for (Method method : type.getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (method.getName().equals("queryState") && parameters.length == 2 && parameters[1].isArray()) return method;
        }
        return null;
    }

    private boolean failedWorldGuardCheck() {
        if (!warnedAboutWorldGuard) {
            plugin.getLogger().warning("Could not query WorldGuard PvP flags; turrets will not target players until the integration is available or respect_worldguard is disabled.");
            warnedAboutWorldGuard = true;
        }
        return false;
    }
}