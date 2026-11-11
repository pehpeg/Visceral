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
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.init.MobEffects;
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
        double prevY = Double.NaN;
        float fallV;                       // how fast it was falling lately (blocks/tick, negative)
        double walked, lastStep;           // footprints: distance walked, and at the last print
        int bloodySteps, feetColor;        // prints left before the feet are clean
        Type feetType;
        boolean leftFoot;
    }

    /** What hurt a creature, as best the client can tell. */
    enum Cause { MELEE, PROJECTILE, EXPLOSION, FIRE, FALL, QUIET, OTHER }

    private static final class Hit {
        Cause cause = Cause.OTHER;
        double px, py, pz;                 // where it landed
        double dx, dy, dz;                 // which way it went (unit)
        double ex, ey, ez;                 // where it came out (projectiles)
        Attack melee;
    }

    /** Projectiles seen lately: {x, y, z, motion x, y, z, tick}. */
    private static final Map<Integer, double[]> PROJ = new HashMap<>();
    /** Explosions heard lately: {x, y, z, clock}. */
    private static final List<double[]> BOOMS = new ArrayList<>();

    /** An explosion's sound is the client's only sign of one: note where and when. */
    @SubscribeEvent
    public static void onSound(net.minecraftforge.client.event.sound.PlaySoundEvent event) {
        Guard.run("Tracker.onSound", () -> onSound0(event));
    }

    private static void onSound0(net.minecraftforge.client.event.sound.PlaySoundEvent event) {
        net.minecraft.client.audio.ISound snd = event.getSound();
        if (snd == null || snd.getSoundLocation() == null) return;
        if (!"entity.generic.explode".equals(snd.getSoundLocation().getPath())) return;
        BOOMS.add(new double[]{snd.getXPosF(), snd.getYPosF(), snd.getZPosF(), clock});
        if (BOOMS.size() > 16) BOOMS.remove(0);
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
        Guard.run("Tracker.onAttack", () -> onAttack0(event));
    }

    private static void onAttack0(AttackEntityEvent event) {
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
        Guard.run("Tracker.onTick", () -> onTick0(event));
    }

    private static void onTick0(TickEvent.ClientTickEvent event) {
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
            if (e instanceof net.minecraft.entity.IProjectile || e instanceof net.minecraft.entity.projectile.EntityFireball) {
                PROJ.put(e.getEntityId(), new double[]{e.posX, e.posY, e.posZ, e.posX - e.lastTickPosX, e.posY - e.lastTickPosY,
                        e.posZ - e.lastTickPosZ, tick});
            }
        }
        PROJ.values().removeIf(p -> tick - p[6] > 4);
        BOOMS.removeIf(b -> clock - b[3] > 0.5f);
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
            if (!Double.isNaN(s.prevY)) {
                float vy = (float) (l.posY - s.prevY);
                s.fallV = vy < s.fallV ? vy : Math.min(0f, s.fallV + 0.12f);
            }
            s.prevY = l.posY;
            boolean near = on && l.getDistanceSq(mc.player) <= range * range;
            if (on && !near && !s.dead && l.deathTime > 0) {
                s.dead = true;
                lastDeath = name(l) + ": too far away (range " + (int) range + ")";
            }
            if (near) {
                try {
                    if (!s.dead) feet(mc, l, s);
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
        else {
            spurt(l, s, t);
            // a corpse hitting the ground
            if (l.deathTime == 12 && !t.none() && !Gibs.burst(l.getEntityId())) Sfx.body(t, l.posX, l.posY, l.posZ, l.width * l.height);
        }   // a corpse's fresh stump (a cut neck) keeps pumping as it falls
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
        return !self && !VisceralConfig.extras.mild && l.isNonBoss() && (!(l instanceof EntityPlayer) || VisceralConfig.gibs.players);
    }

    // ------------------------------------------------------------------ hits

    /**
     * What hurt it, as best we can tell from here: your swing, a projectile that was flying at it,
     * an explosion just heard nearby, a hard landing, fire, or something quiet (drowning, poison,
     * wither). Anything else is "other", thrown the way the hit knocked it.
     */
    private static Hit cause(Minecraft mc, EntityLivingBase l, State s, float window) {
        Hit h = new Hit();
        Attack a = mine(l.getEntityId(), window);
        AxisAlignedBB bb = l.getEntityBoundingBox().grow(0.35);
        if (a != null) {
            h.cause = Cause.MELEE;
            h.melee = a;
            double[] d = direction(mc, l, a);
            h.dx = d[0]; h.dz = d[1];
            if (a.hasPoint) { h.px = a.x; h.py = a.y; h.pz = a.z; } else side(l, h);
            return h;
        }
        // a projectile whose path ran into it
        double best = 1e9;
        for (double[] p : PROJ.values()) {
            double m = Math.sqrt(p[3] * p[3] + p[4] * p[4] + p[5] * p[5]);
            if (m < 0.15 || tick - p[6] > 3) continue;
            Vec3d from = new Vec3d(p[0] - p[3], p[1] - p[4], p[2] - p[5]), to = new Vec3d(p[0] + p[3] * 3, p[1] + p[4] * 3, p[2] + p[5] * 3);
            RayTraceResult r = bb.calculateIntercept(from, to);
            Vec3d at = r != null ? r.hitVec : bb.contains(from) ? from : null;
            if (at == null) continue;
            double dd = at.squareDistanceTo(from);
            if (dd < best) {
                best = dd;
                h.cause = Cause.PROJECTILE;
                h.px = at.x; h.py = at.y; h.pz = at.z;
                h.dx = p[3] / m; h.dy = p[4] / m; h.dz = p[5] / m;
            }
        }
        if (h.cause == Cause.PROJECTILE) {
            // and where it came out: back along its path from beyond the far side
            Vec3d in = new Vec3d(h.px, h.py, h.pz);
            double far = l.width + l.height + 1;
            Vec3d beyond = in.add(h.dx * far, h.dy * far, h.dz * far);
            RayTraceResult out = bb.calculateIntercept(beyond, in);
            Vec3d o = out != null ? out.hitVec : in;
            h.ex = o.x; h.ey = o.y; h.ez = o.z;
            return h;
        }
        for (double[] b : BOOMS) {
            double dx = l.posX - b[0], dy = l.posY + l.height * 0.5 - b[1], dz = l.posZ - b[2];
            double dd = dx * dx + dy * dy + dz * dz;
            if (dd > 10 * 10) continue;
            double m = Math.max(0.3, Math.sqrt(dd));
            h.cause = Cause.EXPLOSION;
            h.dx = dx / m; h.dy = Math.max(0.3, dy / m); h.dz = dz / m;
            side(l, h);
            return h;
        }
        if (s.fallV < -0.5f && l.posY - s.prevY > -0.15) {
            h.cause = Cause.FALL;
            h.px = l.posX; h.py = l.posY + 0.1; h.pz = l.posZ;
            return h;
        }
        if (l.isBurning()) {
            h.cause = Cause.FIRE;
            side(l, h);
            return h;
        }
        if ((l.isInWater() && l.getAir() <= 0) || l.isPotionActive(MobEffects.POISON) || l.isPotionActive(MobEffects.WITHER)) {
            h.cause = Cause.QUIET;
            return h;
        }
        double[] d = direction(mc, l, null);
        h.dx = d[0]; h.dz = d[1];
        side(l, h);
        return h;
    }

    /**
     * Feet in wet blood pick it up; walking on, they leave prints, fainter with each step until
     * they're clean. Your own steps in it squelch.
     */
    private static void feet(Minecraft mc, EntityLivingBase l, State s) {
        if (!VisceralConfig.extras.footprints || !l.onGround || l instanceof net.minecraft.entity.monster.EntitySlime
                || l instanceof net.minecraft.entity.EntityFlying) return;
        double mx = l.posX - l.lastTickPosX, mz = l.posZ - l.lastTickPosZ;
        double step = Math.sqrt(mx * mx + mz * mz);
        if (step > 1.5) return;   // a teleport, not a step
        Blood.Stain wet = Blood.wetFloor(mc.world, l.posX, l.posY, l.posZ);
        if (wet != null) {
            s.bloodySteps = 8 + Blood.RAND.nextInt(5);
            s.feetColor = wet.color;
            s.feetType = wet.type;
        }
        s.walked += step;
        boolean biped = l.height > l.width * 1.6f;
        float stride = biped ? 0.62f * Math.max(0.6f, l.height / 1.8f) : 0.45f * Math.max(0.5f, l.width / 0.9f);
        if (s.walked - s.lastStep < stride) return;
        s.lastStep = s.walked;
        s.leftFoot = !s.leftFoot;
        if (l == mc.player && wet != null) Sfx.step(l.posX, l.posY, l.posZ);
        if (s.bloodySteps <= 0 || s.feetType == null) return;
        float yaw = l instanceof EntityPlayer ? l.rotationYaw : l.renderYawOffset;
        float size = biped ? 0.62f * Math.max(0.7f, l.height / 1.8f) : 0.42f * Math.max(0.6f, l.width / 0.9f);
        Blood.print(s.feetType, s.feetColor, l.posX, l.posY, l.posZ, yaw, s.leftFoot, !biped, size, Math.min(1f, s.bloodySteps / 9f));
        s.bloodySteps--;
    }

    /** A point on the side the hit came from. */
    private static void side(EntityLivingBase l, Hit h) {
        h.px = l.posX - h.dx * l.width * 0.3;
        h.py = l.posY + l.height * (0.5 + Blood.RAND.nextFloat() * 0.25);
        h.pz = l.posZ - h.dz * l.width * 0.3;
    }

    // ------------------------------------------------------------------ hits

    private static void hit(Minecraft mc, EntityLivingBase l, State s, Type t, float dmg, boolean self) {
        if (t.none()) return;
        float k = strength(l, dmg);
        Hit h = cause(mc, l, s, 0.8f);
        if (self) {
            if (wet(t) && h.cause != Cause.FIRE && h.cause != Cause.QUIET) ScreenBlood.hurt(t.color, k);
            if (mc.gameSettings.thirdPersonView == 0) return;   // no droplets in your own face
        }
        Attack a = h.melee;
        double[] d = {h.dx, h.dz};
        double px = h.px, py = h.py, pz = h.pz;
        float amt = Blood.amount();
        int n = Math.round((8 + 22 * k) * amt);
        switch (h.cause) {
            case QUIET:
                return;   // drowning, poison, wither: no wound to bleed from
            case FIRE:
                // burning: it cooks rather than bleeds
                if (Blood.RAND.nextInt(3) == 0) Blood.mist(BloodTypes.ember(), px, py, pz, 0.5f);
                if (Blood.RAND.nextInt(4) == 0) Sfx.burn(px, py, pz);
                return;
            case PROJECTILE:
                // a small spit back out of the hole it went in, and the real spray out the far side
                Blood.spray(t, px, py, pz, -h.dx, -h.dy + 0.3, -h.dz, Math.max(2, n / 5), 1.5f, 0.5f);
                Blood.spray(t, h.ex, h.ey, h.ez, h.dx, h.dy + 0.15, h.dz, n, 3.5f + 3f * k, 0.3f);
                Blood.mist(t, h.ex, h.ey, h.ez, 0.7f + 0.4f * k);
                break;
            case EXPLOSION:
                Blood.spray(t, px, py, pz, h.dx, h.dy + 0.4, h.dz, n * 2, 4f + 3f * k, 0.8f);
                Blood.mist(t, px, py, pz, 1f + 0.5f * k);
                break;
            case FALL:
                // a hard landing: it splats out around the feet
                Blood.groundStain(t, l.posX, l.posY + 0.1, l.posZ, Math.min(2.5f, 0.6f + 0.4f * k + l.width), 0.1f,
                        Blood.T_SPLAT0 + Blood.RAND.nextInt(Blood.T_SPLATS));
                for (int i = 0; i < 4; i++) {
                    double ang = Blood.RAND.nextDouble() * Math.PI * 2;
                    Blood.spray(t, l.posX, l.posY + 0.15, l.posZ, Math.cos(ang), 0.25, Math.sin(ang), Math.max(2, n / 4), 2.5f + k, 0.4f);
                }
                break;
            default:
                // out the far side with the blow, and a little back toward the hit
                Blood.spray(t, px, py, pz, d[0], 0.35, d[1], n, 2.5f + 2.5f * k, 0.55f);
                Blood.spray(t, px, py, pz, -d[0], 0.6, -d[1], Math.round(n * 0.25f), 1.5f + k, 0.6f);
                Blood.mist(t, px, py, pz, 0.6f + 0.4f * k);
                break;
        }
        if (a != null && wet(t) && !self) HeldBlood.hit(t.color);
        if (k > 1.2f && h.cause != Cause.FALL) Blood.groundStain(t, l.posX + d[0] * 0.6, l.posY + 0.1, l.posZ + d[1] * 0.6, 0.5f + 0.3f * k, 0.15f, Blood.T_SPLAT0 + Blood.RAND.nextInt(Blood.T_SPLATS));
        Sfx.hit(t, px, py, pz, k);

        // a heavy enough hit can tear a limb off
        if (!VisceralConfig.gibs.limbLoss || !goreAllowed(l, self) || !BloodTypes.options(l).limbs) return;
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

    /** What happened at the last death near you, and why (shown on the F3 screen). */
    static String lastDeath = "none yet";

    private static String name(EntityLivingBase l) {
        net.minecraft.util.ResourceLocation rl = net.minecraft.entity.EntityList.getKey(l);
        return (l instanceof EntityPlayer ? "player" : rl == null ? l.getClass().getSimpleName() : rl.toString())
                + " (blood " + BloodTypes.of(l).name + ")";
    }

    /** Visceral's lines on the F3 screen: how much gore there is, and what the last death did and why. */
    @SubscribeEvent
    public static void onDebugText(net.minecraftforge.client.event.RenderGameOverlayEvent.Text event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!mc.gameSettings.showDebugInfo) return;
        event.getLeft().add("");
        event.getLeft().add("Visceral " + Visceral.VERSION + (VisceralConfig.enabled ? "" : " (off)") + ": droplets " + Blood.dropCount()
                + ", stains " + Blood.stainCount() + ", gibs " + Gibs.count() + ", flies " + Flies.count()
                + String.format(", drawing %.2f ms/frame", Guard.frameMs())
                + (Guard.off() > 0 ? ", " + Guard.off() + " part(s) switched off after errors" : ""));
        event.getLeft().add("Visceral last death: " + lastDeath);
    }

    /** For the in-game test: forces the next deaths' style (0 burst, 1 decapitation, 2 limb and corpse), -1 = random. */
    static int testStyle = -1;

    private static void death(Minecraft mc, EntityLivingBase l, State s, Type t, float lastHealth) {
        if (t.none()) return;
        Hit h = cause(mc, l, s, 1.2f);
        Attack a = h.melee;
        double[] d = {h.dx, h.dz};
        if (h.cause == Cause.PROJECTILE) { double m = Math.max(1e-3, Math.sqrt(h.dx * h.dx + h.dz * h.dz)); d = new double[]{h.dx / m, h.dz / m}; }
        float k = strength(l, lastHealth);
        float amt = Blood.amount();
        // how big it is, next to a zombie
        float size = MathHelper.clamp((l.width * l.height) / (0.6f * 1.95f), 0.3f, 6f);
        double cx = l.posX, cy = l.posY + l.height * 0.5, cz = l.posZ;
        boolean fire = h.cause == Cause.FIRE, boom = h.cause == Cause.EXPLOSION, fall = h.cause == Cause.FALL;
        if (h.cause == Cause.QUIET) {
            lastDeath = name(l) + ": died quietly (drowned, poison or wither)";
            // drowned, poisoned, withered: it just dies
            if (VisceralConfig.extras.bloodPools && wet(t) && !l.isInWater()) Blood.groundStain(t, cx, l.posY + 0.1, cz, Math.max(0.6f, l.width * 1.2f), 4f, Blood.T_POOL);
            return;
        }
        BloodTypes.Options opt = BloodTypes.options(l);
        boolean roll = (boom && opt.gibs > 0) || Blood.RAND.nextDouble() < VisceralConfig.gibs.gibChance * opt.gibs;
        boolean yours = !VisceralConfig.gibs.onlyYourKills || a != null || h.cause == Cause.PROJECTILE;
        boolean gib = VisceralConfig.gibs.deathGibs && goreAllowed(l, false) && roll && yours;
        if (!gib) {
            String why = VisceralConfig.extras.mild ? "mild mode" : !VisceralConfig.gibs.deathGibs ? "death gibs are off" : !l.isNonBoss() ? "it's a boss"
                    : l instanceof EntityPlayer && !VisceralConfig.gibs.players ? "players don't gib (setting)"
                    : !yours ? "not your kill (onlyYourKills)" : !roll ? "gibChance roll" : "?";
            lastDeath = name(l) + ": died normally (" + why + ") [" + h.cause.name().toLowerCase() + "]";
        }
        if (gib) {
            float force = (float) VisceralConfig.gibs.force * (0.8f + 0.25f * Math.min(2f, k)) * (boom ? 2.2f : fall ? 1.2f : 1f);
            ModelParts.Capture cap = ModelParts.capture(l, 1f);
            // not every death is a burst: some lose their head, some a limb, and fall
            int style = boom || fire || fall ? 0 : testStyle >= 0 ? testStyle : !VisceralConfig.gibs.deathStyles || !opt.styles ? 0
                    : Blood.RAND.nextFloat() < 0.55f ? 0 : Blood.RAND.nextFloat() < 0.6f ? 1 : 2;
            if (style > 0 && cap != null && partDeath(mc, l, s, t, cap, style, d, force, size)) {
                lastDeath = name(l) + ": " + (style == 1 ? "decapitated" : "lost a limb and fell") + " [" + h.cause.name().toLowerCase() + "]";
                return;
            }
            float lift = boom ? 4f : fall ? -2f : 0f;
            boolean parts = Gibs.burst(l, cap, t, d[0], d[1], force, fire, lift);
            if (VisceralConfig.gibs.chunks || !parts) {
                int n = Math.round(VisceralConfig.gibs.chunkCount * (float) Math.sqrt(size) * (parts ? 1f : 1.6f) * (boom ? 1.6f : 1f) * (float) opt.chunks);
                if (!parts) n = Math.max(n, 10);
                // a model we couldn't read: it bursts into chunks of its own colours
                int own = parts ? -1 : ModelParts.averageColor(l);
                Gibs.chunks(t, l.posX, l.posY + l.height * 0.2, l.posZ, l.width, l.height * 0.7f, n, d[0], d[1], force, fire, own);
            }
            lastDeath = name(l) + ": " + (fire ? "burnt up" : boom ? "blown apart" : fall ? "splattered (fall)" : "burst")
                    + (parts ? "" : " (model not readable: chunks)") + " [" + h.cause.name().toLowerCase() + "]";
            if (fire) {
                // burnt up: smoke and embers, no blood
                for (int i = 0; i < 3; i++) Blood.mist(BloodTypes.ember(), cx, cy + (i - 1) * l.height * 0.25, cz, 1.2f);
                Blood.spray(BloodTypes.ember(), cx, cy, cz, 0, 1, 0, 25, 3f, 1f);
                Sfx.burst(BloodTypes.ember(), cx, cy, cz);
            } else {
                int drops = Math.round((45 + 30 * size) * amt * (boom ? 1.5f : 1f));
                double up = boom ? 1.4 : fall ? 0.2 : 1;
                Blood.spray(t, cx, cy, cz, d[0] * 0.5, up, d[1] * 0.5, drops, 5f * (float) Math.sqrt(force), 1.0f);
                Blood.spray(t, cx, cy, cz, d[0], 0.3, d[1], drops / 2, 6f, boom ? 1.2f : 0.4f);
                for (int i = 0; i < 3; i++) Blood.mist(t, cx, cy + (i - 1) * l.height * 0.25, cz, 1.1f * (float) Math.sqrt(size));
                Blood.groundStain(t, cx, l.posY + 0.1, cz, Math.min(4f, Math.max(l.width * (fall ? 3.2f : 2.4f), 1.2f)), 0.12f,
                        Blood.T_SPLAT0 + Blood.RAND.nextInt(Blood.T_SPLATS));
                Sfx.burst(t, cx, cy, cz);
                if (a != null && wet(t) && l.getDistanceSq(mc.player) < 3.2 * 3.2 && mc.gameSettings.thirdPersonView == 0) ScreenBlood.spray(t.color, k);
                if (boom && wet(t) && l.getDistanceSq(mc.player) < 6 * 6 && mc.gameSettings.thirdPersonView == 0) ScreenBlood.spray(t.color, 2f);
            }
            // the body is gone: take it out of the world now, so there's no corpse for another mod to
            // draw, no shadow left behind, and no puff of smoke when it would have vanished
            if (!(l instanceof EntityPlayer)) l.setDead();
        } else if (fire) {
            Blood.mist(BloodTypes.ember(), cx, cy, cz, 0.9f);
            Sfx.burn(cx, cy, cz);
        } else {
            // dies like vanilla, with a last spurt and a pool spreading under it
            Blood.spray(t, cx, cy, cz, d[0], 0.5, d[1], Math.round((14 + 10 * size) * amt), 3f, 0.6f);
            Blood.mist(t, cx, cy, cz, 0.8f);
            Sfx.hit(t, cx, cy, cz, k);
            if (VisceralConfig.extras.bloodPools) {
                Blood.groundStain(t, cx, l.posY + 0.1, cz, Math.min(3.5f, Math.max(0.8f, l.width * 1.9f)), 3f, Blood.T_POOL);
                if (wet(t) && Blood.RAND.nextBoolean()) Flies.swarm(cx, l.posY + 0.1, cz, 50f, 18f);
            }
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
            if (wet(t) && Blood.RAND.nextBoolean()) Flies.swarm(l.posX, l.posY + 0.1, l.posZ, 50f, 18f);
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
