package com.voicevanish;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Does the actual deleting. Mobs are removed instantly; blocks are removed a few chunks per tick
 * so the server does not freeze.
 */
public final class Vanisher {
    /** Tell clients about the change, skip block-update chains and item drops. */
    private static final int FLAGS = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;
    private static final List<Job> JOBS = new ArrayList<>();

    private static final class Job {
        final ServerWorld world;
        final ServerPlayerEntity player;
        final String label;
        final Set<Block> blocks;
        final ArrayDeque<ChunkPos> pending;
        int removed = 0;
        final int mobsRemoved;

        Job(ServerWorld world, ServerPlayerEntity player, String label, Set<Block> blocks,
            ArrayDeque<ChunkPos> pending, int mobsRemoved) {
            this.world = world;
            this.player = player;
            this.label = label;
            this.blocks = blocks;
            this.pending = pending;
            this.mobsRemoved = mobsRemoved;
        }
    }

    private Vanisher() {}

    /** Server thread only. */
    public static void start(ServerPlayerEntity speaker, NameIndex.Target target) {
        ServerWorld world = speaker.getServerWorld();
        ChunkPos center = speaker.getChunkPos();
        int radius = VoiceVanishMod.CONFIG.radiusChunks;
        int mobs = removeMobs(world, target.entities(), center, radius);

        if (target.blocks().isEmpty()) {
            if (mobs > 0) speaker.sendMessage(Text.literal("Vanished " + mobs + " " + target.label()), true);
            return;
        }

        List<ChunkPos> chunks = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) chunks.add(new ChunkPos(center.x + dx, center.z + dz));
        }
        chunks.sort(Comparator.comparingInt(c -> {
            int dx = c.x - center.x;
            int dz = c.z - center.z;
            return dx * dx + dz * dz;
        }));
        JOBS.add(new Job(world, speaker, target.label(), target.blocks(), new ArrayDeque<>(chunks), mobs));
    }

    /** Runs every server tick. */
    public static void tick() {
        if (JOBS.isEmpty()) return;
        int budget = VoiceVanishMod.CONFIG.chunksPerTick;
        Iterator<Job> it = JOBS.iterator();
        while (it.hasNext()) {
            Job job = it.next();
            for (int i = 0; i < budget && !job.pending.isEmpty(); i++) {
                ChunkPos pos = job.pending.poll();
                job.removed += scanChunk(job.world, pos.x, pos.z, job.blocks);
            }
            if (job.pending.isEmpty()) {
                it.remove();
                int total = job.removed + job.mobsRemoved;
                if (total > 0 && !job.player.isRemoved()) {
                    job.player.sendMessage(Text.literal("Vanished " + total + " " + job.label), true);
                }
            }
        }
    }

    private static int scanChunk(ServerWorld world, int cx, int cz, Set<Block> blocks) {
        // Only chunks that are already loaded. Nothing gets generated or loaded.
        WorldChunk chunk = world.getChunkManager().getWorldChunk(cx, cz);
        if (chunk == null) return 0;
        Predicate<BlockState> matches = state -> blocks.contains(state.getBlock());
        ChunkSection[] sections = chunk.getSectionArray();
        int bottomY = chunk.getBottomY();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        int count = 0;

        for (int s = 0; s < sections.length; s++) {
            ChunkSection section = sections[s];
            if (section.isEmpty() || !section.hasAny(matches)) continue;
            int baseY = bottomY + (s << 4);
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                BlockState state = section.getBlockState(x, y, z);
                if (blocks.contains(state.getBlock())) {
                    pos.set((cx << 4) + x, baseY + y, (cz << 4) + z);
                    world.setBlockState(pos, Blocks.AIR.getDefaultState(), FLAGS);
                    count++;
                }
            }
        }
        return count;
    }

    private static int removeMobs(ServerWorld world, Set<EntityType<?>> types, ChunkPos center, int radius) {
        if (types.isEmpty()) return 0;
        List<Entity> toRemove = new ArrayList<>();
        for (Entity entity : world.iterateEntities()) {
            if (!types.contains(entity.getType())) continue;
            ChunkPos cp = entity.getChunkPos();
            if (Math.abs(cp.x - center.x) <= radius && Math.abs(cp.z - center.z) <= radius) toRemove.add(entity);
        }
        for (Entity entity : toRemove) entity.discard();
        return toRemove.size();
    }
}
