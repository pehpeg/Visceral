package com.visceral.client;

import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * Every Visceral drawing handler runs inside push() / pop(): whatever it changes in the graphics
 * state (blending, alpha test, depth, culling, lighting, fog, the colour...), the next mod finds it
 * exactly as it was before, so nothing Visceral draws can change how anyone else's HUD, world
 * overlay or screen looks. The game's own state cache (GlStateManager) is kept in step too, so a
 * later "set this" from any mod really reaches the graphics card.
 */
public final class GlGuard {
    private GlGuard() {}

    private static final int[] CAPS = {GL11.GL_BLEND, GL11.GL_ALPHA_TEST, GL11.GL_TEXTURE_2D, GL11.GL_DEPTH_TEST,
            GL11.GL_CULL_FACE, GL11.GL_LIGHTING, GL11.GL_FOG, GL11.GL_COLOR_MATERIAL};
    private static final int DEPTH = 8;
    private static final int GL_CURRENT_PROGRAM = 0x8B8D;   // nesting (a handler that posts another event)

    private static final class Saved {
        final boolean[] caps = new boolean[CAPS.length];
        boolean depthMask;
        int depthFunc, shade, srcRgb, dstRgb, srcA, dstA;
        final float[] color = new float[4];
        int activeUnit, program;
        boolean lightmap, world;
    }

    private static final Saved[] STACK = new Saved[DEPTH];
    private static int top;
    private static final FloatBuffer F16 = BufferUtils.createFloatBuffer(16);
    private static final IntBuffer I16 = BufferUtils.createIntBuffer(16);

    static {
        for (int i = 0; i < DEPTH; i++) STACK[i] = new Saved();
    }

    public static void push() {
        if (top >= DEPTH) { top++; return; }   // (too deep: only counted)
        Saved s = STACK[top++];
        s.world = false;
        try {
            s.activeUnit = getInt(org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE);
            s.program = 0;
            try { s.program = getInt(GL_CURRENT_PROGRAM); } catch (RuntimeException | LinkageError ignored) { }
            unit(net.minecraft.client.renderer.OpenGlHelper.lightmapTexUnit);
            s.lightmap = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
            unit(net.minecraft.client.renderer.OpenGlHelper.defaultTexUnit);   // states below are the main unit's
            for (int i = 0; i < CAPS.length; i++) s.caps[i] = GL11.glIsEnabled(CAPS[i]);
            s.depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            s.depthFunc = getInt(GL11.GL_DEPTH_FUNC);
            s.shade = getInt(GL11.GL_SHADE_MODEL);
            s.srcRgb = getInt(GL14.GL_BLEND_SRC_RGB);
            s.dstRgb = getInt(GL14.GL_BLEND_DST_RGB);
            s.srcA = getInt(GL14.GL_BLEND_SRC_ALPHA);
            s.dstA = getInt(GL14.GL_BLEND_DST_ALPHA);
            F16.clear();
            GL11.glGetFloat(GL11.GL_CURRENT_COLOR, F16);
            for (int i = 0; i < 4; i++) s.color[i] = F16.get(i);
        } catch (RuntimeException | LinkageError e) {
            s.depthFunc = -1;   // couldn't read it: leave things be on pop
        }
    }

    /**
     * Inside a HUD handler: draw without writing depth. Big screen overlays (the underwater tint
     * along the edges) wrote it, and every mod's HUD drawn after them was then "behind" them and
     * hidden wherever they covered (the hotbar strip underwater). That's in the depth buffer, not a
     * setting, so pop() couldn't undo it: it mustn't be written at all. pop() puts the mask back.
     */
    public static void hud() {
        GlStateManager.depthMask(true);
        GlStateManager.depthMask(false);
        // and a clean start, whatever the element before left: normal blending (vanilla's crosshair is
        // drawn inverting what's behind it; drawn right after it, text came out faint), textures on,
        // no lighting, white. (pop() puts back what was there, for the next mod.)
        unit(net.minecraft.client.renderer.OpenGlHelper.defaultTexUnit);
        GlStateManager.disableBlend();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.disableTexture2D();
        GlStateManager.enableTexture2D();
        GlStateManager.enableLighting();
        GlStateManager.disableLighting();
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GlStateManager.resetColor();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    public static void pop() {
        if (top <= 0) return;
        top--;
        if (top >= DEPTH) return;
        Saved s = STACK[top];
        if (s.depthFunc == -1) return;
        try {
            unit(net.minecraft.client.renderer.OpenGlHelper.lightmapTexUnit);   // the light texture as it was
            GlStateManager.disableTexture2D();
            GlStateManager.enableTexture2D();
            if (!s.lightmap) GlStateManager.disableTexture2D();
            unit(net.minecraft.client.renderer.OpenGlHelper.defaultTexUnit);
            for (int i = 0; i < CAPS.length; i++) cap(CAPS[i], s.caps[i]);
            GlStateManager.depthMask(!s.depthMask);
            GlStateManager.depthMask(s.depthMask);
            GlStateManager.depthFunc(s.depthFunc == GL11.GL_ALWAYS ? GL11.GL_LEQUAL : GL11.GL_ALWAYS);
            GlStateManager.depthFunc(s.depthFunc);
            GlStateManager.shadeModel(s.shade == GL11.GL_FLAT ? GL11.GL_SMOOTH : GL11.GL_FLAT);
            GlStateManager.shadeModel(s.shade);
            GlStateManager.tryBlendFuncSeparate(GL11.GL_ZERO, GL11.GL_ZERO, GL11.GL_ZERO, GL11.GL_ZERO);
            GlStateManager.tryBlendFuncSeparate(s.srcRgb, s.dstRgb, s.srcA, s.dstA);
            GL11.glColor4f(s.color[0], s.color[1], s.color[2], s.color[3]);
            GlStateManager.resetColor();
            GlStateManager.color(s.color[0], s.color[1], s.color[2], s.color[3]);
            unit(s.activeUnit);                                                  // and the unit that was active
            if (s.world && s.program != 0) net.minecraft.client.renderer.OpenGlHelper.glUseProgram(s.program);   // and its shader
        } catch (RuntimeException | LinkageError ignored) {
            // nothing more to do
        }
    }

    private static int getInt(int what) {
        I16.clear();
        GL11.glGetInteger(what, I16);
        return I16.get(0);
    }

    /** Makes a texture unit active, on the card and in the game's cache, whatever the cache thought. */
    private static void unit(int u) {
        int other = u == net.minecraft.client.renderer.OpenGlHelper.defaultTexUnit
                ? net.minecraft.client.renderer.OpenGlHelper.lightmapTexUnit : net.minecraft.client.renderer.OpenGlHelper.defaultTexUnit;
        GlStateManager.setActiveTexture(other);
        GlStateManager.setActiveTexture(u);
    }

    /**
     * Inside a world handler (after push): Visceral's world effects are drawn full-bright, with the
     * game's light texture off, no fog and no lighting. Their glows are added onto the scene, so with
     * the light texture on, a dim spot multiplied them down to nearly black and they vanished (the
     * mining outline, the place flash). pop() puts all of it back as it was.
     */
    public static void world() {
        // A mod drawing the world through its own shader (dynamic lights like Lux, shader packs) may
        // leave it in use here: Visceral's plain glowing shapes went through it and came out
        // invisible (the mining outline, the place flash). Off while Visceral draws; back after.
        if (top > 0 && top <= DEPTH) {
            Saved s = STACK[top - 1];
            s.world = true;
            if (s.program != 0) {
                try { net.minecraft.client.renderer.OpenGlHelper.glUseProgram(0); } catch (RuntimeException | LinkageError ignored) { }
            }
        }
        unit(net.minecraft.client.renderer.OpenGlHelper.lightmapTexUnit);
        GlStateManager.disableTexture2D();
        unit(net.minecraft.client.renderer.OpenGlHelper.defaultTexUnit);
        GlStateManager.disableFog();
        GlStateManager.disableLighting();
    }

    /**
     * Inside a world handler (after push), for things that sit in the world like blocks do: blood and
     * gibs take the world's light (dark at night, lit by torches) and fog, so they never glow.
     * A world shader left in use is switched off the same way as in world().
     */
    public static void worldLit() {
        if (top > 0 && top <= DEPTH) {
            Saved s = STACK[top - 1];
            s.world = true;
            if (s.program != 0) {
                try { net.minecraft.client.renderer.OpenGlHelper.glUseProgram(0); } catch (RuntimeException | LinkageError ignored) { }
            }
        }
        net.minecraft.client.Minecraft.getMinecraft().entityRenderer.enableLightmap();
        unit(net.minecraft.client.renderer.OpenGlHelper.defaultTexUnit);
        GlStateManager.enableFog();
        GlStateManager.disableLighting();
    }

    /** Sets a capability on the card and in the game's cache, whatever the cache thought it was. */
    private static void cap(int c, boolean on) {
        switch (c) {
            case GL11.GL_BLEND: GlStateManager.disableBlend(); GlStateManager.enableBlend(); if (!on) GlStateManager.disableBlend(); break;
            case GL11.GL_ALPHA_TEST: GlStateManager.disableAlpha(); GlStateManager.enableAlpha(); if (!on) GlStateManager.disableAlpha(); break;
            case GL11.GL_TEXTURE_2D: GlStateManager.disableTexture2D(); GlStateManager.enableTexture2D(); if (!on) GlStateManager.disableTexture2D(); break;
            case GL11.GL_DEPTH_TEST: GlStateManager.disableDepth(); GlStateManager.enableDepth(); if (!on) GlStateManager.disableDepth(); break;
            case GL11.GL_CULL_FACE: GlStateManager.disableCull(); GlStateManager.enableCull(); if (!on) GlStateManager.disableCull(); break;
            case GL11.GL_LIGHTING: GlStateManager.disableLighting(); GlStateManager.enableLighting(); if (!on) GlStateManager.disableLighting(); break;
            case GL11.GL_FOG: GlStateManager.disableFog(); GlStateManager.enableFog(); if (!on) GlStateManager.disableFog(); break;
            case GL11.GL_COLOR_MATERIAL: GlStateManager.disableColorMaterial(); GlStateManager.enableColorMaterial(); if (!on) GlStateManager.disableColorMaterial(); break;
            default: break;
        }
    }
}
