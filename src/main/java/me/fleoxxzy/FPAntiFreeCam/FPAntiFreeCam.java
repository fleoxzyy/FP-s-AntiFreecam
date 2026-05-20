package me.fleoxxzy.FPAntiFreeCam;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.generator.WorldInfo;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.StringUtil;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * FPAntiFreeCam – Main plugin class.
 *
 * Anti-FreeCam: when a player is at or above the configured
 * surface-y threshold, all blocks/entities at or below void-y are replaced
 * with air in outbound packets so FreeCam mods see only void.
 *
 * Compatible with Spigot / Paper / Folia, MC 1.19 through 26.1+.
 */
public final class FPAntiFreeCam extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    // ── Singleton ─────────────────────────────────────────────────────────
    private static FPAntiFreeCam instance;
    public static FPAntiFreeCam getInstance() { return instance; }

    // ── Runtime player-state maps ─────────────────────────────────────────
    /**
     * true  = protection active, underground blocks hidden from this player.
     * false = player is underground or has bypass; full view is shown.
     */
    public final Map<UUID, Boolean> playerHiddenState  = new ConcurrentHashMap<>();
    /** Epoch ms when each player's refresh cooldown expires. */
    private final Map<UUID, Long>   refreshCooldowns   = new ConcurrentHashMap<>();
    /**
     * Players currently being re-teleported by us to avoid infinite loops
     * in the teleport event handler.
     */
    private final Set<UUID> internallyTeleporting      = ConcurrentHashMap.newKeySet();

    // ── Config-driven values ──────────────────────────────────────────────
    private WrappedBlockState replacementBlockState;
    private int               replacementBlockId    = 0;
    private String            replacementBlockType  = "minecraft:air";

    private boolean debugMode           = false;
    private int     refreshCooldownMs   = 3_000;

    /** Y level at-or-above which the hiding effect is armed. */
    private double surfaceY = 16.0;
    /** Blocks at-or-below this Y are hidden while the effect is active. */
    private int    voidY    = 15;

    private Set<String> protectedWorlds = ConcurrentHashMap.newKeySet();

    private boolean limitedAreaEnabled = false;
    private int     limitedAreaRadius  = 4;
    private boolean instantProtection        = true;
    private int     instantRadius            = 14;
    private int     preLoadDistance          = 10;
    private boolean forceImmediateRefresh    = true;

    // ── Language config ───────────────────────────────────────────────────
    private FileConfiguration langConfig;
    private String            currentLanguage = "en";

    // ── Sub-systems ───────────────────────────────────────────────────────
    private FoliaScheduler  foliaScheduler;
    private PaperScheduler  paperScheduler;
    private BedrockSupport  bedrockSupport;
    private EntityHider     entityHider;

    // ═════════════════════════════════════════════════════════════════════
    //  JavaPlugin lifecycle
    // ═════════════════════════════════════════════════════════════════════

    @Override
    public void onLoad() {
        instance = this;
        getLogger().info("onLoad() – building PacketEvents…");

        // PacketEvents MUST be built and loaded during onLoad, before onEnable.
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEventsAPI<?> api = PacketEvents.getAPI();
        if (api == null) {
            getLogger().severe("[FPAntiFreeCam] PacketEvents API is null after build – aborting load.");
            return;
        }
        
        api.getSettings()
                .checkForUpdates(false)
                .bStats(true);
        
        api.load();
        if (!api.isLoaded()) {
            getLogger().severe("[FPAntiFreeCam] PacketEvents failed to load – plugin will be disabled.");
        }
    }

    @Override
    public void onEnable() {
        // ── Banner ─────────────────────────────────────────────────────────
        ChatUtil.printBanner(ChatUtil.startupBanner(
                getDescription().getVersion(),
                PlatformUtil.getPlatformName()));

        // ── Validate PacketEvents ──────────────────────────────────────────
        PacketEventsAPI<?> api = PacketEvents.getAPI();
        if (api == null || !api.isLoaded()) {
            getLogger().severe(lang("error.packetevents-unavailable"));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // ── Config + language ──────────────────────────────────────────────
        loadConfigValues();

        // ── Sub-systems ────────────────────────────────────────────────────
        bedrockSupport = new BedrockSupport(this);
        entityHider    = new EntityHider(this);

        if (FoliaScheduler.shouldUse()) {
            foliaScheduler = new FoliaScheduler(this);
            getLogger().info("[FPAntiFreeCam] Folia region-aware scheduler enabled.");
        } else {
            paperScheduler = new PaperScheduler(this);
            getLogger().info("[FPAntiFreeCam] Paper/Spigot tick-batching scheduler enabled.");
        }

        // ── Initialise replacement block ───────────────────────────────────
        if (!initReplacementBlock()) {
            getLogger().severe(lang("error.block-state-null"));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // ── Register packet listener ───────────────────────────────────────
        api.getEventManager().registerListener(new ChunkListener(this), PacketListenerPriority.NORMAL);

        // PacketEvents.init() must be called on the main thread after
        // all packet listeners have been registered.
        api.init();

        // ── Register Bukkit events + commands ──────────────────────────────
        getServer().getPluginManager().registerEvents(this, this);
        registerCommands();

        // ── Handle already-online players (e.g. after /reload) ────────────
        try {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (isWorldProtected(p.getWorld().getName())) {
                    handlePlayerInitialState(p, /* immediateRefresh= */ false);
                }
            }
        } catch (Exception e) {
            getLogger().severe("[FPAntiFreeCam] Error scanning online players on enable: " + e.getMessage());
        }

        getLogger().info("[FPAntiFreeCam] Enabled. Protected worlds: " + protectedWorlds);
    }

    @Override
    public void onDisable() {
        ChatUtil.printBanner(ChatUtil.shutdownBanner());

        // Restore normal view for all players before we stop intercepting packets.
        if (entityHider != null) {
            entityHider.refreshAll();
        }

        if (paperScheduler != null) {
            paperScheduler.shutdown();
        }

        PacketEventsAPI<?> api = PacketEvents.getAPI();
        if (api != null && api.isLoaded()) {
            api.terminate();
        }

        playerHiddenState.clear();
        refreshCooldowns.clear();
        internallyTeleporting.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Public API – consumed by ChunkListener, EntityHider, schedulers, etc.
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Returns true when the given world name is in the protected worlds list.
     * Null-safe.
     */
    public boolean isWorldProtected(String worldName) {
        return worldName != null && protectedWorlds.contains(worldName);
    }

    /**
     * Returns true when the player should currently receive the void-blocks
     * treatment: they are in a protected world, above surface-y, and do NOT
     * hold the bypass permission.
     */
    public boolean isProtectionActive(Player player) {
        if (player == null) return false;
        if (player.hasPermission("fpantifreecam.bypass")) return false;
        return Boolean.TRUE.equals(playerHiddenState.get(player.getUniqueId()));
    }

    /** The WrappedBlockState that replaces hidden blocks in outbound packets. */
    public WrappedBlockState getReplacementBlock() { return replacementBlockState; }

    /** Global numeric ID of the replacement block (fast equality in packet handlers). */
    public int getReplacementBlockId() { return replacementBlockId; }

    /** The Y boundary: blocks at-or-below this value are hidden. */
    public int getVoidY() { return voidY; }

    /** Logs a debug message if debug mode is enabled. */
    public void dbg(String message) {
        if (debugMode) {
            getLogger().info("[FPAntiFreeCam DEBUG] " + message);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Config & language loading
    // ═════════════════════════════════════════════════════════════════════

    private void loadConfigValues() {
        saveDefaultConfig();
        checkConfigVersion();
        reloadConfig();
        FileConfiguration cfg = getConfig();

        debugMode         = cfg.getBoolean("settings.debug-mode", false);
        refreshCooldownMs = cfg.getInt("settings.refresh-cooldown-seconds", 3) * 1_000;
        surfaceY          = cfg.getDouble("protection.surface-y", 16.0);
        voidY             = cfg.getInt("protection.void-y", 15);

        String rawBlock = cfg.getString("replacement.block-type", "air");
        replacementBlockType = rawBlock.startsWith("minecraft:") ? rawBlock : "minecraft:" + rawBlock;

        protectedWorlds.clear();
        List<String> worldList = cfg.getStringList("worlds.list");
        if (worldList != null) protectedWorlds.addAll(worldList);

        limitedAreaEnabled    = cfg.getBoolean("performance.limited-area.enabled", false);
        limitedAreaRadius     = cfg.getInt("performance.limited-area.chunk-radius", 4);
        instantProtection     = cfg.getBoolean("performance.instant-protection.enabled", true);
        instantRadius         = cfg.getInt("performance.instant-protection.instant-load-radius", 14);
        preLoadDistance       = cfg.getInt("performance.instant-protection.pre-load-distance", 10);
        forceImmediateRefresh = cfg.getBoolean("performance.instant-protection.force-immediate-refresh", true);

        loadLanguageConfig(cfg.getString("settings.language", "en"));

        if (entityHider != null) entityHider.loadSettings();

        dbg("Config loaded – worlds=" + protectedWorlds
                + " surfaceY=" + surfaceY
                + " voidY=" + voidY
                + " block=" + replacementBlockType);
    }

    private void loadLanguageConfig(String language) {
        currentLanguage = language;
        File langDir  = new File(getDataFolder(), "lang");
        File langFile = new File(langDir, language + ".yml");

        if (!langDir.exists()) langDir.mkdirs();
        if (!langFile.exists()) {
            // Try to save from jar resources
            try { saveResource("lang/" + language + ".yml", false); }
            catch (Exception ignored) {
                // Language file not bundled – fall back to en
                try { saveResource("lang/en.yml", false); }
                catch (Exception ignored2) {}
            }
        }

        langConfig = YamlConfiguration.loadConfiguration(langFile);

        // Merge defaults from jar so missing keys fall back gracefully
        InputStream defStream = getResource("lang/" + language + ".yml");
        if (defStream == null) defStream = getResource("lang/en.yml");
        if (defStream != null) {
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defStream, StandardCharsets.UTF_8));
            langConfig.setDefaults(defaults);
        }

        dbg("Language loaded: " + language);
    }

    /**
     * Resolves a message key from the current language file, fills in {0}…{N}
     * placeholders and translates '&amp;' colour codes.
     */
    public String lang(String key, Object... args) {
        String msg = (langConfig != null)
                ? langConfig.getString("messages." + key, key)
                : key;
        for (int i = 0; i < args.length; i++) {
            msg = msg.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return ChatUtil.color(msg);
    }

    // ── Block-state initialisation ────────────────────────────────────────

    /**
     * Tries the configured block type first, then falls back through sensible
     * defaults. Returns false only if every candidate fails.
     */
    private boolean initReplacementBlock() {
        String[] candidates = { replacementBlockType, "minecraft:air", "minecraft:stone", "minecraft:dirt" };
        for (String candidate : candidates) {
            try {
                WrappedBlockState state = WrappedBlockState.getByString(candidate);
                if (state != null) {
                    replacementBlockState = state;
                    replacementBlockId    = state.getGlobalId();
                    replacementBlockType  = candidate;
                    dbg("Replacement block → " + candidate + " (globalId=" + replacementBlockId + ")");
                    return true;
                }
            } catch (Exception e) {
                getLogger().warning("[FPAntiFreeCam] Block candidate '" + candidate + "' failed: " + e.getMessage());
            }
        }
        return false;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Player state management
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Evaluates whether the player should be in the hidden or visible state
     * based on their current Y and world, then optionally triggers a chunk
     * refresh so the change is applied immediately.
     *
     * @param immediateRefresh when true, forces a full chunk refresh right away.
     */
    public void handlePlayerInitialState(Player player, boolean immediateRefresh) {
        if (!isWorldProtected(player.getWorld().getName())) {
            // Player is in an unprotected world – clear any lingering state.
            boolean wasCached = playerHiddenState.remove(player.getUniqueId()) != null;
            if (wasCached && immediateRefresh) refreshFullView(player);
            return;
        }

        boolean bypass     = player.hasPermission("fpantifreecam.bypass");
        boolean shouldHide = !bypass && player.getLocation().getY() >= surfaceY;
        playerHiddenState.put(player.getUniqueId(), shouldHide);
        dbg("InitialState " + player.getName() + " hidden=" + shouldHide
                + " Y=" + String.format("%.1f", player.getLocation().getY()));

        if (entityHider != null) entityHider.updateFor(player);

        if (shouldHide || immediateRefresh) refreshFullView(player);
    }

    /** Refreshes all chunks in the server view-distance around the player. */
    public void refreshFullView(Player player) {
        int radius = Bukkit.getViewDistance();
        if (limitedAreaEnabled) radius = Math.min(radius, limitedAreaRadius);
        if (bedrockSupport  != null) radius = bedrockSupport.optimisedRadius(player, radius);
        dbg("refreshFullView → " + player.getName() + " radius=" + radius);
        performRefresh(player, radius);
    }

    private void performRefresh(Player player, int radius) {
        if (!player.isOnline()) return;
        if (!isWorldProtected(player.getWorld().getName())) return;

        // On Folia, make sure we're on the correct region thread before calling
        // world.refreshChunk(); if not, re-schedule to the right region.
        if (!PlatformUtil.isOwnedByCurrentRegion(player.getLocation())) {
            final int fr = radius;
            PlatformUtil.runTask(this, player.getLocation(), () -> performRefresh(player, fr));
            return;
        }

        if (foliaScheduler != null) {
            foliaScheduler.refreshChunks(player, radius);
        } else if (paperScheduler != null) {
            paperScheduler.refreshChunks(player, radius);
        } else {
            // Bare-minimum fallback (single-threaded, synchronous)
            World  world = player.getWorld();
            int    px    = player.getLocation().getBlockX() >> 4;
            int    pz    = player.getLocation().getBlockZ() >> 4;
            for (int cx = px - radius; cx <= px + radius; cx++) {
                for (int cz = pz - radius; cz <= pz + radius; cz++) {
                    try { world.refreshChunk(cx, cz); } catch (Exception ignored) {}
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Bukkit event handlers
    // ═════════════════════════════════════════════════════════════════════

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        dbg("onPlayerJoin: " + player.getName() + " world=" + player.getWorld().getName());
        if (isWorldProtected(player.getWorld().getName())) {
            handlePlayerInitialState(player, /* immediateRefresh= */ false);
        }
        // Bedrock support: early detection so the cache is warm for later calls.
        if (bedrockSupport != null && bedrockSupport.isBedrock(player)) {
            dbg("Bedrock player joined: " + player.getName());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        playerHiddenState.remove(id);
        refreshCooldowns.remove(id);
        internallyTeleporting.remove(id);
        if (foliaScheduler != null) foliaScheduler.cleanupPlayer(id);
        if (paperScheduler != null) paperScheduler.cleanupPlayer(id);
        if (bedrockSupport != null) bedrockSupport.cleanupPlayer(id);
        if (entityHider    != null) entityHider.cleanupPlayer(id);
        dbg("Cleaned up quit player: " + event.getPlayer().getName());
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player  = event.getPlayer();
        String toWorld = player.getWorld().getName();
        dbg("WorldChange: " + player.getName() + " → " + toWorld);

        if (isWorldProtected(toWorld)) {
            // Entered a protected world; force a full refresh to apply hiding immediately.
            handlePlayerInitialState(player, /* immediateRefresh= */ true);
        } else {
            // Left a protected world; restore the full view.
            boolean wasHidden = playerHiddenState.remove(player.getUniqueId()) != null;
            if (wasHidden) refreshFullView(player);
        }
    }

    /**
     * Intercepts player teleports to prevent a momentary "void glimpse" when
     * going from a hiding state to a visible one.  If the player is teleporting
     * out of hiding we cancel the original event, update the state, then
     * re-fire the teleport ourselves (tracked via {@code internallyTeleporting}).
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (internallyTeleporting.contains(player.getUniqueId())) return;

        var to   = event.getTo();
        var from = event.getFrom();
        if (to == null) return;

        boolean toProtected   = isWorldProtected(to.getWorld().getName());
        boolean fromProtected = isWorldProtected(from.getWorld().getName());

        if (!toProtected) {
            // Teleporting into an unprotected world – clear state and restore view.
            if (fromProtected && playerHiddenState.remove(player.getUniqueId()) != null) {
                final var dest = to;
                PlatformUtil.runTask(this, dest, () -> {
                    if (player.isOnline()) refreshFullView(player);
                });
            }
            return;
        }

        // Both worlds are protected (or only destination is).
        UUID    id        = player.getUniqueId();
        boolean bypass    = player.hasPermission("fpantifreecam.bypass");
        boolean oldHidden = Boolean.TRUE.equals(playerHiddenState.getOrDefault(id, to.getY() >= surfaceY));
        boolean newHidden = !bypass && to.getY() >= surfaceY;

        if (oldHidden == newHidden) return; // No state transition – nothing to do.

        if (!newHidden) {
            // Was hiding → now visible.  Cancel + re-teleport to avoid the void flash.
            playerHiddenState.put(id, false);
            event.setCancelled(true);
            final var dest = to;
            PlatformUtil.runTask(this, dest, () -> {
                if (!player.isOnline()) return;
                internallyTeleporting.add(id);
                try {
                    player.teleport(dest);
                } finally {
                    internallyTeleporting.remove(id);
                }
            });
        } else {
            // Was visible → now hiding.  Update state immediately so the packet
            // listener starts replacing blocks from the first new chunk send.
            playerHiddenState.put(id, true);
        }
    }

    /**
     * Detects Y-level transitions across the surface-y threshold and toggles
     * the protection state, triggering a chunk refresh as needed.
     */
    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        var to   = event.getTo();
        var from = event.getFrom();
        if (to == null) return;

        Player player    = event.getPlayer();
        String worldName = player.getWorld().getName();

        // Left the protected world during this move tick (edge case on some platforms).
        if (!isWorldProtected(worldName)) {
            if (playerHiddenState.remove(player.getUniqueId()) != null) {
                refreshFullView(player);
            }
            return;
        }

        // Skip if the player hasn't crossed a block boundary on the Y axis –
        // this filters ~95 % of move events at essentially zero cost.
        if (from.getBlockY() == to.getBlockY()) return;

        UUID    id        = player.getUniqueId();
        boolean bypass    = player.hasPermission("fpantifreecam.bypass");
        boolean newHidden = !bypass && to.getY() >= surfaceY;
        boolean oldHidden = Boolean.TRUE.equals(playerHiddenState.getOrDefault(id, newHidden));

        if (newHidden == oldHidden) return; // No crossing – nothing to do.

        playerHiddenState.put(id, newHidden);
        dbg(String.format("Move transition %s fromY=%.1f toY=%.1f hidden=%b→%b",
                player.getName(), from.getY(), to.getY(), oldHidden, newHidden));

        if (entityHider != null) entityHider.onPlayerMove(player, from, to);

        if (!newHidden) {
            // Player went underground – restore normal view.
            refreshFullView(player);
        } else {
            // Player came to the surface – begin hiding, respecting cooldown.
            long now        = System.currentTimeMillis();
            long expiration = refreshCooldowns.getOrDefault(id, 0L);
            if (now < expiration) {
                dbg("Refresh cooldown active for " + player.getName()
                        + " (expires in " + (expiration - now) + " ms)");
                return;
            }

            // Determine radius – expand for instant-protection near the boundary.
            int radius = Bukkit.getViewDistance();
            if (instantProtection && forceImmediateRefresh
                    && to.getY() <= surfaceY + preLoadDistance) {
                radius = Math.max(radius, instantRadius);
                dbg("Instant-protection radius=" + radius + " for " + player.getName());
            }
            if (limitedAreaEnabled) radius = Math.min(radius, limitedAreaRadius);
            if (bedrockSupport  != null) radius = bedrockSupport.optimisedRadius(player, radius);

            final int fr = radius;
            performRefresh(player, fr);
            refreshCooldowns.put(id, now + refreshCooldownMs);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Command system
    // ═════════════════════════════════════════════════════════════════════

    private void registerCommands() {
        for (String cmdName : new String[]{ "fpac", "fpreload", "fpdebug" }) {
            var cmd = getCommand(cmdName);
            if (cmd != null) {
                cmd.setExecutor(this);
                cmd.setTabCompleter(this);
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return switch (command.getName().toLowerCase()) {
            case "fpac"     -> handleMain(sender, args);
            case "fpreload" -> handleReload(sender);
            case "fpdebug"  -> handleDebug(sender);
            default         -> false;
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("fpantifreecam.admin")) return Collections.emptyList();

        if (command.getName().equalsIgnoreCase("fpac")) {
            if (args.length == 1) {
                return StringUtil.copyPartialMatches(args[0],
                        Arrays.asList("reload", "debug", "world", "stats", "bypass", "help"),
                        new ArrayList<>());
            }
            if (args.length == 2) {
                if (args[0].equalsIgnoreCase("world")) {
                    return StringUtil.copyPartialMatches(args[1],
                            Arrays.asList("list", "add", "remove"), new ArrayList<>());
                }
                if (args[0].equalsIgnoreCase("bypass")) {
                    return Bukkit.getOnlinePlayers().stream()
                            .map(Player::getName)
                            .filter(n -> StringUtil.startsWithIgnoreCase(n, args[1]))
                            .collect(Collectors.toList());
                }
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("world")) {
                List<String> worldNames = Bukkit.getWorlds().stream()
                        .map(WorldInfo::getName).collect(Collectors.toList());
                if (args[1].equalsIgnoreCase("remove")) {
                    return StringUtil.copyPartialMatches(args[2],
                            new ArrayList<>(protectedWorlds), new ArrayList<>());
                }
                return StringUtil.copyPartialMatches(args[2], worldNames, new ArrayList<>());
            }
        }
        return Collections.emptyList();
    }

    // ── Sub-command handlers ──────────────────────────────────────────────

    private boolean handleMain(CommandSender sender, String[] args) {
        if (!sender.hasPermission("fpantifreecam.admin")) {
            sender.sendMessage(lang("no-permission")); return true;
        }
        if (args.length == 0) return handleHelp(sender);

        String   sub     = args[0].toLowerCase();
        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);
        return switch (sub) {
            case "reload" -> handleReload(sender);
            case "debug"  -> handleDebug(sender);
            case "world"  -> handleWorld(sender, subArgs);
            case "stats"  -> handleStats(sender);
            case "bypass" -> handleBypass(sender, subArgs);
            case "help"   -> handleHelp(sender);
            default -> {
                ChatUtil.sendError(sender, "Unknown sub-command. Use /fpac help.");
                yield true;
            }
        };
    }

    // /fpac reload  |  /fpreload
    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("fpantifreecam.reload")) {
            sender.sendMessage(lang("no-permission")); return true;
        }
        loadConfigValues();
        initReplacementBlock();

        // Re-evaluate every online player under the new config.
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (isWorldProtected(p.getWorld().getName())) {
                handlePlayerInitialState(p, true);
            } else if (playerHiddenState.remove(p.getUniqueId()) != null) {
                refreshFullView(p);
            }
        }
        ChatUtil.sendSuccess(sender, lang("reload-success", String.join(", ", protectedWorlds)));
        return true;
    }

    // /fpac debug  |  /fpdebug
    private boolean handleDebug(CommandSender sender) {
        if (!sender.hasPermission("fpantifreecam.debug")) {
            sender.sendMessage(lang("no-permission")); return true;
        }
        debugMode = !debugMode;
        getConfig().set("settings.debug-mode", debugMode);
        saveConfig();
        ChatUtil.sendSuccess(sender, debugMode ? lang("debug-on") : lang("debug-off"));
        getLogger().info("[FPAntiFreeCam] Debug mode " + (debugMode ? "ENABLED" : "DISABLED")
                + " by " + sender.getName());
        return true;
    }

    // /fpac world <list|add|remove> [name]
    private boolean handleWorld(CommandSender sender, String[] args) {
        if (!sender.hasPermission("fpantifreecam.world")) {
            sender.sendMessage(lang("no-permission")); return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase() : "list";

        if (sub.equals("list")) {
            if (protectedWorlds.isEmpty()) {
                ChatUtil.sendWarn(sender, lang("world-list-empty"));
            } else {
                ChatUtil.sendInfo(sender, lang("world-list-header"));
                protectedWorlds.forEach(w -> ChatUtil.sendInfo(sender, lang("world-list-entry", w)));
            }
            return true;
        }

        if (args.length < 2) {
            ChatUtil.sendError(sender, "Usage: /fpac world <list|add|remove> [name]");
            return true;
        }
        String worldName = args[1];

        if (sub.equals("add")) {
            if (Bukkit.getWorld(worldName) == null) {
                ChatUtil.sendError(sender, lang("error.world-not-found", worldName)); return true;
            }
            if (!protectedWorlds.add(worldName)) {
                ChatUtil.sendWarn(sender, lang("world-exists", worldName)); return true;
            }
            saveWorldList();
            ChatUtil.sendSuccess(sender, lang("world-added", worldName));
            Bukkit.getOnlinePlayers().stream()
                    .filter(p -> p.getWorld().getName().equals(worldName))
                    .forEach(p -> handlePlayerInitialState(p, true));

        } else if (sub.equals("remove")) {
            if (!protectedWorlds.remove(worldName)) {
                ChatUtil.sendWarn(sender, lang("world-missing", worldName)); return true;
            }
            saveWorldList();
            ChatUtil.sendSuccess(sender, lang("world-removed", worldName));
            Bukkit.getOnlinePlayers().stream()
                    .filter(p -> p.getWorld().getName().equals(worldName))
                    .forEach(p -> {
                        playerHiddenState.remove(p.getUniqueId());
                        refreshFullView(p);
                    });
        } else {
            ChatUtil.sendError(sender, "Unknown action. Use: list, add, remove.");
        }
        return true;
    }

    // /fpac stats
    private boolean handleStats(CommandSender sender) {
        if (!sender.hasPermission("fpantifreecam.admin")) {
            sender.sendMessage(lang("no-permission")); return true;
        }
        long activeCount = playerHiddenState.values().stream().filter(b -> b).count();

        ChatUtil.send(sender, "&3&l=== FPAntiFreeCam Statistics ===");
        ChatUtil.send(sender, "&eVersion:         &a" + getDescription().getVersion());
        ChatUtil.send(sender, "&ePlatform:        &a" + PlatformUtil.getPlatformName());
        ChatUtil.send(sender, "&eActive players:  &a" + activeCount
                + " &7/ " + Bukkit.getOnlinePlayers().size() + " online");
        ChatUtil.send(sender, "&eProtected worlds: &a" + protectedWorlds.size() + " &7" + protectedWorlds);
        ChatUtil.send(sender, "&eVoid Y:          &a" + voidY + "  &eSurface Y: &a" + surfaceY);
        ChatUtil.send(sender, "&eReplacement:     &a" + replacementBlockType
                + " &7(id=" + replacementBlockId + ")");
        ChatUtil.send(sender, "&eDebug mode:      &a" + (debugMode ? "ON" : "OFF"));
        ChatUtil.send(sender, "&eCooldown:        &a" + (refreshCooldownMs / 1_000) + "s");
        if (bedrockSupport != null)
            ChatUtil.send(sender, "&eBedrock:         &a" + bedrockSupport.statusLine());
        if (entityHider != null)
            ChatUtil.send(sender, "&eEntityHider:     &a" + entityHider.stats());
        if (foliaScheduler != null)
            ChatUtil.send(sender, "&eFoliaScheduler:  &a" + foliaScheduler.stats());
        if (paperScheduler != null)
            ChatUtil.send(sender, "&ePaperScheduler:  &a" + paperScheduler.stats());
        ChatUtil.send(sender, "&3&l=================================");
        return true;
    }

    // /fpac bypass <player>  – toggles bypass for that player this session
    private boolean handleBypass(CommandSender sender, String[] args) {
        if (!sender.hasPermission("fpantifreecam.bypass")) {
            sender.sendMessage(lang("no-permission")); return true;
        }
        if (args.length == 0) {
            ChatUtil.sendError(sender, "Usage: /fpac bypass <player>"); return true;
        }
        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            ChatUtil.sendError(sender, lang("bypass-unknown", args[0])); return true;
        }
        // Force the player out of the hidden state and refresh so they see normally.
        playerHiddenState.put(target.getUniqueId(), false);
        if (entityHider != null) entityHider.updateFor(target);
        refreshFullView(target);
        ChatUtil.sendSuccess(sender, lang("bypass-granted", target.getName()));
        return true;
    }

    // /fpac help
    private boolean handleHelp(CommandSender sender) {
        ChatUtil.send(sender, lang("help-header"));
        List<String> lines = langConfig != null
                ? langConfig.getStringList("messages.help-lines")
                : Collections.emptyList();
        if (lines.isEmpty()) {
            // Built-in fallback
            for (String l : new String[]{
                "&e/fpac reload    &7– Reload config & language",
                "&e/fpac debug     &7– Toggle debug logging",
                "&e/fpac world <list|add|remove> [name]  &7– Manage worlds",
                "&e/fpac stats     &7– Show runtime statistics",
                "&e/fpac bypass <player>  &7– Force-clear protection for a player",
                "&e/fpac help      &7– Show this help",
                "&e/fpreload       &7– Alias: reload",
                "&e/fpdebug        &7– Alias: debug"
            }) { ChatUtil.send(sender, l); }
        } else {
            lines.forEach(l -> ChatUtil.send(sender, ChatUtil.color(l)));
        }
        ChatUtil.send(sender, lang("help-footer"));
        return true;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Helpers
    // ═════════════════════════════════════════════════════════════════════

    private void saveWorldList() {
        getConfig().set("worlds.list", new ArrayList<>(protectedWorlds));
        saveConfig();
    }

    /**
     * Checks the 'config-version' field and merges missing keys from the default
     * config into the existing file if necessary.
     */
    private void checkConfigVersion() {
        FileConfiguration cfg = getConfig();
        int currentVer = cfg.getInt("config-version", 0);
        int latestVer  = 1; // Current latest version

        if (currentVer < latestVer) {
            getLogger().info("[FPAntiFreeCam] Updating config.yml to version " + latestVer + "...");
            
            // Set defaults from the jar's config.yml
            InputStream defStream = getResource("config.yml");
            if (defStream != null) {
                YamlConfiguration defConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(defStream, StandardCharsets.UTF_8));
                
                // Copy missing keys from default to current
                for (String key : defConfig.getKeys(true)) {
                    if (!cfg.contains(key)) {
                        cfg.set(key, defConfig.get(key));
                    }
                }
            }
            
            // Update the version number
            cfg.set("config-version", latestVer);
            saveConfig();
            getLogger().info("[FPAntiFreeCam] Config update complete.");
        }
    }
}
