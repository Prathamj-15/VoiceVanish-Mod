package com.voicevanish;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

/** /voicevanish on | off | status | radius <chunks> | delay <seconds> | sound on|off | confidence <0-1> | heard on|off */
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
                            + ", delay=" + c.delaySeconds + "s"
                            + ", sound=" + c.playSound
                            + ", speechEngine=" + VoiceVanishMod.SPEECH.describe());
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
                .then(CommandManager.literal("delay")
                        .then(CommandManager.argument("seconds", IntegerArgumentType.integer(0, 120))
                                .executes(ctx -> {
                                    VoiceVanishMod.CONFIG.delaySeconds = IntegerArgumentType.getInteger(ctx, "seconds");
                                    VoiceVanishMod.CONFIG.save();
                                    reply(ctx.getSource(), "Things now vanish " + VoiceVanishMod.CONFIG.delaySeconds
                                            + " seconds after the name is heard.");
                                    return 1;
                                })))
                .then(CommandManager.literal("sound")
                        .then(CommandManager.literal("on").executes(ctx -> {
                            VoiceVanishMod.CONFIG.playSound = true;
                            VoiceVanishMod.CONFIG.save();
                            reply(ctx.getSource(), "Vanish sound is on.");
                            return 1;
                        }))
                        .then(CommandManager.literal("off").executes(ctx -> {
                            VoiceVanishMod.CONFIG.playSound = false;
                            VoiceVanishMod.CONFIG.save();
                            reply(ctx.getSource(), "Vanish sound is off.");
                            return 1;
                        })))
                .then(CommandManager.literal("confidence")
                        .then(CommandManager.argument("value", DoubleArgumentType.doubleArg(0.0, 1.0))
                                .executes(ctx -> {
                                    VoiceVanishMod.CONFIG.minConfidence = DoubleArgumentType.getDouble(ctx, "value");
                                    VoiceVanishMod.CONFIG.save();
                                    reply(ctx.getSource(), "Minimum confidence set to " + VoiceVanishMod.CONFIG.minConfidence
                                            + ". Lower it if words are missed, raise it if things vanish by mistake.");
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
