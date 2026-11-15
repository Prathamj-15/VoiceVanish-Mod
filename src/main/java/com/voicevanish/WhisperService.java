package com.voicevanish;

import io.github.givimad.whisperjni.WhisperContext;
import io.github.givimad.whisperjni.WhisperFullParams;
import io.github.givimad.whisperjni.WhisperJNI;
import io.github.givimad.whisperjni.WhisperSamplingStrategy;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Speech-to-text with Whisper (the same family of models used by modern dictation tools), running
 * fully offline through whisper.cpp. Each player's speech is collected until they pause, then
 * transcribed on a background thread so the server never lags.
 */
public class WhisperService implements SpeechBackend {
    private static final int RATE = 16000;
    /** Ignore blips shorter than this (a quarter second). */
    private static final int MIN_SAMPLES = RATE / 4;
    /** Whisper wants at least one second of audio, so short clips are padded with silence. */
    private static final int PAD_TO = (int) (RATE * 1.3);
    /** Longest chunk sent to Whisper in one go. */
    private static final int MAX_SAMPLES = RATE * 10;
    /** Pause length that ends a sentence. */
    private static final int SILENCE_FLUSH = (int) (RATE * 0.8);
    /** Frames quieter than this count as silence. */
    private static final float SPEECH_RMS = 0.01f;

    /** A natural sentence gives Whisper context for the unusual words without listing names it could repeat back. */
    private static final String PROMPT =
            "A Minecraft player talking about blocks and mobs, like deepslate, dirt, and pigs.";

    private static final class Segment {
        final float[] data = new float[MAX_SAMPLES + RATE];
        int length;
        int silent;
        boolean spoke;
    }

    private final Map<UUID, Segment> segments = new ConcurrentHashMap<>();
    /** One thread does everything native (loading, downloading, transcribing). */
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8),
            r -> {
                Thread t = new Thread(r, "voicevanish-whisper");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy());

    private volatile boolean ready;
    private WhisperJNI whisper;
    private WhisperContext context;

    @Override
    public boolean isReady() {
        return ready;
    }

    /** Starts loading (and, if needed, downloading) the model in the background. */
    public void load(Path dir) {
        String name = VoiceVanishMod.CONFIG.whisperModel;
        Path file = dir.resolve("ggml-" + name + ".bin");
        worker.execute(() -> {
            try {
                WhisperJNI.loadLibrary();
                WhisperJNI.setLibraryLogger(null);
                whisper = new WhisperJNI();

                if (!Files.isRegularFile(file)) {
                    if (!VoiceVanishMod.CONFIG.autoDownloadModel) {
                        VoiceVanishMod.LOGGER.warn("Whisper model {} not found. Download ggml-{}.bin and put it in {}.",
                                name, name, dir);
                        return;
                    }
                    download(name, file);
                }
                context = whisper.init(file);
                ready = true;
                VoiceVanishMod.LOGGER.info("Whisper speech model '{}' loaded. Voice detection is ready.", name);
            } catch (Throwable t) {
                VoiceVanishMod.LOGGER.error("Could not start Whisper (falling back to Vosk if available)", t);
            }
        });
    }

    private static void download(String name, Path target) throws IOException, InterruptedException {
        String url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-" + name + ".bin";
        VoiceVanishMod.LOGGER.info("Downloading the Whisper speech model '{}' (one time only, can take a few minutes)...", name);
        Files.createDirectories(target.getParent());
        Path part = target.resolveSibling(target.getFileName() + ".part");

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpResponse<InputStream> response = client.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("Download failed with HTTP " + response.statusCode() + " for " + url);
        }
        long total = response.headers().firstValueAsLong("Content-Length").orElse(-1);

        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(part)) {
            byte[] buffer = new byte[1 << 16];
            long done = 0;
            int nextPercent = 10;
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                done += read;
                if (total > 0 && done * 100 / total >= nextPercent) {
                    VoiceVanishMod.LOGGER.info("Whisper model download: {}%", nextPercent);
                    nextPercent += 10;
                }
            }
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        VoiceVanishMod.LOGGER.info("Whisper model downloaded to {}", target);
    }

    @Override
    public void feed(ServerPlayerEntity player, short[] pcm48k, boolean endOfSpeech) {
        if (!ready) return;

        Segment seg = segments.computeIfAbsent(player.getUuid(), id -> new Segment());
        float[] finished = null;
        synchronized (seg) {
            if (pcm48k != null && pcm48k.length >= 3) {
                int n = pcm48k.length / 3;
                if (seg.length + n > seg.data.length) {
                    finished = take(seg); // buffer full: send what we have
                }
                // 48 kHz -> 16 kHz by averaging groups of 3 samples
                double energy = 0;
                for (int i = 0; i < n; i++) {
                    float f = (pcm48k[i * 3] + pcm48k[i * 3 + 1] + pcm48k[i * 3 + 2]) / (3f * 32768f);
                    seg.data[seg.length + i] = f;
                    energy += f * f;
                }
                seg.length += n;
                if (Math.sqrt(energy / n) >= SPEECH_RMS) {
                    seg.spoke = true;
                    seg.silent = 0;
                } else {
                    seg.silent += n;
                }
                if (finished == null && seg.spoke && seg.silent >= SILENCE_FLUSH) {
                    finished = take(seg); // they paused
                } else if (finished == null && seg.length >= MAX_SAMPLES) {
                    finished = take(seg); // long ramble: do not wait forever
                }
            }
            if (finished == null && endOfSpeech) {
                finished = take(seg); // microphone stopped
            }
        }

        if (finished != null) {
            float[] samples = finished;
            worker.execute(() -> transcribe(player, samples));
        }
    }

    /** Caller holds the lock. Returns the audio to transcribe (or null if there was no speech) and resets. */
    private static float[] take(Segment seg) {
        float[] out = null;
        if (seg.spoke && seg.length >= MIN_SAMPLES) {
            out = new float[Math.max(seg.length, PAD_TO)];
            System.arraycopy(seg.data, 0, out, 0, seg.length);
        }
        seg.length = 0;
        seg.silent = 0;
        seg.spoke = false;
        return out;
    }

    private void transcribe(ServerPlayerEntity player, float[] samples) {
        try {
            VanishConfig cfg = VoiceVanishMod.CONFIG;
            WhisperFullParams params = new WhisperFullParams(WhisperSamplingStrategy.GREEDY);
            params.language = "en";
            params.initialPrompt = PROMPT;
            params.noContext = true;
            params.noTimestamps = true;
            params.printProgress = false;
            params.printTimestamps = false;
            params.printRealtime = false;
            params.printSpecial = false;
            params.nThreads = cfg.whisperThreads > 0
                    ? cfg.whisperThreads
                    : Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));

            int result = whisper.full(context, params, samples, samples.length);
            if (result != 0) {
                VoiceVanishMod.LOGGER.warn("Whisper could not transcribe a clip (code {})", result);
                return;
            }
            StringBuilder text = new StringBuilder();
            int segments = whisper.fullNSegments(context);
            for (int i = 0; i < segments; i++) {
                text.append(' ').append(whisper.fullGetSegmentText(context, i));
            }
            String heard = text.toString().trim();
            if (heard.isEmpty()) return;

            MinecraftServer server = player.getServer();
            if (server != null) {
                server.execute(() -> VoiceVanishMod.onSpeech(player, heard));
            }
        } catch (Throwable t) {
            VoiceVanishMod.LOGGER.error("Whisper transcription failed", t);
        }
    }

    @Override
    public void release(UUID id) {
        segments.remove(id);
    }
}
