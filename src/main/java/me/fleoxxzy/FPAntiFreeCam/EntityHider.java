package me.fleoxxzy.FPAntiFreeCam;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hides non-player entities that are inside the hidden zone (at or below
 * void-y) from players whose FreeCam protection is currently active.
 *
 * Uses the vanilla {@link Player#hideEntity}/{@link Player#showEntity} API
 * which works on all target versions (1.19+).
 */
public final class EntityHider implements Listener {

    private final Plugin        plugin;
    private final FPAntiFreeCam main;

    /**
     * Stable pair-key set: UUID.nameUUIDFromBytes(playerUUID + ":" + entityUUID).
     * Tracks which player↔entity pairs are currently hidden.
     */
    private final Set<UUID> hidden = ConcurrentHashMap.newKeySet();

    private boolean enabled  = true;
    private int     voidY    = 15;

    public EntityHider(FPAntiFreeCam main) {
        this.plugin = main;
        this.main   = main;
        loadSettings();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    // ── Settings ──────────────────────────────────────────────────────────

    public void loadSettings() {
        enabled  = plugin.getConfig().getBoolean("entities.hide-entities", true);
        voidY    = plugin.getConfig().getInt("protection.void-y", 15);
    }

    public boolean isEnabled() { return enabled; }

    // ── Core logic ────────────────────────────────────────────────────────

    /**
     * Called whenever a player's FreeCam-protection state changes.
     * Shows or hides nearby underground entities accordingly.
     */
    public void updateFor(Player player) {
        if (!enabled) return;
        if (main.isProtectionActive(player)) {
            hideUndergroundEntities(player);
        } else {
            showAllEntities(player);
        }
    }

    @EventHandler
    public void onEntitySpawn(EntitySpawnEvent event) {
        if (!enabled) return;
        Entity entity = event.getEntity();
        if (entity instanceof Player) return;
        if (entity.getLocation().getY() > voidY) return;

        PlatformUtil.runTaskLater(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (shouldHideEntityFrom(player, entity)) {
                    hideFrom(player, entity);
                }
            }
        }, 1L);
    }

    // ── Cleanup ───────────────────────────────────────────────────────────

    public void cleanupPlayer(UUID id) {
        hidden.removeIf(key -> key.toString().startsWith(id.toString()));
    }

    public void refreshAll() {
        for (Player p : Bukkit.getOnlinePlayers()) updateFor(p);
    }

    public String stats() {
        return "hidden-pairs:" + hidden.size();
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private void hideUndergroundEntities(Player player) {
        PlatformUtil.runTask(plugin, () -> {
            for (Entity e : getNearby(player)) {
                if (shouldHideEntityFrom(player, e)) hideFrom(player, e);
            }
        });
    }

    private void showAllEntities(Player player) {
        PlatformUtil.runTask(plugin, () -> {
            for (Entity e : getNearby(player)) showTo(player, e);
        });
    }

    private boolean shouldHideEntityFrom(Player player, Entity entity) {
        if (!enabled) return false;
        if (!main.isProtectionActive(player)) return false;
        return entity.getLocation().getY() <= voidY;
    }

    private void hideFrom(Player player, Entity entity) {
        UUID key = pairKey(player.getUniqueId(), entity.getUniqueId());
        if (hidden.contains(key)) return;
        try {
            player.hideEntity(plugin, entity);
            hidden.add(key);
        } catch (Exception e) {
            plugin.getLogger().fine("[FPAntiFreeCam] hideEntity failed: " + e.getMessage());
        }
    }

    private void showTo(Player player, Entity entity) {
        UUID key = pairKey(player.getUniqueId(), entity.getUniqueId());
        if (!hidden.contains(key)) return;
        try {
            player.showEntity(plugin, entity);
            hidden.remove(key);
        } catch (Exception e) {
            plugin.getLogger().fine("[FPAntiFreeCam] showEntity failed: " + e.getMessage());
        }
    }

    private List<Entity> getNearby(Player player) {
        List<Entity> result = new ArrayList<>();
        try {
            int viewDist;
            try { viewDist = player.getClientViewDistance(); }
            catch (Exception e) { viewDist = Bukkit.getViewDistance(); }
            double   radius = viewDist * 16.0;
            Location center = player.getLocation();
            result.addAll(center.getWorld().getNearbyEntities(center, radius, radius, radius));
            result.removeIf(e -> e instanceof Player);
        } catch (Exception e) {
            plugin.getLogger().warning("[FPAntiFreeCam] getNearbyEntities failed: " + e.getMessage());
        }
        return result;
    }

    private static UUID pairKey(UUID player, UUID entity) {
        return UUID.nameUUIDFromBytes((player + ":" + entity).getBytes());
    }
}
