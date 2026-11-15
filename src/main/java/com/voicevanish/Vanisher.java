package com.voicevanish;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
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
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Does the actual deleting. A heard name first waits a few seconds, then mobs are removed at once and
 * blocks are removed a few chunks per tick so the server does not freeze.
 */
public final class Vanisher {
    /** Tell clients about the change, skip block-update chains and item drops. */
    private static final int FLAGS = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;

    /** Names that were heard and are waiting for their delay to run out. */
    private static final List<Waiting> WAITING = new ArrayList<>();
    private static final List<Job> JOBS = new ArrayList<>();
    private static boolean viewDistanceWarned = false;

    private record Waiting(ServerWorld world, UUID playerId, ChunkPos center, int radius,
                           NameIndex.Target target, int fireTick) {}

    private static final class Job {
        final ServerWorld world;
        final UUID playerId;
        final String label;
        final Set<Block> blocks;
        final ArrayDeque<ChunkPos> pending;
        final int mobsRemoved;
        int removed = 0;
        BlockPos soundPos;

        Job(ServerWorld world, UUID playerId, String label, Set<Block> blocks,
            ArrayDeque<ChunkPos> pending, int mobsRemoved) {
            this.world = world;
            this.playerId = playerId;
            this.label = label;
            this.blocks = blocks;
            this.pending = pending;
            this.mobsRemoved = mobsRemoved;
        }
    }

    private Vanisher() {}

    /** A name was heard. Server thread only. The vanishing happens after the configured delay. */
    public static void start(ServerPlayerEntity speaker, NameIndex.Target target) {
        ServerWorld world = speaker.getServerWorld();
        for (Waiting w : WAITING) {
            if (w.world() == world && w.target().label().equals(target.label())) return; // already counting down
        }

        MinecraftServer server = world.getServer();
        int delay = VoiceVanishMod.CONFIG.delaySeconds;
        int radius = VoiceVanishMod.CONFIG.radiusChunks;
        WAITING.add(new Waiting(world, speaker.getUuid(), speaker.getChunkPos(), radius,
                target, server.getTicks() + delay * 20));

        speaker.sendMessage(Text.literal("Heard \"" + target.label() + "\" - vanishing in " + delay + "s"), true);

        int view = server.getPlayerManager().getViewDistance();
        if (!viewDistanceWarned && radius > view) {
            viewDistanceWarned = true;
            speaker.sendMessage(Text.literal("Voice Vanish: the server only loads " + view + " chunks around you, so only "
                    + "those are affected. Set view-distance=32 in server.properties to reach " + radius + " chunks."), false);
        }
    }

    /** Runs every server tick. */
    public static void tick(MinecraftServer server) {
        if (!WAITING.isEmpty()) {
            int now = server.getTicks();
            Iterator<Waiting> it = WAITING.iterator();
            while (it.hasNext()) {
                Waiting w = it.next();
                if (now >= w.fireTick()) {
                    it.remove();
                    fire(server, w);
                }
            }
        }

        if (JOBS.isEmpty()) return;
        int budget = VoiceVanishMod.CONFIG.chunksPerTick;
        Iterator<Job> it = JOBS.iterator();
        while (it.hasNext()) {
            Job job = it.next();
            job.soundPos = null;
            for (int i = 0; i < budget && !job.pending.isEmpty(); i++) {
                ChunkPos pos = job.pending.poll();
                job.removed += scanChunk(job, pos.x, pos.z);
            }
            if (job.soundPos != null && VoiceVanishMod.CONFIG.playSound) {
                playVanishSound(job.world, job.soundPos.getX() + 0.5, job.soundPos.getY() + 0.5, job.soundPos.getZ() + 0.5);
            }
            if (job.pending.isEmpty()) {
                it.remove();
                finish(server, job.playerId, job.removed + job.mobsRemoved, job.label);
            }
        }
    }

    private static void fire(MinecraftServer server, Waiting w) {
        int mobs = removeMobs(w.world(), w.target().entities(), w.center(), w.radius());

        if (w.target().blocks().isEmpty()) {
            finish(server, w.playerId(), mobs, w.target().label());
            return;
        }

        List<ChunkPos> chunks = new ArrayList<>();
        ChunkPos center = w.center();
        int radius = w.radius();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                chunks.add(new ChunkPos(center.x + dx, center.z + dz));
            }
        }
        // Closest chunks first, so the effect spreads outward from the speaker.
        chunks.sort(Comparator.comparingInt(c -> {
            int dx = c.x - center.x;
            int dz = c.z - center.z;
            return dx * dx + dz * dz;
        }));
        JOBS.add(new Job(w.world(), w.playerId(), w.target().label(), w.target().blocks(),
                new ArrayDeque<>(chunks), mobs));
    }

    private static void finish(MinecraftServer server, UUID playerId, int total, String label) {
        if (total <= 0) return;
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
        if (player != null) {
            player.sendMessage(Text.literal("Vanished " + total + " " + label), true);
        }
    }

    private static int scanChunk(Job job, int cx, int cz) {
        // Only chunks that are already loaded. Nothing gets generated or loaded.
        WorldChunk chunk = job.world.getChunkManager().getWorldChunk(cx, cz);
        if (chunk == null) return 0;

        Set<Block> blocks = job.blocks;
        Predicate<BlockState> matches = state -> blocks.contains(state.getBlock());
        ChunkSection[] sections = chunk.getSectionArray();
        int bottomY = chunk.getBottomY();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        int count = 0;

        for (int s = 0; s < sections.length; s++) {
            ChunkSection section = sections[s];
            if (section.isEmpty() || !section.hasAny(matches)) continue;
            int baseY = bottomY + (s << 4);
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (blocks.contains(state.getBlock())) {
                            pos.set((cx << 4) + x, baseY + y, (cz << 4) + z);
                            if (job.soundPos == null) job.soundPos = pos.toImmutable();
                            job.world.setBlockState(pos, Blocks.AIR.getDefaultState(), FLAGS);
                            count++;
                        }
                    }
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
            if (Math.abs(cp.x - center.x) <= radius && Math.abs(cp.z - center.z) <= radius) {
                toRemove.add(entity);
            }
        }
        int sounds = 0;
        for (Entity entity : toRemove) {
            if (VoiceVanishMod.CONFIG.playSound && sounds++ < 8) {
                playVanishSound(world, entity.getX(), entity.getY(), entity.getZ());
            }
            entity.discard();
        }
        return toRemove.size();
    }

    /** A short glassy "poof": a high-pitched shatter layered with a soft teleport whoosh. */
    private static void playVanishSound(ServerWorld world, double x, double y, double z) {
        world.playSound(null, x, y, z, SoundEvents.BLOCK_AMETHYST_BLOCK_BREAK, SoundCategory.BLOCKS, 1.2f, 1.5f);
        world.playSound(null, x, y, z, SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.BLOCKS, 0.35f, 2.0f);
    }
}
