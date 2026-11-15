package com.voicevanish;

import net.minecraft.server.network.ServerPlayerEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

/**
 * Picks the best available speech engine: Whisper (accurate) if it is loaded, otherwise Vosk.
 */
public final class SpeechHub implements SpeechBackend {
    private final SpeechService vosk = new SpeechService();
    private final WhisperService whisper = new WhisperService();

    /** @param root config/voicevanish */
    public void load(Path root) {
        String engine = VoiceVanishMod.CONFIG.engine;
        Path voskModel = root.resolve("model");

        if (!engine.equals("whisper") && (engine.equals("vosk") || Files.isDirectory(voskModel.resolve("am")))) {
            vosk.load(voskModel);
        }
        if (!engine.equals("vosk")) {
            whisper.load(root.resolve("whisper"));
        }
    }

    /** Tells the Vosk fallback which names to listen for (Whisper needs no list). */
    public void setVocabulary(Set<String> names) {
        vosk.setVocabulary(names);
    }

    public String describe() {
        if (whisper.isReady()) return "whisper";
        if (vosk.isReady()) return "vosk";
        return "none yet (model still loading or missing)";
    }

    @Override
    public boolean isReady() {
        return whisper.isReady() || vosk.isReady();
    }

    @Override
    public void feed(ServerPlayerEntity player, short[] pcm48k, boolean endOfSpeech) {
        if (whisper.isReady()) {
            whisper.feed(player, pcm48k, endOfSpeech);
        } else if (vosk.isReady()) {
            vosk.feed(player, pcm48k, endOfSpeech);
        }
    }

    @Override
    public void release(UUID id) {
        whisper.release(id);
        vosk.release(id);
    }
}
