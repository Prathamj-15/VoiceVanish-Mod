package com.voicevanish;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
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
 *
 * Matching ignores spaces, so "deep slate", "deepslate" and "Deep-Slate" are all the same thing,
 * and plurals ("pigs", "zombies") work too.
 */
public final class NameIndex {
    /** One spoken name and everything it refers to (a name can be both a block and a mob, e.g. "tnt"). */
    public record Target(String label, Set<Block> blocks, Set<EntityType<?>> entities) {}

    private static final Set<String> SKIP_BLOCKS = Set.of("air", "cave_air", "void_air");
    private static final Set<String> SKIP_ENTITIES = Set.of(
            "player", "item", "marker", "area_effect_cloud", "lightning_bolt", "experience_orb");

    private final Map<String, Set<Block>> blocks = new HashMap<>();
    private final Map<String, Set<EntityType<?>>> entities = new HashMap<>();
    /** Name with the spaces removed -> the name ("deepslate bricks" -> key "deepslatebricks"). */
    private final Map<String, String> compact = new HashMap<>();
    private int maxWords = 1;

    /** Call once the server has started so every mod's blocks and mobs are registered. */
    public void build() {
        blocks.clear();
        entities.clear();
        compact.clear();
        maxWords = 1;

        for (Block block : Registries.BLOCK) {
            String path = Registries.BLOCK.getId(block).getPath();
            if (SKIP_BLOCKS.contains(path)) continue;
            blocks.computeIfAbsent(path.replace('_', ' '), k -> new HashSet<>()).add(block);
        }
        for (EntityType<?> type : Registries.ENTITY_TYPE) {
            String path = Registries.ENTITY_TYPE.getId(type).getPath();
            if (SKIP_ENTITIES.contains(path)) continue;
            entities.computeIfAbsent(path.replace('_', ' '), k -> new HashSet<>()).add(type);
        }

        // Easy-to-say names
        entities.computeIfAbsent("dragon", k -> new HashSet<>()).add(EntityType.ENDER_DRAGON);
        entities.computeIfAbsent("golem", k -> new HashSet<>()).addAll(Set.of(EntityType.IRON_GOLEM, EntityType.SNOW_GOLEM));
        Set<Block> grass = blocks.computeIfAbsent("grass", k -> new HashSet<>());
        grass.add(Blocks.GRASS_BLOCK);
        grass.add(Blocks.SHORT_GRASS);
        blocks.computeIfAbsent("cobble", k -> new HashSet<>()).add(Blocks.COBBLESTONE);

        for (String name : allNames()) {
            compact.putIfAbsent(name.replace(" ", ""), name);
            maxWords = Math.max(maxWords, name.split(" ").length);
        }
        VoiceVanishMod.LOGGER.info("Name index ready: {} block names, {} mob names", blocks.size(), entities.size());
    }

    private Set<String> allNames() {
        Set<String> all = new HashSet<>(blocks.keySet());
        all.addAll(entities.keySet());
        return all;
    }

    /** Every spoken block and mob name, used to tell the speech recognizer what to listen for. */
    public Set<String> spokenNames() {
        return allNames();
    }

    /** Finds every block / mob name in the sentence. Longest names win ("wither skeleton" beats "wither"). */
    public List<Target> match(String sentence) {
        String cleaned = sentence.toLowerCase().replaceAll("[^a-z]+", " ").trim();
        if (cleaned.isEmpty()) return List.of();
        String[] words = cleaned.split(" ");

        Map<String, Target> found = new LinkedHashMap<>();
        // Spoken names can be split into more words than the registry id ("deep slate"), so look a bit further.
        int window = maxWords + 2;
        int i = 0;
        while (i < words.length) {
            int matchedLength = 0;
            int longest = Math.min(window, words.length - i);
            for (int n = longest; n >= 1 && matchedLength == 0; n--) {
                StringBuilder joined = new StringBuilder();
                for (int k = 0; k < n; k++) joined.append(words[i + k]);
                for (String key : variants(joined.toString())) {
                    String name = compact.get(key);
                    if (name != null) {
                        found.putIfAbsent(name, new Target(name,
                                blocks.getOrDefault(name, Set.of()),
                                entities.getOrDefault(name, Set.of())));
                        matchedLength = n;
                        break;
                    }
                }
            }
            i += Math.max(1, matchedLength);
        }
        return new ArrayList<>(found.values());
    }

    /** "zombies" -> "zombie", "pigs" -> "pig", "ladies" -> "lady" style endings. */
    private static List<String> variants(String text) {
        List<String> out = new ArrayList<>(4);
        out.add(text);
        if (text.endsWith("ies") && text.length() > 3) out.add(text.substring(0, text.length() - 3) + "y");
        if (text.endsWith("es") && text.length() > 2) out.add(text.substring(0, text.length() - 2));
        if (text.endsWith("s") && text.length() > 1) out.add(text.substring(0, text.length() - 1));
        return out;
    }
}
