package com.voicevanish;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

/** /voicevanish on | off | status | radius <chunks> | wakeword <word> | wakeword off | heard on|off */
public final class VanishCommand {
    private VanishCommand() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("voicevanish")
                .requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.literal("on").executes(ctx -> {
                    VoiceVanishMod.CONFIG.enabled = true;
                    VoiceVanishMod.CONFIG.save();
                    reply(ctx.getSource(), "Voice Vanish is ON. Be careful what you say!");
                    return 1;
                }))
                .then(CommandManager.literal("off").executes(ctx -> {
                    VoiceVanishMod.CONFIG.enabled = false;
                    VoiceVanishMod.CONFIG.save();
                    reply(ctx.getSource(), "Voice Vanish is OFF.");
                    return 1;
                }))
                .then(CommandManager.literal("status").executes(ctx -> {
                    VanishConfig c = VoiceVanishMod.CONFIG;
                    reply(ctx.getSource(), "enabled=" + c.enabled
                            + ", radius=" + c.radiusChunks + " chunks"
                            + ", wakeWord=" + (c.wakeWord.isEmpty() ? "(none)" : c.wakeWord)
                            + ", speechModelLoaded=" + VoiceVanishMod.SPEECH.isReady());
                    return 1;
                }))
                .then(CommandManager.literal("radius")
                        .then(CommandManager.argument("chunks", IntegerArgumentType.integer(1, 64))
                                .executes(ctx -> {
                                    VoiceVanishMod.CONFIG.radiusChunks = IntegerArgumentType.getInteger(ctx, "chunks");
                                    VoiceVanishMod.CONFIG.save();
                                    reply(ctx.getSource(), "Radius set to " + VoiceVanishMod.CONFIG.radiusChunks
                                            + " chunks. (Only chunks the server has loaded are affected.)");
                                    return 1;
                                })))
                .then(CommandManager.literal("wakeword")
                        .then(CommandManager.literal("off").executes(ctx -> {
                            VoiceVanishMod.CONFIG.wakeWord = "";
                            VoiceVanishMod.CONFIG.save();
                            reply(ctx.getSource(), "Wake word removed. Any block or mob name works now.");
                            return 1;
                        }))
                        .then(CommandManager.argument("word", StringArgumentType.greedyString()).executes(ctx -> {
                            VoiceVanishMod.CONFIG.wakeWord = StringArgumentType.getString(ctx, "word");
                            VoiceVanishMod.CONFIG.save();
                            reply(ctx.getSource(), "Wake word set to \"" + VoiceVanishMod.CONFIG.wakeWord
                                    + "\". Say e.g. \"" + VoiceVanishMod.CONFIG.wakeWord + " stone\".");
                            return 1;
                        })))
                .then(CommandManager.literal("heard")
                        .then(CommandManager.literal("on").executes(ctx -> {
                            VoiceVanishMod.CONFIG.showHeard = true;
                            VoiceVanishMod.CONFIG.save();
                            reply(ctx.getSource(), "Now showing what was heard on the action bar.");
                            return 1;
                        }))
                        .then(CommandManager.literal("off").executes(ctx -> {
                            VoiceVanishMod.CONFIG.showHeard = false;
                            VoiceVanishMod.CONFIG.save();
                            reply(ctx.getSource(), "Heard-text display is off.");
                            return 1;
                        }))));
    }

    private static void reply(ServerCommandSource source, String message) {
        source.sendFeedback(() -> Text.literal(message), false);
    }
}
