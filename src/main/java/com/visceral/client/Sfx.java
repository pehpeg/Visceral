package com.visceral.client;

import com.visceral.Visceral;
import com.visceral.VisceralConfig;
import com.visceral.client.BloodTypes.Type;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.PositionedSound;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundCategory;

/**
 * Visceral's own gore sounds (assets/visceral/sounds, all made for this mod): wet squelches, flesh
 * tearing, bones snapping, bodies bursting, meat thudding down, blood gushing and dripping, and
 * their slime, bone, metal, ember and ghost versions. Each sound has several takes the game picks
 * from, and every play is pitched a little differently, so nothing repeats exactly.
 * A resource pack can replace any of them (same file names, see sounds.json).
 */
final class Sfx {
    private Sfx() {}

    private static boolean on() {
        return VisceralConfig.enabled && VisceralConfig.extras.sounds && VisceralConfig.extras.volume > 0.01;
    }

    /** A sound out in the world at (x,y,z). */
    private static void play(String event, double x, double y, double z, float vol, float pitch) {
        if (!on()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null) return;
        float v = vol * (float) VisceralConfig.extras.volume;
        if (v <= 0.01f) return;
        float p = pitch * (0.9f + Blood.RAND.nextFloat() * 0.2f);
        mc.getSoundHandler().playSound(new Gore(new ResourceLocation(Visceral.MODID, event), v, p, (float) x, (float) y, (float) z, true));
    }

    /** One sound in many voices: a few of the same, a little apart, for something huge. */
    private static void layered(String event, double x, double y, double z, float vol, float pitch, int voices) {
        play(event, x, y, z, vol, pitch);
        for (int i = 1; i < voices; i++) play(event, x, y, z, vol * 0.55f, pitch * (0.8f + Blood.RAND.nextFloat() * 0.15f));
    }

    /** A hit drawing blood; k = how hard (about 0.35-2.5). */
    static void hit(Type t, double x, double y, double z, float k) {
        float v = Math.min(1.2f, 0.45f + 0.3f * k);
        float p = 1.1f - 0.12f * Math.min(2f, k);   // harder hits are lower and meatier
        switch (t.style) {
            case LIQUID:
                play(k > 1.4f ? "flesh.heavy" : "flesh.hit", x, y, z, v, p);
                break;
            case SLIME: play("slime.hit", x, y, z, v, p); break;
            case DUST: play("bone.crack", x, y, z, v, p); break;
            case SPARKS: play("metal.hit", x, y, z, v * 0.8f, p); break;
            case EMBERS: play("ember.hit", x, y, z, v * 0.8f, p); break;
            case ECTO: play("ecto.hit", x, y, z, v * 0.7f, p); break;
            default: break;
        }
    }

    /** A creature bursting. */
    static void burst(Type t, double x, double y, double z) {
        switch (t.style) {
            case LIQUID:
                layered("flesh.burst", x, y, z, 1.3f, 1f, 2);
                play("bone.crack", x, y, z, 0.7f, 0.8f);
                break;
            case SLIME: layered("slime.burst", x, y, z, 1.2f, 1f, 2); break;
            case DUST: layered("bone.shatter", x, y, z, 1.2f, 1f, 2); break;
            case SPARKS: layered("metal.burst", x, y, z, 1.1f, 1f, 2); break;
            case EMBERS: layered("ember.burst", x, y, z, 1.1f, 1f, 2); break;
            case ECTO: play("ecto.burst", x, y, z, 1.0f, 1f); break;
            default: break;
        }
    }

    /** A limb torn off: flesh ripping, the joint snapping, then the gush. */
    static void tear(Type t, double x, double y, double z) {
        switch (t.style) {
            case LIQUID:
                play("flesh.tear", x, y, z, 1.2f, 1f);
                play("flesh.gush", x, y, z, 0.8f, 1f);
                break;
            case SLIME: play("slime.burst", x, y, z, 0.9f, 1.2f); break;
            case DUST: play("bone.crack", x, y, z, 1.2f, 0.8f); break;
            case SPARKS: play("metal.hit", x, y, z, 1.1f, 0.8f); break;
            case EMBERS: play("ember.hit", x, y, z, 1.0f, 0.8f); break;
            case ECTO: play("ecto.hit", x, y, z, 0.9f, 0.8f); break;
            default: break;
        }
    }

    /** A gib or chunk landing; k = how hard (0-1). */
    static void land(Type t, double x, double y, double z, float k) {
        float v = 0.25f + 0.55f * k;
        switch (t.style) {
            case LIQUID: play(k > 0.45f ? "flesh.thud" : "flesh.splat", x, y, z, v, 1f); break;
            case SLIME: play("slime.land", x, y, z, v, 1f); break;
            case DUST: play("bone.clatter", x, y, z, v, 1f); break;
            case SPARKS: play("metal.land", x, y, z, v, 1f); break;
            case EMBERS: play("ember.hit", x, y, z, v * 0.5f, 1.3f); break;
            default: break;
        }
    }

    /** A big splat of blood landing on something. */
    static void splat(Type t, double x, double y, double z, float k) {
        if (t.style == BloodTypes.Style.LIQUID || t.style == BloodTypes.Style.SLIME) play("flesh.splat", x, y, z, 0.2f + 0.3f * k, 1.1f);
    }

    /** A drop falling from a ceiling. */
    static void drip(double x, double y, double z) {
        play("flesh.drip", x, y, z, 0.35f, 1f);
    }

    /** A body dropping to the ground; `size` = width x height. */
    static void body(Type t, double x, double y, double z, float size) {
        float v = Math.min(1.2f, 0.45f + size * 0.3f);
        float p = Math.max(0.6f, 1.15f - size * 0.15f);   // bigger bodies land deeper
        switch (t.style) {
            case LIQUID: case SLIME: play("body.fall", x, y, z, v, p); break;
            case DUST: play("bone.clatter", x, y, z, v, p); break;
            case SPARKS: play("metal.land", x, y, z, v, p * 0.8f); break;
            default: play("body.fall", x, y, z, v * 0.7f, p); break;
        }
    }

    /** Flies buzzing over old gore. */
    static void buzz(double x, double y, double z) {
        play("flies.buzz", x, y, z, 0.45f, 1f);
    }

    /** Your foot squelching down in blood. */
    static void step(double x, double y, double z) {
        play("flesh.step", x, y, z, 0.4f, 1f);
    }

    /** Flesh burning (fire, lava). */
    static void burn(double x, double y, double z) {
        play("ember.hit", x, y, z, 0.6f, 0.8f);
    }

    /** Blood hitting your face (your screen). */
    static void screen() {
        if (!on()) return;
        Minecraft mc = Minecraft.getMinecraft();
        Entity e = mc.getRenderViewEntity();
        if (e == null) return;
        float v = 0.8f * (float) VisceralConfig.extras.volume * (float) VisceralConfig.extras.screenBloodStrength;
        if (v <= 0.01f) return;
        mc.getSoundHandler().playSound(new Gore(new ResourceLocation(Visceral.MODID, "screen.splat"), v,
                0.9f + Blood.RAND.nextFloat() * 0.2f, (float) e.posX, (float) (e.posY + e.getEyeHeight()), (float) e.posZ, false));
    }

    /** A gore sound at a spot (or right on you, with no fall-off). */
    private static final class Gore extends PositionedSound {
        Gore(ResourceLocation id, float volume, float pitch, float x, float y, float z, boolean falloff) {
            super(id, SoundCategory.NEUTRAL);
            this.volume = volume;
            this.pitch = pitch;
            this.xPosF = x;
            this.yPosF = y;
            this.zPosF = z;
            this.repeat = false;
            this.repeatDelay = 0;
            this.attenuationType = falloff ? ISound.AttenuationType.LINEAR : ISound.AttenuationType.NONE;
        }
    }
}
