package com.visceral.client;

import com.visceral.Visceral;
import com.visceral.VisceralConfig;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;

/**
 * Blood on your weapon and hand: each hit that draws blood leaves some on them (in that blood's
 * colour), building up over four levels. It wears off slowly, and comes off at once in water or rain.
 * Drawn the same way as wounds (projected over your hand and held item as they're drawn), so it
 * works with any item, modded weapons included.
 */
@Mod.EventBusSubscriber(modid = Visceral.MODID, value = Side.CLIENT)
public final class HeldBlood {
    private HeldBlood() {}

    private static int level, color;
    private static float sinceHit;
    private static boolean drawing;

    static boolean on() {
        return VisceralConfig.enabled && VisceralConfig.extras.bloodyWeapon && !VisceralConfig.extras.mild;
    }

    /** You hit something that bled this colour. */
    static void hit(int bloodColor) {
        if (!on()) return;
        color = bloodColor;
        sinceHit = 0f;
        if (Blood.RAND.nextInt(3) != 0) level = Math.min(4, level + 1);
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || level == 0) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null || mc.isGamePaused()) return;
        sinceHit += 0.05f;
        // washed off in water or rain; otherwise it wears off slowly
        if (mc.player.isWet()) level = 0;
        else if (sinceHit > 25f) { level--; sinceHit = 0f; }
    }

    /** Just before your hand and held item are drawn: the blood goes on. */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onRenderHand(RenderHandEvent event) {
        if (drawing) { Wounds.end(); drawing = false; }
        if (level <= 0 || !on()) return;
        Guard.run("HeldBlood.begin", () -> drawing = Wounds.begin(color, level, 31));
    }

    /** The hand is done (the HUD comes next): the blood goes off again. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onOverlay(RenderGameOverlayEvent.Pre event) {
        if (drawing) { Wounds.end(); drawing = false; }
    }

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.END && drawing) { Wounds.end(); drawing = false; }
    }

    static void clear() {
        level = 0;
    }
}
