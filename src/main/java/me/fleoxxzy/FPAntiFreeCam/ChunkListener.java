package me.fleoxxzy.FPAntiFreeCam;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * PacketEvents listener that intercepts outbound chunk/block-change packets
 * and replaces hidden underground blocks with air for eligible players.
 *
 * Supports:
 *  - CHUNK_DATA          (initial chunk load and full re-send)
 *  - BLOCK_CHANGE        (single block update)
 *  - MULTI_BLOCK_CHANGE  (section batch update)
 */
public final class ChunkListener implements PacketListener {

    private final FPAntiFreeCam plugin;

    public ChunkListener(FPAntiFreeCam plugin) {
        this.plugin = plugin;
    }

    // ── PacketEvents entry point ──────────────────────────────────────────

    @Override
    public void onPacketSend(PacketSendEvent event) {
        User user = event.getUser();
        if (user == null) return;

        UUID uuid = user.getUUID();
        if (uuid == null) return;

        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) return;

        World world = player.getWorld();
        if (world == null || !plugin.isWorldProtected(world.getName())) return;

        if (!plugin.isProtectionActive(player)) return;

        if (event.getPacketType() == PacketType.Play.Server.CHUNK_DATA) {
            handleChunkData(event, player);
        } else if (event.getPacketType() == PacketType.Play.Server.BLOCK_CHANGE) {
            handleBlockChange(event, player);
        } else if (event.getPacketType() == PacketType.Play.Server.MULTI_BLOCK_CHANGE) {
            handleMultiBlockChange(event, player);
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        // No inbound packets need modification
    }

    // ── CHUNK_DATA ────────────────────────────────────────────────────────

    private void handleChunkData(PacketSendEvent event, Player player) {
        WrappedBlockState replacement = plugin.getReplacementBlock();
        if (replacement == null) return;

        WrapperPlayServerChunkData wrapper;
        try {
            wrapper = new WrapperPlayServerChunkData(event);
        } catch (Exception e) {
            plugin.dbg("ChunkData wrapper error for " + player.getName() + ": " + e.getMessage());
            return;
        }

        Column column;
        BaseChunk[] sections;
        try {
            column   = wrapper.getColumn();
            sections = column != null ? column.getChunks() : null;
        } catch (Exception e) {
            plugin.dbg("Column access error: " + e.getMessage());
            return;
        }

        if (sections == null) return;

        World   world    = player.getWorld();
        int     minY     = world.getMinHeight();
        int     voidY    = plugin.getVoidY();
        boolean modified = false;

        for (int si = 0; si < sections.length; si++) {
            BaseChunk section = sections[si];
            if (section == null || section.isEmpty()) continue;

            int sectionBaseY = minY + si * 16;

            for (int ly = 0; ly < 16; ly++) {
                int worldY = sectionBaseY + ly;
                if (worldY > voidY) continue;

                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        try {
                            WrappedBlockState current = section.get(lx, ly, lz);
                            if (current != null && !current.equals(replacement)) {
                                section.set(lx, ly, lz, replacement);
                                modified = true;
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        }

        if (modified) {
            try { wrapper.setIgnoreOldData(true); } catch (Exception ignored) {}
            event.markForReEncode(true);
            plugin.dbg("CHUNK_DATA modified for " + player.getName());
        }
    }

    // ── BLOCK_CHANGE ──────────────────────────────────────────────────────

    private void handleBlockChange(PacketSendEvent event, Player player) {
        WrappedBlockState replacement = plugin.getReplacementBlock();
        if (replacement == null) return;

        try {
            WrapperPlayServerBlockChange wrapper = new WrapperPlayServerBlockChange(event);
            Vector3i pos = wrapper.getBlockPosition();
            if (pos == null) return;

            if (pos.getY() > plugin.getVoidY()) return;

            WrappedBlockState current = wrapper.getBlockState();
            if (current != null && !current.equals(replacement)) {
                wrapper.setBlockState(replacement);
                event.markForReEncode(true);
                plugin.dbg("BLOCK_CHANGE modified at " + pos + " for " + player.getName());
            }
        } catch (Exception e) {
            plugin.dbg("BLOCK_CHANGE error: " + e.getMessage());
        }
    }

    // ── MULTI_BLOCK_CHANGE ────────────────────────────────────────────────

    private void handleMultiBlockChange(PacketSendEvent event, Player player) {
        WrappedBlockState replacement = plugin.getReplacementBlock();
        if (replacement == null) return;

        try {
            WrapperPlayServerMultiBlockChange wrapper = new WrapperPlayServerMultiBlockChange(event);
            WrapperPlayServerMultiBlockChange.EncodedBlock[] blocks = wrapper.getBlocks();
            if (blocks == null) return;

            boolean modified      = false;
            int     replacementId = plugin.getReplacementBlockId();
            int     voidY         = plugin.getVoidY();

            for (WrapperPlayServerMultiBlockChange.EncodedBlock block : blocks) {
                if (block == null) continue;
                if (block.getY() > voidY) continue;
                if (block.getBlockId() != replacementId) {
                    block.setBlockId(replacementId);
                    modified = true;
                }
            }

            if (modified) {
                event.markForReEncode(true);
                plugin.dbg("MULTI_BLOCK_CHANGE modified for " + player.getName());
            }
        } catch (Exception e) {
            plugin.dbg("MULTI_BLOCK_CHANGE error: " + e.getMessage());
        }
    }
}
