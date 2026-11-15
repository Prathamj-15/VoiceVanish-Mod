package com.voicevanish;

import net.minecraft.server.network.ServerPlayerEntity;

import java.util.UUID;

/** Something that turns a player's microphone audio into text. */
public interface SpeechBackend {
    boolean isReady();

    /**
     * Feed one decoded voice frame (48 kHz, 16-bit mono, as given by Simple Voice Chat).
     * Runs on the voice chat thread, never touch the world from here.
     *
     * @param pcm48k      the audio, or null
     * @param endOfSpeech true when the player stopped talking
     */
    void feed(ServerPlayerEntity player, short[] pcm48k, boolean endOfSpeech);

    void release(UUID id);
}
