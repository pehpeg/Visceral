package com.visceral.client;

import com.visceral.Visceral;
import com.visceral.VisceralConfig;
import com.visceral.client.BloodTypes.Type;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.IResource;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Wounds: hurt creatures get bloody. The more hurt, the more blood soaks into their skin, fur or
 * scales (four levels); creatures missing a limb and corpses are soaked worst. Their armour too.
 *
 * Works on any model, modded ones included: the blood isn't painted into the creature's texture
 * (every model lays its texture out differently) but projected onto the model as it's drawn, through
 * a spare texture slot, so each body part gets its own pattern. It darkens the creature's own
 * colours toward the blood's, so it's lit like the rest of the creature and never glows.
 */
@Mod.EventBusSubscriber(modid = Visceral.MODID, value = Side.CLIENT)
public final class Wounds {
    private Wounds() {}

    private static final ResourceLocation MASKS = new ResourceLocation(Visceral.MODID, "textures/fx/wounds.png");
    private static final int N = 128;
    private static int[][] masks;          // 4 levels of blood (alpha 0-255)
    private static boolean loadFailed;
    private static final Map<Integer, DynamicTexture> TEX = new HashMap<>();
    private static final FloatBuffer PLANE = BufferUtils.createFloatBuffer(4);
    private static int active;
    /** Forced levels: corpses and gibs (entity id -> level). */
    private static final Map<Integer, Integer> FORCED = new HashMap<>();

    static boolean on() {
        return VisceralConfig.enabled && VisceralConfig.extras.wounds;
    }

    /** How bloody a creature is: 0 none, 1-4. */
    static int level(EntityLivingBase e) {
        Integer f = FORCED.get(e.getEntityId());
        if (f != null) return f;
        if (e.deathTime > 0) return 4;
        float max = Math.max(1f, e.getMaxHealth());
        float hurt = 1f - e.getHealth() / max;
        int lv = hurt < 0.12f ? 0 : hurt < 0.35f ? 1 : hurt < 0.6f ? 2 : hurt < 0.8f ? 3 : 4;
        if (Gibs.lost(e.getEntityId()) != null) lv = Math.max(lv, 3);
        return lv;
    }

    static void force(int entityId, int lv) {
        FORCED.put(entityId, lv);
    }

    static void forget(int entityId) {
        FORCED.remove(entityId);
    }

    // ------------------------------------------------------------------ while a creature is drawn

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onRenderPre(RenderLivingEvent.Pre<EntityLivingBase> event) {
        Guard.run("Wounds.onRenderPre", () -> onRenderPre0(event));
    }

    private static void onRenderPre0(RenderLivingEvent.Pre<EntityLivingBase> event) {
        if (active > 0) end();   // a creature before this one never finished (another mod skipped its Post)
        if (!on()) return;
        EntityLivingBase e = event.getEntity();
        int lv = level(e);
        if (lv <= 0) return;
        Type t = BloodTypes.of(e);
        if (t.none() || t.glows()) return;   // no blood soaking into a ghost, a golem or a blaze
        if (begin(t.color, lv, e.getEntityId())) active = 1;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderPost(RenderLivingEvent.Post<EntityLivingBase> event) {
        Guard.run("Wounds.onRenderPost", () -> onRenderPost0(event));
    }

    private static void onRenderPost0(RenderLivingEvent.Post<EntityLivingBase> event) {
        if (active > 0) end();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderWorld(RenderWorldLastEvent event) {
        Guard.run("Wounds.onRenderWorld", () -> onRenderWorld0(event));
    }

    private static void onRenderWorld0(RenderWorldLastEvent event) {
        if (active > 0) end();
    }

    /**
     * Turns the blood on for what's drawn next: texture slot 2 holds the blood pattern for this
     * colour and level, its coordinates made from each part's own position (so it needs no UVs),
     * multiplied over the colours below it.
     */
    static boolean begin(int color, int lv, int seed) {
        DynamicTexture tex = texture(color, lv);
        if (tex == null) return false;
        try {
            GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit + 1);
            GlStateManager.enableTexture2D();
            GlStateManager.bindTexture(tex.getGlTextureId());
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
            float k = 1.7f, o1 = (seed * 0.6180339f) % 1f, o2 = (seed * 0.4142135f) % 1f;
            GL11.glTexGeni(GL11.GL_S, GL11.GL_TEXTURE_GEN_MODE, GL11.GL_OBJECT_LINEAR);
            PLANE.clear(); PLANE.put(k).put(0.31f * k).put(0.77f * k).put(o1).flip();
            GL11.glTexGen(GL11.GL_S, GL11.GL_OBJECT_PLANE, PLANE);
            GL11.glTexGeni(GL11.GL_T, GL11.GL_TEXTURE_GEN_MODE, GL11.GL_OBJECT_LINEAR);
            PLANE.clear(); PLANE.put(0.23f * k).put(k).put(0.53f * k).put(o2).flip();
            GL11.glTexGen(GL11.GL_T, GL11.GL_OBJECT_PLANE, PLANE);
            // (straight to GL, not GlStateManager: its texgen switches aren't kept per slot, and the
            // End portal relies on them for slot 0)
            GL11.glEnable(GL11.GL_TEXTURE_GEN_S);
            GL11.glEnable(GL11.GL_TEXTURE_GEN_T);
            return true;
        } finally {
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        }
    }

    static void end() {
        active = 0;
        GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit + 1);
        GL11.glDisable(GL11.GL_TEXTURE_GEN_S);
        GL11.glDisable(GL11.GL_TEXTURE_GEN_T);
        GlStateManager.disableTexture2D();
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
    }

    // ------------------------------------------------------------------ the textures

    /** The pattern for one blood colour and level: white (no change) where clean, the blood's colour where soaked. */
    private static DynamicTexture texture(int color, int lv) {
        int key = (color << 3) | lv;
        DynamicTexture t = TEX.get(key);
        if (t != null) return t;
        if (!loadMasks()) return null;
        t = new DynamicTexture(N, N);
        int[] px = t.getTextureData();
        int[] m = masks[lv - 1];
        // soaked blood is darker than splashed blood; and it never goes fully black on the creature
        float br = ((color >> 16) & 255) / 255f, bg = ((color >> 8) & 255) / 255f, bb = (color & 255) / 255f;
        float lift = 1.25f;
        br = Math.min(1f, br * lift); bg = Math.min(1f, bg * lift); bb = Math.min(1f, bb * lift);
        for (int i = 0; i < N * N; i++) {
            float a = m[i] / 255f * 0.92f;
            int r = (int) (255 * (1f - a + a * br)), g = (int) (255 * (1f - a + a * bg)), b = (int) (255 * (1f - a + a * bb));
            px[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        t.updateDynamicTexture();
        TEX.put(key, t);
        return t;
    }

    private static boolean loadMasks() {
        if (masks != null) return true;
        if (loadFailed) return false;
        try {
            IResource res = Minecraft.getMinecraft().getResourceManager().getResource(MASKS);
            BufferedImage img;
            try (InputStream in = res.getInputStream()) {
                img = ImageIO.read(in);
            }
            masks = new int[4][N * N];
            for (int lv = 0; lv < 4; lv++) {
                for (int y = 0; y < N; y++) {
                    for (int x = 0; x < N; x++) masks[lv][y * N + x] = (img.getRGB(lv * N + x, y) >>> 24) & 255;
                }
            }
            return true;
        } catch (Exception ex) {
            loadFailed = true;
            Visceral.LOG.warn("Visceral: couldn't load the wound textures: {}", ex.toString());
            return false;
        }
    }

    /** After a resource reload the textures are made again (and a resource pack's wounds.png is used). */
    public static void reload() {
        for (DynamicTexture t : TEX.values()) t.deleteGlTexture();
        TEX.clear();
        masks = null;
        loadFailed = false;
    }
}
