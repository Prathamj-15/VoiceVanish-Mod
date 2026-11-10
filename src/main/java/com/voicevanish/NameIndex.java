package com.voicevanish;

import net.minecraft.block.Block;
import net.minecraft.entity.EntityType;
import net.minecraft.registry.Registries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns registry ids into spoken names ("oak_log" -> "oak log") and finds them inside a sentence.
 */
public final class NameIndex {
    /** One spoken name and everything it refers to (a name can be both a block and a mob, e.g. "tnt"). */
    public record Target(String label, Set<Block> blocks, Set<EntityType<?>> entities) {}

    private static final Set<String> SKIP_BLOCKS = Set.of("air", "cave_air", "void_air");
    private static final Set<String> SKIP_ENTITIES = Set.of(
            "player", "item", "marker", "area_effect_cloud", "lightning_bolt", "experience_orb");

    private final Map<String, Set<Block>> blocks = new HashMap<>();
    private final Map<String, Set<EntityType<?>>> entities = new HashMap<>();
    private int maxWords = 1;

    /** Call once the server has started so every mod's blocks and mobs are registered. */
    public void build() {
        blocks.clear();
        entities.clear();
        maxWords = 1;
        for (Block block : Registries.BLOCK) {
            String path = Registries.BLOCK.getId(block).getPath();
            if (SKIP_BLOCKS.contains(path)) continue;
            String name = path.replace('_', ' ');
            blocks.computeIfAbsent(name, k -> new HashSet<>()).add(block);
            maxWords = Math.max(maxWords, wordCount(name));
        }
        for (EntityType<?> type : Registries.ENTITY_TYPE) {
            String path = Registries.ENTITY_TYPE.getId(type).getPath();
            if (SKIP_ENTITIES.contains(path)) continue;
            String name = path.replace('_', ' ');
            entities.computeIfAbsent(name, k -> new HashSet<>()).add(type);
            maxWords = Math.max(maxWords, wordCount(name));
        }
        alias("dragon", EntityType.ENDER_DRAGON);
        VoiceVanishMod.LOGGER.info("Name index ready: {} block names, {} mob names", blocks.size(), entities.size());
    }

    private void alias(String spoken, EntityType<?> type) {
        entities.computeIfAbsent(spoken, k -> new HashSet<>()).add(type);
    }

    /** Finds every block / mob name in the sentence. Longest names win ("wither skeleton" beats "wither"). */
    public List<Target> match(String sentence) {
        String[] words = sentence.toLowerCase().trim().split("\\s+");
        Map<String, Target> found = new LinkedHashMap<>();
        int i = 0;
        while (i < words.length) {
            boolean matched = false;
            int longest = Math.min(maxWords, words.length - i);
            for (int n = longest; n >= 1 && !matched; n--) {
                String phrase = String.join(" ", java.util.Arrays.copyOfRange(words, i, i + n));
                for (String candidate : variants(phrase)) {
                    Set<Block> b = blocks.get(candidate);
                    Set<EntityType<?>> e = entities.get(candidate);
                    if (b != null || e != null) {
                        found.putIfAbsent(candidate, new Target(candidate,
                                b == null ? Set.of() : b, e == null ? Set.of() : e));
                        i += n;
                        matched = true;
                        break;
                    }
                }
            }
            if (!matched) i++;
        }
        return new ArrayList<>(found.values());
    }

    /** "zombies" -> "zombie", "slimes" -> "slime", "bees" -> "bee", "ladies" -> "lady" style endings. */
    private static List<String> variants(String phrase) {
        List<String> out = new ArrayList<>(4);
        out.add(phrase);
        if (phrase.endsWith("ies") && phrase.length() > 3) out.add(phrase.substring(0, phrase.length() - 3) + "y");
        if (phrase.endsWith("es") && phrase.length() > 2) out.add(phrase.substring(0, phrase.length() - 2));
        if (phrase.endsWith("s") && phrase.length() > 1) out.add(phrase.substring(0, phrase.length() - 1));
        return out;
    }

    private static int wordCount(String name) {
        return name.split(" ").length;
    }
}
