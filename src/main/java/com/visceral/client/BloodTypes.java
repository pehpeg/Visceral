package com.visceral.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.visceral.Visceral;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.boss.EntityDragon;
import net.minecraft.entity.boss.EntityWither;
import net.minecraft.entity.monster.AbstractSkeleton;
import net.minecraft.entity.monster.EntityBlaze;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.entity.monster.EntityEnderman;
import net.minecraft.entity.monster.EntityEndermite;
import net.minecraft.entity.monster.EntityGhast;
import net.minecraft.entity.monster.EntityGolem;
import net.minecraft.entity.monster.EntityIronGolem;
import net.minecraft.entity.monster.EntityMagmaCube;
import net.minecraft.entity.monster.EntityShulker;
import net.minecraft.entity.monster.EntitySilverfish;
import net.minecraft.entity.monster.EntitySlime;
import net.minecraft.entity.monster.EntitySnowman;
import net.minecraft.entity.monster.EntitySpider;
import net.minecraft.entity.monster.EntityVex;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.entity.passive.EntitySquid;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What each creature bleeds. config/visceral-blood.json has two parts:
 *  - "types": the kinds of blood (a colour, a colour for its chunks, and a style: liquid, slime,
 *    dust, sparks, embers, ecto or none). Add your own.
 *  - "creatures": creature id -> type, e.g. "minecraft:zombie": "rotten". Wildcards work:
 *    "somemod:*" or "*_golem".
 * A creature that isn't listed is worked out from what it is (a skeleton of any mod is bone, a slime
 * is slime, a golem is metal...) and then from words in its id ("ghost", "construct", "spider"...),
 * so modded creatures get sensible blood without anyone listing them. Everything else bleeds red.
 */
public final class BloodTypes {
    private BloodTypes() {}

    public enum Style {
        /** Droplets that fall and stain, a mist at the hit, meat chunks. */
        LIQUID,
        /** Like liquid but thicker: bigger, slower blobs and glossy stains. */
        SLIME,
        /** Bone dust puffs and bone shards; no stains. */
        DUST,
        /** Metal sparks and bits; no stains. */
        SPARKS,
        /** Glowing embers and smoke, scorch marks. */
        EMBERS,
        /** Glowing drops that float a little and fade; no stains. */
        ECTO,
        /** No gore at all for this creature. */
        NONE
    }

    public static final class Type {
        public final String name;
        public final int color, chunkColor;
        public final Style style;

        Type(String name, int color, int chunkColor, Style style) {
            this.name = name;
            this.color = color;
            this.chunkColor = chunkColor;
            this.style = style;
        }

        public boolean stains() {
            return style == Style.LIQUID || style == Style.SLIME || style == Style.EMBERS;
        }

        public boolean glows() {
            return style == Style.EMBERS || style == Style.ECTO || style == Style.SPARKS;
        }

        public boolean none() {
            return style == Style.NONE;
        }
    }

    private static final Map<String, Type> TYPES = new LinkedHashMap<>();
    private static final Map<String, String> EXACT = new HashMap<>();
    private static final List<Object[]> WILD = new ArrayList<>();   // {Pattern, typeName}
    private static final Map<Class<?>, Type> CACHE = new HashMap<>();
    public static final Type RED = new Type("red", 0x8A0B0B, 0x9C2A2A, Style.LIQUID);
    private static File file;

    public static void init(File f) {
        file = f;
        reload();
    }

    public static void reload() {
        TYPES.clear();
        EXACT.clear();
        WILD.clear();
        CACHE.clear();
        defaults();
        if (file == null) return;
        try {
            if (!file.exists()) writeDefaults();
            try (Reader r = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                JsonObject root = new JsonParser().parse(r).getAsJsonObject();
                if (root.has("types")) {
                    for (Map.Entry<String, JsonElement> en : root.getAsJsonObject("types").entrySet()) {
                        try {
                            JsonObject o = en.getValue().getAsJsonObject();
                            int c = parseColor(o.get("color").getAsString());
                            int cc = o.has("chunkColor") ? parseColor(o.get("chunkColor").getAsString()) : c;
                            Style st = Style.valueOf(o.get("style").getAsString().trim().toUpperCase(Locale.ROOT));
                            String n = en.getKey().toLowerCase(Locale.ROOT);
                            TYPES.put(n, new Type(n, c, cc, st));
                        } catch (RuntimeException ex) {
                            Visceral.LOG.warn("visceral-blood.json: skipping blood type '{}': {}", en.getKey(), ex.toString());
                        }
                    }
                }
                if (root.has("creatures")) {
                    for (Map.Entry<String, JsonElement> en : root.getAsJsonObject("creatures").entrySet()) {
                        String key = en.getKey().trim().toLowerCase(Locale.ROOT);
                        String type = en.getValue().getAsString().trim().toLowerCase(Locale.ROOT);
                        if (key.startsWith("_")) continue;
                        if (key.contains("*")) {
                            String rx = ("\\Q" + key + "\\E").replace("*", "\\E.*\\Q");
                            WILD.add(new Object[]{Pattern.compile(rx), type});
                        } else {
                            EXACT.put(key.contains(":") ? key : "minecraft:" + key, type);
                        }
                    }
                }
            }
        } catch (Exception ex) {
            Visceral.LOG.error("Couldn't read {} (using built-in blood types): {}", file, ex.toString());
        }
    }

    private static int parseColor(String s) {
        s = s.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2);
        return Integer.parseInt(s, 16) & 0xFFFFFF;
    }

    private static void type(String n, int c, int cc, Style s) {
        TYPES.put(n, new Type(n, c, cc, s));
    }

    private static void defaults() {
        type("red", 0x8A0B0B, 0x9C2A2A, Style.LIQUID);
        type("dark", 0x5E0A0A, 0x7A2020, Style.LIQUID);
        type("rotten", 0x4A1A0C, 0x5D4A2A, Style.LIQUID);
        type("creeper", 0x3E9A22, 0x2E6E1C, Style.LIQUID);
        type("insect", 0x8FA126, 0x4A3A22, Style.LIQUID);
        type("ender", 0x7A1FB8, 0x2A1240, Style.LIQUID);
        type("ink", 0x14141E, 0x2A2A3A, Style.LIQUID);
        type("sap", 0x6E8A2A, 0x6A4A2A, Style.LIQUID);
        type("slime", 0x6FD24A, 0x7FDC5A, Style.SLIME);
        type("magma", 0xFF6A10, 0x3A1A12, Style.EMBERS);
        type("bone", 0xE8E2D0, 0xECE6D6, Style.DUST);
        type("snow", 0xF4FAFF, 0xFFFFFF, Style.DUST);
        type("metal", 0xFFC860, 0x8A8A8A, Style.SPARKS);
        type("embers", 0xFF8A20, 0x3A2A22, Style.EMBERS);
        type("ecto", 0x9EE8FF, 0xCFF4FF, Style.ECTO);
        type("ghast", 0xF0F0F0, 0xE8E8E8, Style.ECTO);
        type("none", 0x000000, 0x000000, Style.NONE);
    }

    private static void writeDefaults() throws Exception {
        JsonObject root = new JsonObject();
        root.addProperty("_readme", "Blood per creature. 'types': name -> colour (#RRGGBB), chunkColor, style "
                + "(liquid, slime, dust, sparks, embers, ecto, none). 'creatures': creature id -> type name; wildcards "
                + "like \"somemod:*\" or \"*_golem\" work. Creatures not listed are worked out from what they are and "
                + "their id, then bleed red. Press F3+T in game to reload this file.");
        JsonObject types = new JsonObject();
        for (Type t : TYPES.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("color", String.format("#%06X", t.color));
            o.addProperty("chunkColor", String.format("#%06X", t.chunkColor));
            o.addProperty("style", t.style.name().toLowerCase(Locale.ROOT));
            types.add(t.name, o);
        }
        root.add("types", types);
        JsonObject cr = new JsonObject();
        cr.addProperty("_example_somemod:*", "red");
        cr.addProperty("minecraft:zombie", "rotten");
        cr.addProperty("minecraft:husk", "rotten");
        cr.addProperty("minecraft:zombie_villager", "rotten");
        cr.addProperty("minecraft:zombie_pigman", "rotten");
        cr.addProperty("minecraft:zombie_horse", "rotten");
        cr.addProperty("minecraft:giant", "rotten");
        cr.addProperty("minecraft:skeleton", "bone");
        cr.addProperty("minecraft:stray", "bone");
        cr.addProperty("minecraft:wither_skeleton", "bone");
        cr.addProperty("minecraft:skeleton_horse", "bone");
        cr.addProperty("minecraft:creeper", "creeper");
        cr.addProperty("minecraft:spider", "insect");
        cr.addProperty("minecraft:cave_spider", "insect");
        cr.addProperty("minecraft:silverfish", "insect");
        cr.addProperty("minecraft:endermite", "ender");
        cr.addProperty("minecraft:enderman", "ender");
        cr.addProperty("minecraft:shulker", "ender");
        cr.addProperty("minecraft:ender_dragon", "ender");
        cr.addProperty("minecraft:slime", "slime");
        cr.addProperty("minecraft:magma_cube", "magma");
        cr.addProperty("minecraft:blaze", "embers");
        cr.addProperty("minecraft:ghast", "ghast");
        cr.addProperty("minecraft:vex", "ecto");
        cr.addProperty("minecraft:villager_golem", "metal");
        cr.addProperty("minecraft:snowman", "snow");
        cr.addProperty("minecraft:squid", "ink");
        cr.addProperty("minecraft:wither", "bone");
        cr.addProperty("minecraft:armor_stand", "none");
        root.add("creatures", cr);
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        file.getParentFile().mkdirs();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            gson.toJson(root, w);
        }
    }

    // ------------------------------------------------------------------ lookup

    public static Type of(Entity e) {
        String id = idOf(e);
        // the listed ids win (exact, then wildcards); not cached by class, as one class can have many ids
        if (id != null) {
            String t = EXACT.get(id);
            if (t != null && TYPES.containsKey(t)) return TYPES.get(t);
            for (Object[] w : WILD) {
                if (((Pattern) w[0]).matcher(id).matches() && TYPES.containsKey((String) w[1])) return TYPES.get((String) w[1]);
            }
        }
        Type c = CACHE.get(e.getClass());
        if (c == null) {
            c = guess(e, id);
            CACHE.put(e.getClass(), c);
        }
        return c;
    }

    private static String idOf(Entity e) {
        if (e instanceof EntityPlayer) return "minecraft:player";
        ResourceLocation rl = EntityList.getKey(e);
        return rl == null ? null : rl.toString().toLowerCase(Locale.ROOT);
    }

    private static Type t(String n) {
        Type t = TYPES.get(n);
        return t != null ? t : RED;
    }

    private static boolean has(String s, String... words) {
        for (String w : words) if (s.contains(w)) return true;
        return false;
    }

    /** What a creature nobody listed probably bleeds: what it is first, then words in its id. */
    private static Type guess(Entity e, String id) {
        // what it is (modded creatures are often built on these)
        if (e instanceof EntityMagmaCube) return t("magma");
        if (e instanceof EntitySlime) return t("slime");
        if (e instanceof AbstractSkeleton) return t("bone");
        if (e instanceof EntityIronGolem) return t("metal");
        if (e instanceof EntitySnowman) return t("snow");
        if (e instanceof EntityBlaze) return t("embers");
        if (e instanceof EntityGhast) return t("ghast");
        if (e instanceof EntityVex) return t("ecto");
        if (e instanceof EntityEnderman || e instanceof EntityEndermite || e instanceof EntityShulker || e instanceof EntityDragon) return t("ender");
        if (e instanceof EntityCreeper) return t("creeper");
        if (e instanceof EntitySpider || e instanceof EntitySilverfish) return t("insect");
        if (e instanceof EntityWither) return t("bone");
        if (e instanceof EntitySquid) return t("ink");
        if (e instanceof EntityZombie) return t("rotten");
        // words in its id (the part after the mod name)
        if (id != null) {
            String p = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
            if (has(p, "armor_stand", "armorstand", "mannequin", "dummy", "target", "statue")) return t("none");
            if (has(p, "skeleton", "skelly", "bone", "skull", "lich", "boneling")) return t("bone");
            if (has(p, "magma", "lava", "molten")) return t("magma");
            if (has(p, "slime", "ooze", "jelly", "blob")) return t("slime");
            if (has(p, "golem", "construct", "automaton", "robot", "mech", "sentinel", "turret", "clockwork", "drone", "android", "cyborg")) return t("metal");
            if (has(p, "ghost", "spirit", "wraith", "wisp", "phantom", "specter", "spectre", "banshee", "soul", "spook", "shade", "apparition", "poltergeist")) return t("ecto");
            if (has(p, "blaze", "fire", "flame", "ember", "infernal", "inferno", "ifrit", "salamander", "cinder", "pyro", "hellhound")) return t("embers");
            if (has(p, "ender", "void", "shulker")) return t("ender");
            if (has(p, "snow", "frost", "yeti", "glacial")) return t("snow");
            if (has(p, "zombie", "ghoul", "undead", "draugr", "mummy", "rotting", "rotten", "corpse", "husk", "drowned", "revenant", "wight")) return t("rotten");
            if (has(p, "spider", "bug", "insect", "beetle", "scorpion", "ant_", "wasp", "bee", "mantis", "worm", "larva", "locust", "centipede", "roach", "fly", "moth", "crawler")) return t("insect");
            if (has(p, "treant", "ent_", "plant", "vine", "shroom", "mushroom", "dryad", "sprout", "root", "thorn")) return t("sap");
            if (has(p, "squid", "octopus", "kraken", "cuttle")) return t("ink");
            if (has(p, "demon", "devil")) return t("dark");
        }
        if (e instanceof EntityGolem) return t("metal");
        if (e instanceof EntityLivingBase && ((EntityLivingBase) e).isEntityUndead()) return t("rotten");
        return t("red");
    }
}
