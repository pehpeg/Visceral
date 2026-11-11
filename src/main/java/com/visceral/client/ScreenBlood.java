package com.visceral.client;

import com.visceral.Visceral;
import com.visceral.VisceralConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Blood on your screen: splats around the edges when you get hurt, and a spray across the view when
 * you kill something right in front of you. They run down a little and fade. Drawn under the HUD,
 * so the hotbar and health stay readable.
 */
@Mod.EventBusSubscriber(modid = Visceral.MODID, value = Side.CLIENT)
public final class ScreenBlood {
    private ScreenBlood() {}

    private static final class Splat {
        float x, y, size, born, life, slide, run;
        int tile, flip, color;
        float alpha;
    }

    private static final List<Splat> SPLATS = new ArrayList<>();
    private static float clock;

    private static boolean on() {
        return VisceralConfig.enabled && VisceralConfig.extras.screenBlood && VisceralConfig.extras.screenBloodStrength > 0
                && !VisceralConfig.extras.mild;
    }

    /** You got hurt: k about 0.3 (a scratch) to 2.5 (a huge hit). */
    static void hurt(int color, float k) {
        if (!on()) return;
        int n = Math.round((1 + k * 2.5f) * (float) VisceralConfig.extras.screenBloodStrength);
        for (int i = 0; i < n; i++) {
            // round the edges, where it doesn't block what you're looking at
            float a = Blood.RAND.nextFloat() * 6.28f;
            float d = 0.36f + Blood.RAND.nextFloat() * 0.2f;
            add(0.5f + (float) Math.cos(a) * d, 0.5f + (float) Math.sin(a) * d * 0.9f, 0.18f + Blood.RAND.nextFloat() * 0.2f * Math.min(1.5f, k), color, 0.75f);
        }
        if (k > 1.2f) Sfx.screen();
    }

    /** Something burst right in front of you. */
    static void spray(int color, float k) {
        if (!on()) return;
        int n = Math.round((2 + k * 3) * (float) VisceralConfig.extras.screenBloodStrength);
        for (int i = 0; i < n; i++) {
            add(0.15f + Blood.RAND.nextFloat() * 0.7f, 0.1f + Blood.RAND.nextFloat() * 0.75f, 0.08f + Blood.RAND.nextFloat() * 0.2f, color, 0.85f);
        }
        Sfx.screen();
    }

    private static void add(float x, float y, float size, int color, float alpha) {
        Splat s = new Splat();
        s.x = x; s.y = y; s.size = size;
        s.born = clock;
        s.life = 2.5f + Blood.RAND.nextFloat() * 2f;
        s.slide = 0.02f + Blood.RAND.nextFloat() * 0.05f;
        int r = Blood.RAND.nextInt(10);
        s.tile = r < 2 ? Blood.T_SPATTER : r < 4 ? Blood.T_SMEAR : Blood.T_SPLAT0 + Blood.RAND.nextInt(Blood.T_SPLATS);
        if (s.tile == Blood.T_SMEAR) { s.flip &= 1; s.run = 0.06f + Blood.RAND.nextFloat() * 0.1f; s.size *= 1.3f; }
        s.flip = Blood.RAND.nextInt(4);
        s.color = Blood.vary(color, 0.1f);
        s.alpha = alpha;
        SPLATS.add(s);
        while (SPLATS.size() > 40) SPLATS.remove(0);
    }

    private static void quad(BufferBuilder b, Splat s, float w, float h, float m, float now, boolean gloss) {
        float t = now - s.born;
        float k = t / s.life;
        float a = s.alpha * (t < 0.08f ? t / 0.08f : 1f) * (k > 0.55f ? Math.max(0f, (1f - k) / 0.45f) : 1f);
        if (gloss) a *= 0.45f * Math.max(0f, 1f - k * 1.6f);
        if (a <= 0.01f) return;
        float sz = s.size * m * (1f + 0.08f * Math.min(1f, t * 6f));
        float cx = s.x * w, cy = (s.y + s.slide * Math.min(1f, t / 2.5f)) * h;   // runs down slowly
        float u0 = (s.tile % 4) * 0.25f + 0.002f, v0 = (s.tile / 4) * 0.25f + 0.002f, u1 = u0 + 0.246f, v1 = v0 + 0.246f;
        if ((s.flip & 1) != 0) { float q = u0; u0 = u1; u1 = q; }
        if ((s.flip & 2) != 0) { float q = v0; v0 = v1; v1 = q; }
        int c = s.color;
        float r = gloss ? 1f : ((c >> 16) & 255) / 255f, g = gloss ? 1f : ((c >> 8) & 255) / 255f, bl = gloss ? 1f : (c & 255) / 255f;
        float hs = sz * 0.5f;
        float top = cy - hs, bottom = cy + hs;
        if (s.run > 0f) {
            // a trickle running down the screen: the picture's top is the run's head (upside down on screen)
            float grow = Math.min(1f, t / 2.2f);
            bottom = top + sz * (0.2f + 0.8f * grow);
            float fv = 0.2f + 0.8f * grow;
            // screen y grows downward: show the blob first, then more of the run below it
            float vb = v1 - (v1 - v0) * fv;
            b.pos(cx - hs, bottom, 0).tex(u0, vb).color(r, g, bl, a).endVertex();
            b.pos(cx + hs, bottom, 0).tex(u1, vb).color(r, g, bl, a).endVertex();
            b.pos(cx + hs, top, 0).tex(u1, v1).color(r, g, bl, a).endVertex();
            b.pos(cx - hs, top, 0).tex(u0, v1).color(r, g, bl, a).endVertex();
            return;
        }
        b.pos(cx - hs, bottom, 0).tex(u0, v1).color(r, g, bl, a).endVertex();
        b.pos(cx + hs, bottom, 0).tex(u1, v1).color(r, g, bl, a).endVertex();
        b.pos(cx + hs, top, 0).tex(u1, v0).color(r, g, bl, a).endVertex();
        b.pos(cx - hs, top, 0).tex(u0, v0).color(r, g, bl, a).endVertex();
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent event) {
        Guard.run("ScreenBlood.onTick", () -> onTick0(event));
    }

    private static void onTick0(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.isGamePaused()) return;
        clock += 0.05f;
        SPLATS.removeIf(s -> clock - s.born > s.life);
        if (mc.player != null && mc.player.isDead) SPLATS.clear();
    }

    @SubscribeEvent
    public static void onOverlay(RenderGameOverlayEvent.Pre event) {
        Guard.run("ScreenBlood.onOverlay", () -> onOverlay0(event));
    }

    private static void onOverlay0(RenderGameOverlayEvent.Pre event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL || SPLATS.isEmpty()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.gameSettings.thirdPersonView != 0) return;
        GlGuard.push();
        GlGuard.hud();
        try {
            draw(mc, event.getResolution(), event.getPartialTicks());
        } finally {
            GlGuard.pop();
        }
    }

    private static void draw(Minecraft mc, ScaledResolution res, float pt) {
        float w = res.getScaledWidth(), h = res.getScaledHeight(), m = Math.max(w, h);
        float now = clock + pt * 0.05f;
        Blood.bind(Blood.ATLAS);
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        GlStateManager.disableAlpha();
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder b = tess.getBuffer();
        b.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
        for (Splat s : SPLATS) quad(b, s, w, h, m, now, false);
        tess.draw();
        // the wet shine, fading as it dries
        Blood.bind(Blood.GLOSS);
        GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        b.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
        for (Splat s : SPLATS) quad(b, s, w, h, m, now, true);
        tess.draw();
        GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        GlStateManager.enableAlpha();
    }
}
