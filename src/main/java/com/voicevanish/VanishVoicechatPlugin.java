package com.voicevanish;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Hooks into Simple Voice Chat and receives every player's microphone audio on the server. */
public class VanishVoicechatPlugin implements VoicechatPlugin {
    private static final Map<UUID, OpusDecoder> DECODERS = new ConcurrentHashMap<>();
    private static VoicechatApi api;

    @Override
    public String getPluginId() { return VoiceVanishMod.MOD_ID; }

    @Override
    public void initialize(VoicechatApi voicechatApi) { api = voicechatApi; }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophone);
    }

    private void onMicrophone(MicrophonePacketEvent event) {
        if (!VoiceVanishMod.SPEECH.isReady() || api == null) return;
        VoicechatConnection connection = event.getSenderConnection();
        if (connection == null) return;
        Object platformPlayer = connection.getPlayer().getPlayer();
        if (!(platformPlayer instanceof ServerPlayerEntity player)) return;
        OpusDecoder decoder = DECODERS.computeIfAbsent(player.getUuid(), id -> api.createDecoder());
        byte[] opus = event.getPacket().getOpusEncodedData();
        if (opus == null || opus.length == 0) {
            decoder.resetState();
            VoiceVanishMod.SPEECH.feed(player, null, true);
            return;
        }
        short[] pcm = decoder.decode(opus);
        VoiceVanishMod.SPEECH.feed(player, pcm, false);
    }

    public static void release(UUID id) {
        OpusDecoder decoder = DECODERS.remove(id);
        if (decoder != null) decoder.close();
    }
}
