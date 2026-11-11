package com.visceral.client;

import com.visceral.Visceral;
import com.visceral.VisceralConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Flies: gore that's been lying around a while draws a little swarm, darting and circling over it
 * and buzzing when you're close, until the gore sinks away. Tiny dark specks, cheap to draw.
 */
@Mod.EventBusSubscriber(modid = Visceral.MODID, value = Side.CLIENT)
public final class Flies {
    private Flies() {}

    private static final class Swarm {
        double x, y, z;
        float born, life;
        int n;
        final float[] seed;
        Swarm(int n) { this.n = n; seed = new float[n * 6]; }
    }

    private static final List<Swarm> SWARMS = new ArrayList<>();
    private static World lastWorld;
    private static float clock, nextBuzz;

    static int count() {
        int n = 0;
        for (Swarm s : SWARMS) if (clock >= s.born) n += s.n;
        return n;
    }

    /** Flies gather over (x,y,z) for `seconds`. */
    static void swarm(double x, double y, double z, float seconds) {
        swarm(x, y, z, seconds, 0f);
    }

    /** As swarm(), the flies only turning up after `delay` seconds. */
    static void swarm(double x, double y, double z, float seconds, float delay) {
        if (!VisceralConfig.enabled || !VisceralConfig.extras.flies || SWARMS.size() >= 24) return;
        Swarm s = new Swarm(4 + Blood.RAND.nextInt(6));
        s.x = x; s.y = y; s.z = z;
        s.born = clock + delay;
        s.life = seconds;
        for (int i = 0; i < s.seed.length; i++) s.seed[i] = Blood.RAND.nextFloat();
        SWARMS.add(s);
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent event) {
        Guard.run("Flies.onTick", () -> onTick0(event));
    }

    private static void onTick0(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world != lastWorld) { lastWorld = mc.world; SWARMS.clear(); }
        if (mc.world == null || mc.player == null || mc.isGamePaused()) return;
        clock += 0.05f;
        Iterator<Swarm> it = SWARMS.iterator();
        Swarm near = null;
        double best = 12 * 12;
        while (it.hasNext()) {
            Swarm s = it.next();
            if (clock - s.born > s.life) { it.remove(); continue; }
            if (clock < s.born) continue;
            double d = mc.player.getDistanceSq(s.x, s.y, s.z);
            if (d < best) { best = d; near = s; }
        }
        // the nearest swarm buzzes now and then
        if (near != null && clock >= nextBuzz) {
            nextBuzz = clock + 2.2f + Blood.RAND.nextFloat() * 2.5f;
            Sfx.buzz(near.x, near.y + 0.3, near.z);
        }
    }

    @SubscribeEvent
    public static void onRenderWorld(RenderWorldLastEvent event) {
        Guard.run("Flies.onRenderWorld", () -> onRenderWorld0(event));
    }

    private static void onRenderWorld0(RenderWorldLastEvent event) {
        if (SWARMS.isEmpty()) return;
        Minecraft mc = Minecraft.getMinecraft();
        Entity cam = mc.getRenderViewEntity();
        if (cam == null) return;
        float pt = event.getPartialTicks();
        float now = clock + pt * 0.05f;
        double cx = cam.lastTickPosX + (cam.posX - cam.lastTickPosX) * pt;
        double cy = cam.lastTickPosY + (cam.posY - cam.lastTickPosY) * pt;
        double cz = cam.lastTickPosZ + (cam.posZ - cam.lastTickPosZ) * pt;
        float yaw = (float) Math.toRadians(mc.getRenderManager().playerViewY);
        float rx = (float) Math.cos(yaw), rz = (float) Math.sin(yaw);
        GlGuard.push();
        GlGuard.world();
        try {
            GlStateManager.disableTexture2D();
            GlStateManager.enableBlend();
            GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            GlStateManager.disableCull();
            GlStateManager.enableDepth();
            Tessellator tess = Tessellator.getInstance();
            BufferBuilder b = tess.getBuffer();
            b.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
            for (Swarm s : SWARMS) {
                if (s.x - cx > 48 || cx - s.x > 48 || s.z - cz > 48 || cz - s.z > 48) continue;
                float age = now - s.born;
                if (age < 0) continue;
                float a = Math.min(1f, age / 2f) * Math.min(1f, (s.life - age) / 2f);   // they arrive and leave
                if (a <= 0.02f) continue;
                for (int i = 0; i < s.n; i++) {
                    float[] q = s.seed;
                    int o = i * 6;
                    // each fly loops on its own lissajous path, with a jittery dart thrown in
                    float t = now * (1.6f + q[o] * 1.8f) + q[o + 1] * 20f;
                    float r = 0.18f + q[o + 2] * 0.4f;
                    double fx = s.x + Math.sin(t) * r + Math.sin(t * 3.7f + q[o + 3] * 9f) * 0.07f;
                    double fz = s.z + Math.cos(t * (0.8f + q[o + 4] * 0.5f)) * r + Math.cos(t * 4.3f) * 0.07f;
                    double fy = s.y + 0.15 + q[o + 5] * 0.45 + Math.sin(t * 2.3f + q[o]) * 0.12;
                    float x = (float) (fx - cx), y = (float) (fy - cy), z = (float) (fz - cz);
                    float h = 0.016f;
                    b.pos(x - rx * h, y - h, z - rz * h).color(0.05f, 0.05f, 0.04f, 0.9f * a).endVertex();
                    b.pos(x + rx * h, y - h, z + rz * h).color(0.05f, 0.05f, 0.04f, 0.9f * a).endVertex();
                    b.pos(x + rx * h, y + h, z + rz * h).color(0.05f, 0.05f, 0.04f, 0.9f * a).endVertex();
                    b.pos(x - rx * h, y + h, z - rz * h).color(0.05f, 0.05f, 0.04f, 0.9f * a).endVertex();
                }
            }
            tess.draw();
            GlStateManager.enableTexture2D();
            GlStateManager.enableCull();
        } finally {
            GlGuard.pop();
        }
    }
}
