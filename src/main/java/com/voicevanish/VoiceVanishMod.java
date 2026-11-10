package com.voicevanish;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class VoiceVanishMod implements ModInitializer {
    public static final String MOD_ID = "voicevanish";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static VanishConfig CONFIG;
    public static final NameIndex NAMES = new NameIndex();
    public static final SpeechService SPEECH = new SpeechService();
    private static final Map<String, Long> LAST_TRIGGER = new HashMap<>();

    @Override
    public void onInitialize() {
        CONFIG = VanishConfig.load();
        Path modelDir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID).resolve("model");
        SPEECH.load(modelDir);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> NAMES.build());
        ServerTickEvents.END_SERVER_TICK.register(server -> Vanisher.tick());
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID id = handler.getPlayer().getUuid();
            SPEECH.release(id);
            VanishVoicechatPlugin.release(id);
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                VanishCommand.register(dispatcher));
        LOGGER.info("Voice Vanish loaded. Radius: {} chunks.", CONFIG.radiusChunks);
    }

    /** Called on the SERVER thread with whatever a player just said. */
    public static void onSpeech(ServerPlayerEntity player, String text) {
        if (!CONFIG.enabled || text == null || text.isBlank()) return;
        String heard = text.toLowerCase().trim();
        if (CONFIG.showHeard) player.sendMessage(Text.literal("🎙 " + heard), true);
        String wake = CONFIG.wakeWord;
        if (!wake.isEmpty()) {
            int idx = heard.indexOf(wake);
            if (idx < 0) return;
            heard = heard.substring(idx + wake.length()).trim();
            if (heard.isEmpty()) return;
        }
        List<NameIndex.Target> targets = NAMES.match(heard);
        long now = System.currentTimeMillis();
        for (NameIndex.Target target : targets) {
            Long last = LAST_TRIGGER.get(target.label());
            if (last != null && now - last < CONFIG.cooldownMs) continue;
            LAST_TRIGGER.put(target.label(), now);
            Vanisher.start(player, target);
        }
    }
}
