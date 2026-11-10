package com.voicevanish;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.network.ServerPlayerEntity;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Offline speech-to-text using Vosk. One recognizer per player so voices never get mixed. */
public class SpeechService {
    private static final float SAMPLE_RATE = 16000f;
    private Model model;
    private final Map<UUID, Recognizer> recognizers = new ConcurrentHashMap<>();

    public boolean isReady() { return model != null; }

    public void load(Path modelDir) {
        if (!Files.isDirectory(modelDir.resolve("am"))) {
            VoiceVanishMod.LOGGER.warn(
                    "Vosk model not found in {}. Download vosk-model-small-en-us-0.15 and extract its contents there. "
                            + "Voice commands are disabled until you do.", modelDir);
            return;
        }
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS);
            model = new Model(modelDir.toString());
            VoiceVanishMod.LOGGER.info("Vosk speech model loaded from {}", modelDir);
        } catch (Throwable t) {
            VoiceVanishMod.LOGGER.error("Could not load the Vosk speech model", t);
            model = null;
        }
    }

    /** Feed one decoded voice frame (48 kHz, 16-bit mono, as given by Simple Voice Chat). */
    public void feed(ServerPlayerEntity player, short[] pcm48k, boolean endOfSpeech) {
        if (model == null) return;
        Recognizer rec = recognizers.computeIfAbsent(player.getUuid(), id -> {
            try {
                return new Recognizer(model, SAMPLE_RATE);
            } catch (Exception e) {
                VoiceVanishMod.LOGGER.error("Could not create recognizer", e);
                return null;
            }
        });
        if (rec == null) return;
        String json = null;
        synchronized (rec) {
            if (pcm48k != null && pcm48k.length >= 3) {
                byte[] bytes = downsampleTo16kBytes(pcm48k);
                if (rec.acceptWaveForm(bytes, bytes.length)) json = rec.getResult();
            }
            if (json == null && endOfSpeech) json = rec.getFinalResult();
        }
        if (json != null) {
            String text = extractText(json);
            if (!text.isBlank()) player.getServer().execute(() -> VoiceVanishMod.onSpeech(player, text));
        }
    }

    public void release(UUID id) {
        Recognizer rec = recognizers.remove(id);
        if (rec != null) synchronized (rec) { rec.close(); }
    }

    /** 48 kHz -> 16 kHz by averaging each group of 3 samples, then little-endian 16-bit bytes. */
    private static byte[] downsampleTo16kBytes(short[] in) {
        int outLen = in.length / 3;
        byte[] out = new byte[outLen * 2];
        for (int i = 0; i < outLen; i++) {
            int sum = in[i * 3] + in[i * 3 + 1] + in[i * 3 + 2];
            short s = (short) (sum / 3);
            out[i * 2] = (byte) (s & 0xFF);
            out[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
        }
        return out;
    }

    private static String extractText(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            JsonElement text = obj.get("text");
            return text == null ? "" : text.getAsString();
        } catch (Exception e) {
            return "";
        }
    }
}
