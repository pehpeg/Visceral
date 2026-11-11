package com.visceral.client;

import net.minecraft.item.Item;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.item.ItemTool;
import net.minecraft.util.ResourceLocation;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What kind of weapon something is, worked out from the item itself so modded weapons need nothing
 * set up: what it's built on (a sword, an axe, a tool), what tool it counts as, and words in its
 * name (a "warhammer", a "spear", a "cleaver"). Swords cut, axes chop, hammers and fists crush,
 * spears and daggers pierce.
 */
final class Weapons {
    private Weapons() {}

    enum Kind {
        NONE,     // fists, or nothing that's a weapon
        SHARP,    // swords, knives: clean cuts
        CHOP,     // axes, cleavers, scythes: heads and limbs come off
        BLUNT,    // hammers, maces, clubs, pickaxes: crushed, not cut
        PIERCE    // spears, daggers, rapiers, arrows: in and out the far side
    }

    private static final Map<Item, Kind> CACHE = new ConcurrentHashMap<>();

    private static final String[] BLUNT = {"hammer", "mace", "club", "maul", "flail", "morning", "cudgel", "bludgeon", "mallet",
            "warhammer", "sledge", "bat", "staff", "rod", "baton", "knuckle", "gauntlet", "fist", "pickaxe", "shovel", "spade", "anchor"};
    private static final String[] CHOP = {"axe", "cleaver", "halberd", "glaive", "scythe", "sickle", "guillotine", "executioner",
            "bardiche", "poleaxe", "battleaxe", "greataxe", "waraxe", "hatchet", "tomahawk", "chainsaw", "greatsword", "claymore", "zweihander", "machete"};
    private static final String[] PIERCE = {"spear", "trident", "pike", "lance", "javelin", "rapier", "estoc", "dagger", "stiletto",
            "sai", "harpoon", "fork", "pitchfork", "needle", "spike", "bayonet", "tanto", "katar"};
    private static final String[] SHARP = {"sword", "blade", "katana", "sabre", "saber", "scimitar", "knife", "cutlass", "longsword",
            "shortsword", "falchion", "claw", "talon", "kukri", "wakizashi", "nodachi", "tachi"};

    static Kind kind(ItemStack s) {
        if (s == null || s.isEmpty()) return Kind.NONE;
        Item item = s.getItem();
        Kind k = CACHE.get(item);
        if (k == null) {
            k = classify(s);
            CACHE.put(item, k);
        }
        return k;
    }

    private static Kind classify(ItemStack s) {
        Item item = s.getItem();
        ResourceLocation rl = item.getRegistryName();
        String name = (rl == null ? "" : rl.getPath()) + " " + item.getTranslationKey(s);
        name = name.toLowerCase(Locale.ROOT);
        // the words in the name come first: a modded "warhammer" built on ItemSword is still a hammer
        // (checked from the most specific: "pickaxe" before "axe", "greatsword" before "sword")
        if (has(name, BLUNT)) return Kind.BLUNT;
        if (has(name, PIERCE)) return Kind.PIERCE;
        if (has(name, CHOP)) return Kind.CHOP;
        if (has(name, SHARP)) return Kind.SHARP;
        if (item instanceof ItemAxe) return Kind.CHOP;
        if (item instanceof ItemSword) return Kind.SHARP;
        try {
            Set<String> classes = item.getToolClasses(s);
            if (classes.contains("axe")) return Kind.CHOP;
            if (classes.contains("pickaxe") || classes.contains("shovel")) return Kind.BLUNT;
            if (classes.contains("sword")) return Kind.SHARP;
        } catch (RuntimeException ignored) {
        }
        if (item instanceof ItemTool) return Kind.BLUNT;
        return Kind.NONE;
    }

    /** A word in the name: short ones ("bat", "rod", "sai") only as a whole word, so "combat_knife" isn't a bat. */
    private static boolean has(String name, String[] words) {
        String[] tokens = name.split("[^a-z]+");
        for (String w : words) {
            for (String t : tokens) {
                if (w.length() <= 4 ? t.equals(w) || t.equals(w + "s") : t.contains(w)) return true;
            }
        }
        return false;
    }
}
