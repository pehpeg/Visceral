package com.visceral.client;

import com.visceral.Visceral;
import com.visceral.VisceralConfig;
import com.visceral.client.BloodTypes.Style;
import com.visceral.client.BloodTypes.Type;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Watches every creature near you, using only what the game already sends your client: it got hurt
 * (its hurt timer restarts), how much (its health dropped), and that it died (its death timer
 * starts). That's the same for every creature from every mod, so they all get gore, on any server.
 * Hits bleed; heavy hits can tear a limb off; deaths burst into gibs or leave a pool; the badly
 * hurt drip as they go.
 */
@Mod.EventBusSubscriber(modid = Visceral.MODID, value = Side.CLIENT)
public final class Tracker {
    private Tracker() {}

    private static final float DT = 0.05f;

    private static final class State {
        float health;
        int hurt, death, seen;
        boolean pending, dead;
        float healthBefore;
        float spurtUntil;
        ModelParts.Group stump;
    }

    private static final class Attack {
        float time;
        boolean hasPoint;
        double x, y, z;
    }

    private static final Map<Integer, State> STATES = new HashMap<>();
    private static final Map<Integer, Attack> ATTACKS = new HashMap<>();
    private static float clock;
    private static int tick;
    private static World lastWorld;

    @SubscribeEvent
    public static void onAttack(AttackEntityEvent event) {
        EntityPlayer p = event.getEntityPlayer();
        Minecraft mc = Minecraft.getMinecraft();
        if (p == null || !p.world.isRemote || p != mc.player) return;
        Attack a = new Attack();
        a.time = clock;
        RayTraceResult ray = mc.objectMouseOver;
        if (ray != null && ray.typeOfHit == RayTraceResult.Type.ENTITY && ray.entityHit == event.getTarget() && ray.hitVec != null) {
            a.hasPoint = true;
            a.x = ray.hitVec.x; a.y = ray.hitVec.y; a.z = ray.hitVec.z;
        }
        ATTACKS.put(event.getTarget().getEntityId(), a);
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world != lastWorld) {
            lastWorld = mc.world;
            STATES.clear();
            ATTACKS.clear();
        }
        if (mc.world == null || mc.player == null || mc.isGamePaused()) return;
        clock += DT;
        tick++;
        ATTACKS.values().removeIf(a -> clock - a.time > 1.2f);
        boolean on = VisceralConfig.enabled;
        double range = VisceralConfig.blood.range;
        List<Entity> list = new ArrayList<>(mc.world.loadedEntityList);
        for (Entity e : list) {
            if (!(e instanceof EntityLivingBase)) continue;
            EntityLivingBase l = (EntityLivingBase) e;
            int id = l.getEntityId();
            State s = STATES.get(id);
            if (s == null) {
                s = new State();
                s.health = s.healthBefore = l.getHealth();
                s.hurt = l.hurtTime;
                s.death = l.deathTime;
                s.dead = l.deathTime > 0;
                s.seen = tick;
                STATES.put(id, s);
                continue;
            }
            s.seen = tick;
            boolean near = on && l.getDistanceSq(mc.player) <= range * range;
            if (near) {
                try {
                    watch(mc, l, s);
                } catch (RuntimeException ex) {
                    Visceral.LOG.debug("gore for {} failed", l, ex);
                }
            }
            s.health = l.getHealth();
            s.hurt = l.hurtTime;
            s.death = l.deathTime;
        }
        if (tick % 40 == 0) {
            Iterator<Map.Entry<Integer, State>> it = STATES.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Integer, State> en = it.next();
                if (tick - en.getValue().seen > 40) {
                    Gibs.forget(en.getKey());
                    Wounds.forget(en.getKey());
                    it.remove();
                }
            }
        }
    }

    private static void watch(Minecraft mc, EntityLivingBase l, State s) {
        Type t = BloodTypes.of(l);
        boolean self = l == mc.player;
        // a hit last tick: its health has caught up by now, so we know how hard it was
        if (s.pending && !s.dead && l.deathTime == 0 && l.getHealth() > 0f) {
            s.pending = false;
            hit(mc, l, s, t, s.healthBefore - l.getHealth(), self);
        }
        if (l.hurtTime > s.hurt && l.hurtTime >= l.maxHurtTime - 1 && !s.dead) {
            s.pending = true;
            s.healthBefore = s.health;
        }
        if (!s.dead && (l.deathTime > 0 || l.getHealth() <= 0f) && (s.death == 0)) {
            s.dead = true;
            float before = s.pending ? s.healthBefore : s.health;
            s.pending = false;
            if (!self) death(mc, l, s, t, Math.max(before, 1f));
            else if (VisceralConfig.extras.screenBlood && wet(t)) ScreenBlood.hurt(t.color, 2.5f);
            return;
        }
        if (!s.dead) bleed(mc, l, s, t, self);
        else spurt(l, s, t);   // a corpse's fresh stump (a cut neck) keeps pumping as it falls
    }

    private static boolean wet(Type t) {
        return t.style == Style.LIQUID || t.style == Style.SLIME;
    }

    /** How hard a hit was, about 0.35 (a poke) to 2.5 (devastating), from the damage and the creature's size. */
    private static float strength(EntityLivingBase l, float dmg) {
        float max = Math.max(1f, l.getMaxHealth());
        if (dmg <= 0f) dmg = max * 0.12f;   // healed in the same tick, or a creature that hides its health
        return MathHelper.clamp(0.35f + dmg / Math.max(4f, max * 0.25f), 0.35f, 2.5f);
    }

    /** Away from whoever hit it: you if it was you, else the way the hit knocked it, else anywhere. */
    private static double[] direction(Minecraft mc, EntityLivingBase l, Attack a) {
        double dx, dz;
        if (a != null) { dx = l.posX - mc.player.posX; dz = l.posZ - mc.player.posZ; }
        else if (l.motionX * l.motionX + l.motionZ * l.motionZ > 0.0025) { dx = l.motionX; dz = l.motionZ; }
        else { double ang = Blood.RAND.nextDouble() * Math.PI * 2; dx = Math.cos(ang); dz = Math.sin(ang); }
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-4) return new double[]{1, 0};
        return new double[]{dx / len, dz / len};
    }

    private static Attack mine(int id, float window) {
        Attack a = ATTACKS.get(id);
        return a != null && clock - a.time <= window ? a : null;
    }

    /** Gibs and lost limbs: not you, not bosses (their deaths are a show of their own), players if allowed. */
    private static boolean goreAllowed(EntityLivingBase l, boolean self) {
        return !self && l.isNonBoss() && (!(l instanceof EntityPlayer) || VisceralConfig.gibs.players);
    }

    // ------------------------------------------------------------------ hits

    private static void hit(Minecraft mc, EntityLivingBase l, State s, Type t, float dmg, boolean self) {
        if (t.none()) return;
        float k = strength(l, dmg);
        if (self) {
            if (wet(t)) ScreenBlood.hurt(t.color, k);
            if (mc.gameSettings.thirdPersonView == 0) return;   // no droplets in your own face
        }
        Attack a = mine(l.getEntityId(), 0.8f);
        double[] d = direction(mc, l, a);
        double px, py, pz;
        if (a != null && a.hasPoint) { px = a.x; py = a.y; pz = a.z; }
        else {
            px = l.posX - d[0] * l.width * 0.3;
            py = l.posY + l.height * (0.5 + Blood.RAND.nextFloat() * 0.25);
            pz = l.posZ - d[1] * l.width * 0.3;
        }
        float amt = Blood.amount();
        int n = Math.round((8 + 22 * k) * amt);
        // out the far side with the blow, and a little back toward the hit
        Blood.spray(t, px, py, pz, d[0], 0.35, d[1], n, 2.5f + 2.5f * k, 0.55f);
        Blood.spray(t, px, py, pz, -d[0], 0.6, -d[1], Math.round(n * 0.25f), 1.5f + k, 0.6f);
        Blood.mist(t, px, py, pz, 0.6f + 0.4f * k);
        if (k > 1.2f) Blood.groundStain(t, l.posX + d[0] * 0.6, l.posY + 0.1, l.posZ + d[1] * 0.6, 0.5f + 0.3f * k, 0.15f, Blood.T_SPLAT0 + Blood.RAND.nextInt(Blood.T_SPLATS));
        Sfx.hit(t, px, py, pz, k);

        // a heavy enough hit can tear a limb off
        if (!VisceralConfig.gibs.limbLoss || !goreAllowed(l, self)) return;
        if (dmg < VisceralConfig.gibs.limbLossDamage * l.getMaxHealth()) return;
        if (Blood.RAND.nextDouble() >= VisceralConfig.gibs.limbLossChance) return;
        ModelParts.Capture cap = ModelParts.capture(l, 1f);
        if (cap == null) return;
        ModelParts.Group pick = null;
        double best = 1e18;
        for (ModelParts.Group g : cap.groups) {
            if (!g.limb || Gibs.isLost(l.getEntityId(), g)) continue;
            // the limb nearest where you hit it (or any, for other damage)
            double dd = a != null && a.hasPoint
                    ? (g.wx - px) * (g.wx - px) + (g.wy - py) * (g.wy - py) + (g.wz - pz) * (g.wz - pz)
                    : Blood.RAND.nextDouble();
            if (dd < best) { best = dd; pick = g; }
        }
        if (pick == null) return;
        Gibs.tearOff(l, cap, pick, t, d[0], d[1], (float) VisceralConfig.gibs.force);
        s.stump = pick;
        s.spurtUntil = clock + 3f;
        Blood.spray(t, pick.wx, pick.wy, pick.wz, d[0], 0.8, d[1], Math.round(30 * amt), 4f, 0.5f);
        Blood.mist(t, pick.wx, pick.wy, pick.wz, 1.2f);
        Sfx.tear(t, pick.wx, pick.wy, pick.wz);
    }

    // ------------------------------------------------------------------ deaths

    /** For the in-game test: forces the next deaths' style (0 burst, 1 decapitation, 2 limb and corpse), -1 = random. */
    static int testStyle = -1;

    private static void death(Minecraft mc, EntityLivingBase l, State s, Type t, float lastHealth) {
        if (t.none()) return;
        Attack a = mine(l.getEntityId(), 1.2f);
        double[] d = direction(mc, l, a);
        float k = strength(l, lastHealth);
        float amt = Blood.amount();
        // how big it is, next to a zombie
        float size = MathHelper.clamp((l.width * l.height) / (0.6f * 1.95f), 0.3f, 6f);
        double cx = l.posX, cy = l.posY + l.height * 0.5, cz = l.posZ;
        boolean gib = VisceralConfig.gibs.deathGibs && goreAllowed(l, false)
                && Blood.RAND.nextDouble() < VisceralConfig.gibs.gibChance
                && (!VisceralConfig.gibs.onlyYourKills || a != null);
        if (gib) {
            float force = (float) VisceralConfig.gibs.force * (0.8f + 0.25f * Math.min(2f, k));
            ModelParts.Capture cap = ModelParts.capture(l, 1f);
            // not every death is a burst: some lose their head, some a limb, and fall
            int style = testStyle >= 0 ? testStyle : !VisceralConfig.gibs.deathStyles ? 0
                    : Blood.RAND.nextFloat() < 0.55f ? 0 : Blood.RAND.nextFloat() < 0.6f ? 1 : 2;
            if (style > 0 && cap != null && partDeath(mc, l, s, t, cap, style, d, force, size)) return;
            boolean parts = Gibs.burst(l, cap, t, d[0], d[1], force);
            if (VisceralConfig.gibs.chunks || !parts) {
                int n = Math.round(VisceralConfig.gibs.chunkCount * (float) Math.sqrt(size) * (parts ? 1f : 1.6f));
                if (!parts) n = Math.max(n, 8);
                Gibs.chunks(t, l.posX, l.posY + l.height * 0.2, l.posZ, l.width, l.height * 0.7f, n, d[0], d[1], force);
            }
            int drops = Math.round((45 + 30 * size) * amt);
            Blood.spray(t, cx, cy, cz, d[0] * 0.5, 1, d[1] * 0.5, drops, 5f * (float) Math.sqrt(force), 1.0f);
            Blood.spray(t, cx, cy, cz, d[0], 0.3, d[1], drops / 2, 6f, 0.4f);
            for (int i = 0; i < 3; i++) Blood.mist(t, cx, cy + (i - 1) * l.height * 0.25, cz, 1.1f * (float) Math.sqrt(size));
            Blood.groundStain(t, cx, l.posY + 0.1, cz, Math.min(4f, Math.max(l.width * 2.4f, 1.2f)), 0.12f, Blood.T_SPLAT0 + Blood.RAND.nextInt(Blood.T_SPLATS));
            Sfx.burst(t, cx, cy, cz);
            if (a != null && wet(t) && l.getDistanceSq(mc.player) < 3.2 * 3.2 && mc.gameSettings.thirdPersonView == 0) ScreenBlood.spray(t.color, k);
            // the body is gone: take it out of the world now, so there's no corpse for another mod to
            // draw, no shadow left behind, and no puff of smoke when it would have vanished
            if (!(l instanceof EntityPlayer)) l.setDead();
        } else {
            // dies like vanilla, with a last spurt and a pool spreading under it
            Blood.spray(t, cx, cy, cz, d[0], 0.5, d[1], Math.round((14 + 10 * size) * amt), 3f, 0.6f);
            Blood.mist(t, cx, cy, cz, 0.8f);
            Sfx.hit(t, cx, cy, cz, k);
            if (VisceralConfig.extras.bloodPools) Blood.groundStain(t, cx, l.posY + 0.1, cz, Math.min(3.5f, Math.max(0.8f, l.width * 1.9f)), 3f, Blood.T_POOL);
        }
    }

    /**
     * A death that isn't a burst: the head comes off (style 1) or a limb (style 2), the rest falls
     * like a normal death, soaked, the stump pumping, a pool spreading. False if it has no such part.
     */
    private static boolean partDeath(Minecraft mc, EntityLivingBase l, State s, Type t, ModelParts.Capture cap, int style,
                                     double[] d, float force, float size) {
        ModelParts.Group pick = null;
        for (ModelParts.Group g : cap.groups) {
            if (Gibs.isLost(l.getEntityId(), g)) continue;
            if (style == 1 ? g.head : g.limb) {
                if (pick == null || Blood.RAND.nextBoolean()) pick = g;
            }
        }
        if (pick == null) return false;
        Gibs.tearOff(l, cap, pick, t, d[0], d[1], force * (style == 1 ? 1.25f : 1f));
        s.stump = pick;
        s.spurtUntil = clock + (style == 1 ? 2.6f : 2f);
        float amt = Blood.amount();
        Blood.spray(t, pick.wx, pick.wy, pick.wz, d[0], 1.2, d[1], Math.round((40 + 15 * size) * amt), 4.5f, 0.55f);
        Blood.mist(t, pick.wx, pick.wy, pick.wz, 1.4f);
        Sfx.tear(t, pick.wx, pick.wy, pick.wz);
        if (VisceralConfig.extras.bloodPools) {
            Blood.groundStain(t, l.posX, l.posY + 0.1, l.posZ, Math.min(3.5f, Math.max(1f, l.width * 2.1f)), 3.5f, Blood.T_POOL);
        }
        return true;
    }

    /** A fresh stump spurts with each heartbeat (on the living and on a falling corpse). */
    private static void spurt(EntityLivingBase l, State s, Type t) {
        if (s.stump == null || clock >= s.spurtUntil || t.none() || !(t.stains() || t.style == Style.ECTO)) return;
        float beat = (clock * 2.4f) % 1f;
        if (beat >= 0.25f) return;
        double[] p = Gibs.joint(l, s.stump.sx, s.stump.sy, s.stump.sz, 1f);
        double ox = p[0] - l.posX, oz = p[2] - l.posZ;
        double ol = Math.sqrt(ox * ox + oz * oz);
        if (ol < 1e-3) { ox = 0; oz = 0; ol = 1; }
        float fade = (s.spurtUntil - clock) / 3f;
        Blood.spray(t, p[0], p[1], p[2], ox / ol, 0.6, oz / ol, Math.max(1, Math.round(4 * Blood.amount() * fade)), 3f * fade + 0.8f, 0.25f);
    }

    // ------------------------------------------------------------------ bleeding

    private static void bleed(Minecraft mc, EntityLivingBase l, State s, Type t, boolean self) {
        if (t.none() || (self && mc.gameSettings.thirdPersonView == 0)) return;
        if (!(t.stains() || t.style == Style.ECTO)) return;
        spurt(l, s, t);
        if (!VisceralConfig.extras.bleeding) return;
        float max = Math.max(1f, l.getMaxHealth());
        float frac = l.getHealth() / max;
        float below = (float) VisceralConfig.extras.bleedBelow;
        boolean maimed = Gibs.lost(l.getEntityId()) != null;
        if (frac >= below && !maimed) return;
        float p = below > 0 ? 0.04f + 0.3f * (1f - Math.min(1f, frac / below)) : 0f;
        if (maimed) p = Math.max(p, 0.2f);
        if (Blood.RAND.nextFloat() >= p * Math.min(2f, Blood.amount())) return;
        if (maimed && s.stump != null && Blood.RAND.nextBoolean()) {
            double[] j = Gibs.joint(l, s.stump.sx, s.stump.sy, s.stump.sz, 1f);
            Blood.drip(t, j[0], j[1], j[2]);
        } else {
            Blood.drip(t, l.posX + (Blood.RAND.nextDouble() - 0.5) * l.width,
                    l.posY + l.height * (0.3 + Blood.RAND.nextDouble() * 0.45),
                    l.posZ + (Blood.RAND.nextDouble() - 0.5) * l.width);
        }
    }
}
