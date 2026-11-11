package com.visceral.client;

import com.visceral.Visceral;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.IEntityOwnable;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.DamageSource;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.CriticalHitEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In single player the game's own server runs inside the same program, so Visceral can simply ask it
 * what really happened: the damage type, who did it (you, your pet, a mob, nobody), with what weapon,
 * whether it was a critical or a sweep, and where an arrow hit. The client side reads this instead of
 * guessing. On a multiplayer server none of this exists and the client goes back to its own guesses,
 * so nothing has to be installed on servers.
 *
 * Written on the server thread, read on the client thread: everything here is immutable once stored.
 */
@Mod.EventBusSubscriber(modid = Visceral.MODID, value = Side.CLIENT)
public final class Exact {
    private Exact() {}

    enum Kind { MELEE, PROJECTILE, EXPLOSION, FIRE, FALL, QUIET, OTHER }

    /** What the server says hurt a creature. */
    static final class Info {
        final long time;
        final Kind kind;
        final UUID attacker;          // the one responsible (the shooter of an arrow, not the arrow); null if nobody
        final UUID owner;             // the attacker's owner, if it's a pet
        final double ax, ay, az;      // where the attacker (or the explosion) was
        final boolean hasPoint;       // a projectile: where it was, and how it was moving
        final double px, py, pz, mx, my, mz;
        final boolean headshot, crit, sweep;
        final Weapons.Kind weapon;

        Info(long time, Kind kind, UUID attacker, UUID owner, double ax, double ay, double az, boolean hasPoint,
             double px, double py, double pz, double mx, double my, double mz, boolean headshot, boolean crit, boolean sweep,
             Weapons.Kind weapon) {
            this.time = time; this.kind = kind; this.attacker = attacker; this.owner = owner;
            this.ax = ax; this.ay = ay; this.az = az; this.hasPoint = hasPoint;
            this.px = px; this.py = py; this.pz = pz; this.mx = mx; this.my = my; this.mz = mz;
            this.headshot = headshot; this.crit = crit; this.sweep = sweep; this.weapon = weapon;
        }

        /** You did it, or your pet did. */
        boolean by(UUID you) {
            return you != null && (you.equals(attacker) || you.equals(owner));
        }
    }

    private static final Map<Integer, Info> HURT = new ConcurrentHashMap<>();
    /** Per player, the creature their swing was aimed at and when (anything else hurt by that swing was swept). */
    private static final Map<UUID, long[]> SWING = new ConcurrentHashMap<>();
    /** Per creature, a critical hit just landed on it. */
    private static final Map<Integer, Long> CRITS = new ConcurrentHashMap<>();

    /** The server's word on what last hurt creature `id`, if it's fresh; null on a multiplayer server. */
    static Info get(int id, long maxAgeMs) {
        Info i = HURT.get(id);
        return i != null && System.currentTimeMillis() - i.time <= maxAgeMs ? i : null;
    }

    private static boolean local(Entity e) {
        if (e == null || e.world == null || e.world.isRemote) return false;
        MinecraftServer s = e.getServer();
        return s != null && !s.isDedicatedServer();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = false)
    public static void onSwing(AttackEntityEvent event) {
        if (!local(event.getEntityPlayer())) return;
        try {
            long tick = event.getEntityPlayer().world.getTotalWorldTime();
            SWING.put(event.getEntityPlayer().getUniqueID(), new long[]{event.getTarget().getEntityId(), tick});
        } catch (RuntimeException ignored) {
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onCrit(CriticalHitEvent event) {
        if (!local(event.getEntityPlayer()) || event.getTarget() == null) return;
        try {
            boolean crit = event.getResult() == net.minecraftforge.fml.common.eventhandler.Event.Result.ALLOW
                    || (event.getResult() == net.minecraftforge.fml.common.eventhandler.Event.Result.DEFAULT && event.isVanillaCritical());
            if (crit) CRITS.put(event.getTarget().getEntityId(), System.currentTimeMillis());
        } catch (RuntimeException ignored) {
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onHurt(LivingHurtEvent event) {
        if (event.isCanceled()) return;
        record(event.getEntityLiving(), event.getSource());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        if (event.isCanceled()) return;
        record(event.getEntityLiving(), event.getSource());
    }

    private static void record(EntityLivingBase victim, DamageSource src) {
        if (!local(victim) || src == null) return;
        try {
            long now = System.currentTimeMillis();
            Entity imm = src.getImmediateSource(), tru = src.getTrueSource();
            Kind kind;
            if (src.isExplosion()) kind = Kind.EXPLOSION;
            else if (src.isFireDamage()) kind = Kind.FIRE;
            else if (src == DamageSource.FALL || src == DamageSource.FLY_INTO_WALL) kind = Kind.FALL;
            else if (src.isProjectile() || (imm != null && tru != null && imm != tru)) kind = Kind.PROJECTILE;
            else if (imm instanceof EntityLivingBase) kind = Kind.MELEE;
            else if (src == DamageSource.DROWN || src == DamageSource.STARVE || src.isMagicDamage() || src == DamageSource.WITHER
                    || src == DamageSource.IN_WALL || src == DamageSource.CRAMMING || src == DamageSource.OUT_OF_WORLD) kind = Kind.QUIET;
            else kind = Kind.OTHER;

            UUID att = tru != null ? tru.getUniqueID() : imm != null ? imm.getUniqueID() : null;
            UUID own = tru instanceof IEntityOwnable ? ((IEntityOwnable) tru).getOwnerId() : null;
            double ax = Double.NaN, ay = Double.NaN, az = Double.NaN;
            Vec3d loc = src.getDamageLocation();
            if (kind == Kind.EXPLOSION && loc != null) { ax = loc.x; ay = loc.y; az = loc.z; }
            else if (tru != null) { ax = tru.posX; ay = tru.posY + tru.height * 0.5; az = tru.posZ; }
            else if (loc != null) { ax = loc.x; ay = loc.y; az = loc.z; }

            boolean hasPoint = false, head = false;
            double px = 0, py = 0, pz = 0, mx = 0, my = 0, mz = 0;
            if (kind == Kind.PROJECTILE && imm != null) {
                hasPoint = true;
                px = imm.posX; py = imm.posY + imm.height * 0.5; pz = imm.posZ;
                mx = imm.motionX; my = imm.motionY; mz = imm.motionZ;
                // a headshot: it went in around the eyes, in the top part of the body
                double eye = victim.posY + victim.getEyeHeight();
                head = py > victim.posY + victim.height * 0.6 && Math.abs(py - eye) < Math.max(0.3, victim.height * 0.17);
            }

            Weapons.Kind weapon = Weapons.Kind.NONE;
            boolean crit = false, sweep = false;
            if (kind == Kind.MELEE && imm instanceof EntityLivingBase) {
                ItemStack held = ((EntityLivingBase) imm).getHeldItemMainhand();
                weapon = Weapons.kind(held);
                Long c = CRITS.get(victim.getEntityId());
                crit = c != null && now - c < 250;
                if (imm instanceof EntityPlayer) {
                    long[] sw = SWING.get(imm.getUniqueID());
                    // hurt by this player's swing on this tick, but it wasn't what they aimed at: the sweep caught it
                    sweep = sw != null && sw[0] != victim.getEntityId() && sw[1] == victim.world.getTotalWorldTime();
                }
            } else if (kind == Kind.PROJECTILE && imm != null) {
                weapon = Weapons.Kind.PIERCE;
            }
            HURT.put(victim.getEntityId(), new Info(now, kind, att, own, ax, ay, az, hasPoint, px, py, pz, mx, my, mz, head, crit, sweep, weapon));
            if (HURT.size() > 512) HURT.values().removeIf(i -> now - i.time > 5000);
            if (CRITS.size() > 64) CRITS.values().removeIf(t -> now - t > 1000);
        } catch (RuntimeException ignored) {
            // the server's view is a bonus; the client's own guesses still work without it
        }
    }
}
