package com.voicevanish;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** Settings stored in config/voicevanish.json. Change them with /voicevanish or edit the file. */
public class VanishConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("voicevanish.json");
    private static final int CURRENT_VERSION = 2;

    /** Used to upgrade old config files. */
    public int configVersion;

    /** Master switch. */
    public boolean enabled = true;

    /** How many chunks around the speaker are affected, in every direction. Needs view-distance on the server to match. */
    public int radiusChunks = 30;

    /** Seconds between hearing a name and the blocks/mobs vanishing. */
    public int delaySeconds = 5;

    /** Play a small vanish sound when things disappear. */
    public boolean playSound = true;

    /** How many chunks are scanned per server tick while clearing blocks (lower = less lag). */
    public int chunksPerTick = 8;

    /** The same name will not trigger again within this many milliseconds. */
    public int cooldownMs = 2500;

    /** If one sentence contains more names than this, it is treated as a misheard clip and ignored. */
    public int maxNamesPerSentence = 6;

    /** Show what was heard on the speaker's action bar when no block or mob name was found in it. */
    public boolean showHeard = true;

    /** "auto" (Whisper, with Vosk as backup), "whisper" or "vosk". */
    public String engine = "auto";

    /** Whisper model name. "small.en-q5_1" is accurate and compact; "base.en-q5_1" is faster; "small.en" is the most accurate. */
    public String whisperModel = "small.en-q5_1";

    /** Download the Whisper model automatically the first time. */
    public boolean autoDownloadModel = true;

    /** CPU threads Whisper may use. 0 = automatic. */
    public int whisperThreads = 0;

    /** Vosk only: listen for block and mob names only. */
    public boolean useGrammar = true;

    /** Vosk only: ignore words it is less sure about than this (0.0 to 1.0). */
    public double minConfidence = 0.6;

    public static VanishConfig load() {
        if (Files.exists(FILE)) {
            try (Reader reader = Files.newBufferedReader(FILE)) {
                VanishConfig cfg = GSON.fromJson(reader, VanishConfig.class);
                if (cfg != null) {
                    if (cfg.configVersion < CURRENT_VERSION) {
                        // Older config: move to the new bigger radius and faster clearing.
                        cfg.radiusChunks = 30;
                        cfg.chunksPerTick = Math.max(cfg.chunksPerTick, 8);
                        cfg.configVersion = CURRENT_VERSION;
                        cfg.save();
                    }
                    cfg.clamp();
                    return cfg;
                }
            } catch (Exception e) {
                VoiceVanishMod.LOGGER.error("Could not read config, using defaults", e);
            }
        }
        VanishConfig cfg = new VanishConfig();
        cfg.configVersion = CURRENT_VERSION;
        cfg.save();
        return cfg;
    }

    public void save() {
        clamp();
        try (Writer writer = Files.newBufferedWriter(FILE)) {
            GSON.toJson(this, writer);
        } catch (IOException e) {
            VoiceVanishMod.LOGGER.error("Could not save config", e);
        }
    }

    private void clamp() {
        radiusChunks = Math.max(1, Math.min(64, radiusChunks));
        delaySeconds = Math.max(0, Math.min(120, delaySeconds));
        chunksPerTick = Math.max(1, Math.min(64, chunksPerTick));
        cooldownMs = Math.max(0, cooldownMs);
        maxNamesPerSentence = Math.max(1, maxNamesPerSentence);
        whisperThreads = Math.max(0, whisperThreads);
        minConfidence = Math.max(0.0, Math.min(1.0, minConfidence));
        if (engine == null || !Set.of("auto", "whisper", "vosk").contains(engine.trim().toLowerCase())) {
            engine = "auto";
        }
        engine = engine.trim().toLowerCase();
        if (whisperModel == null || !whisperModel.matches("[A-Za-z0-9._-]+")) {
            whisperModel = "small.en-q5_1";
        }
    }
}
