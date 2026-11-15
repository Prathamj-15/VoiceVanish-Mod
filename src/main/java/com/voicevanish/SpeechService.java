package com.voicevanish;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.network.ServerPlayerEntity;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Offline speech-to-text using Vosk. One recognizer per player so voices never get mixed.
 *
 * Accuracy: instead of letting Vosk guess from the whole English dictionary (which turns "stone"
 * into "lead stone"), it is told to listen ONLY for Minecraft block and mob names. Everything else
 * becomes "[unk]" and is ignored. Words the recognizer is unsure about are dropped as well.
 */
public class SpeechService implements SpeechBackend {
    private static final float SAMPLE_RATE = 16000f;
    private static final String UNKNOWN = "[unk]";

    private Model model;
    /** Words the loaded model knows. Names containing other words cannot be recognized. */
    private Set<String> lexicon;
    /** JSON list of phrases handed to Vosk, or null to listen to everything. */
    private volatile String grammarJson;
    private final Map<UUID, Recognizer> recognizers = new ConcurrentHashMap<>();

    public boolean isReady() {
        return model != null;
    }

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
            return;
        }
        loadLexicon(modelDir.resolve("graph").resolve("words.txt"));
    }

    private void loadLexicon(Path wordsFile) {
        if (!Files.isRegularFile(wordsFile)) {
            VoiceVanishMod.LOGGER.warn("No {} found, cannot check which names the model understands.", wordsFile);
            return;
        }
        Set<String> words = new HashSet<>();
        try (Stream<String> lines = Files.lines(wordsFile, StandardCharsets.UTF_8)) {
            lines.forEach(line -> {
                int space = line.indexOf(' ');
                if (space > 0) words.add(line.substring(0, space));
            });
            lexicon = words;
        } catch (Exception e) {
            VoiceVanishMod.LOGGER.warn("Could not read the model's word list", e);
        }
    }

    /**
     * Tells the recognizer which names to listen for. Call once after all blocks and mobs exist
     * (server start), before players speak.
     */
    public void setVocabulary(Set<String> names) {
        if (model == null) return;
        if (!VoiceVanishMod.CONFIG.useGrammar) {
            grammarJson = null;
            VoiceVanishMod.LOGGER.info("Names-only listening is off, using full dictionary.");
            return;
        }

        Set<String> phrases = new TreeSet<>();
        int skipped = 0;
        StringBuilder examples = new StringBuilder();
        for (String name : names) {
            if (!name.matches("[a-z]+( [a-z]+)*")) {
                skipped++;
                continue;
            }
            // Words the model does not know are split into words it does: "deepslate" -> "deep slate".
            List<String> spoken = new ArrayList<>();
            boolean ok = true;
            for (String word : name.split(" ")) {
                List<String> parts = lexicon == null ? List.of(word) : splitIntoKnown(word);
                if (parts == null) {
                    ok = false;
                    break;
                }
                spoken.addAll(parts);
            }
            if (!ok) {
                skipped++;
                if (examples.length() < 160) examples.append(name).append(", ");
                continue;
            }
            phrases.add(String.join(" ", spoken));

            // Plural of the last word: "zombies", "oak logs"
            String last = spoken.get(spoken.size() - 1);
            for (String plural : new String[]{last + "s", last + "es"}) {
                if (lexicon == null || lexicon.contains(plural)) {
                    List<String> copy = new ArrayList<>(spoken);
                    copy.set(copy.size() - 1, plural);
                    phrases.add(String.join(" ", copy));
                }
            }
        }
        phrases.add(UNKNOWN);

        JsonArray array = new JsonArray();
        for (String phrase : phrases) array.add(phrase);
        grammarJson = array.toString();

        VoiceVanishMod.LOGGER.info("Listening for {} names ({} skipped because the speech model has no word for them{}).",
                phrases.size() - 1, skipped, examples.length() == 0 ? "" : ", e.g. " + examples);
    }

    /**
     * The word itself if the model knows it, otherwise the best split into two known words
     * ("deepslate" -> deep + slate), or null if neither works.
     */
    private List<String> splitIntoKnown(String word) {
        if (lexicon.contains(word)) return List.of(word);
        List<String> best = null;
        int bestScore = 0;
        for (int cut = 3; cut <= word.length() - 3; cut++) {
            String a = word.substring(0, cut);
            String b = word.substring(cut);
            if (lexicon.contains(a) && lexicon.contains(b)) {
                int score = Math.min(a.length(), b.length());
                if (score > bestScore) {
                    bestScore = score;
                    best = List.of(a, b);
                }
            }
        }
        return best;
    }

    private Recognizer createRecognizer() {
        try {
            String grammar = grammarJson;
            Recognizer rec = grammar == null
                    ? new Recognizer(model, SAMPLE_RATE)
                    : new Recognizer(model, SAMPLE_RATE, grammar);
            rec.setWords(true); // gives a confidence value for every word
            return rec;
        } catch (Exception e) {
            VoiceVanishMod.LOGGER.error("Could not create recognizer", e);
            return null;
        }
    }

    /**
     * Feed one decoded voice frame (48 kHz, 16-bit mono, as given by Simple Voice Chat).
     * Runs on the voice chat thread, never touches the world directly.
     *
     * @param endOfSpeech true when the player stopped talking (empty microphone packet)
     */
    public void feed(ServerPlayerEntity player, short[] pcm48k, boolean endOfSpeech) {
        if (model == null) return;

        Recognizer rec = recognizers.computeIfAbsent(player.getUuid(), id -> createRecognizer());
        if (rec == null) return;

        String json = null;
        synchronized (rec) {
            if (pcm48k != null && pcm48k.length >= 3) {
                byte[] bytes = downsampleTo16kBytes(pcm48k);
                if (rec.acceptWaveForm(bytes, bytes.length)) {
                    json = rec.getResult();
                }
            }
            if (json == null && endOfSpeech) {
                json = rec.getFinalResult();
            }
        }

        if (json != null) {
            String text = extractText(json);
            if (!text.isBlank()) {
                // Jump to the server thread before touching Minecraft.
                player.getServer().execute(() -> VoiceVanishMod.onSpeech(player, text));
            }
        }
    }

    public void release(UUID id) {
        Recognizer rec = recognizers.remove(id);
        if (rec != null) {
            synchronized (rec) {
                rec.close();
            }
        }
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

    /** Keeps only confidently recognized, known words. */
    private static String extractText(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            JsonElement words = obj.get("result");
            if (words == null || !words.isJsonArray()) {
                // No per-word info available: fall back to the plain text.
                JsonElement text = obj.get("text");
                return text == null ? "" : text.getAsString().replace(UNKNOWN, "").trim();
            }
            double min = VoiceVanishMod.CONFIG.minConfidence;
            StringBuilder out = new StringBuilder();
            for (JsonElement el : words.getAsJsonArray()) {
                JsonObject w = el.getAsJsonObject();
                String word = w.get("word").getAsString();
                double conf = w.has("conf") ? w.get("conf").getAsDouble() : 1.0;
                if (UNKNOWN.equals(word) || conf < min) continue;
                if (out.length() > 0) out.append(' ');
                out.append(word);
            }
            return out.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
