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
        final int attacker;           // the one responsible (the shooter of an arrow, not the arrow); -1 if nobody
        final int owner;              // the attacker's owner, if it's a pet; -1 if not
        final double ax, ay, az;      // where the attacker (or the explosion) was
        final boolean hasPoint;       // a projectile: where it was, and how it was moving
        final double px, py, pz, mx, my, mz;
        final boolean headshot, crit, sweep;
        final Weapons.Kind weapon;

        Info(long time, Kind kind, int attacker, int owner, double ax, double ay, double az, boolean hasPoint,
             double px, double py, double pz, double mx, double my, double mz, boolean headshot, boolean crit, boolean sweep,
             Weapons.Kind weapon) {
            this.time = time; this.kind = kind; this.attacker = attacker; this.owner = owner;
            this.ax = ax; this.ay = ay; this.az = az; this.hasPoint = hasPoint;
            this.px = px; this.py = py; this.pz = pz; this.mx = mx; this.my = my; this.mz = mz;
            this.headshot = headshot; this.crit = crit; this.sweep = sweep; this.weapon = weapon;
        }

        /** You (your entity id; the same on the server and every client) did it, or your pet did. */
        boolean by(int you) {
            return you >= 0 && (you == attacker || you == owner);
        }

        Info at(long now) {
            return new Info(now, kind, attacker, owner, ax, ay, az, hasPoint, px, py, pz, mx, my, mz, headshot, crit, sweep, weapon);
        }

        void write(io.netty.buffer.ByteBuf b) {
            b.writeByte(kind.ordinal()); b.writeInt(attacker); b.writeInt(owner);
            b.writeDouble(ax); b.writeDouble(ay); b.writeDouble(az);
            b.writeBoolean(hasPoint);
            b.writeDouble(px); b.writeDouble(py); b.writeDouble(pz);
            b.writeFloat((float) mx); b.writeFloat((float) my); b.writeFloat((float) mz);
            b.writeByte((headshot ? 1 : 0) | (crit ? 2 : 0) | (sweep ? 4 : 0));
            b.writeByte(weapon.ordinal());
        }

        static Info read(io.netty.buffer.ByteBuf b) {
            Kind kind = Kind.values()[Math.min(Kind.values().length - 1, b.readUnsignedByte())];
            int att = b.readInt(), own = b.readInt();
            double ax = b.readDouble(), ay = b.readDouble(), az = b.readDouble();
            boolean hp = b.readBoolean();
            double px = b.readDouble(), py = b.readDouble(), pz = b.readDouble();
            double mx = b.readFloat(), my = b.readFloat(), mz = b.readFloat();
            int f = b.readUnsignedByte();
            Weapons.Kind w = Weapons.Kind.values()[Math.min(Weapons.Kind.values().length - 1, b.readUnsignedByte())];
            return new Info(System.currentTimeMillis(), kind, att, own, ax, ay, az, hp, px, py, pz, mx, my, mz,
                    (f & 1) != 0, (f & 2) != 0, (f & 4) != 0, w);
        }
    }

    private static final Map<Integer, Info> HURT = new ConcurrentHashMap<>();
    /** Per player, the creature their swing was aimed at and when (anything else hurt by that swing was swept). */
    private static final Map<Integer, long[]> SWING = new ConcurrentHashMap<>();
    /** Per creature, a critical hit just landed on it. */
    private static final Map<Integer, Long> CRITS = new ConcurrentHashMap<>();

    /** The server's word on what last hurt creature `id`, if it's fresh; null on a multiplayer server. */
    static Info get(int id, long maxAgeMs) {
        Info i = HURT.get(id);
        return i != null && System.currentTimeMillis() - i.time <= maxAgeMs ? i : null;
    }

    // ------------------------------------------------------------------ LAN: telling your friends

    /** Your friends' games (with Visceral) are told the same, so they see it the way you do. */
    public static net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper NET;
    /** For the test: the host gets it by network too, as a friend would. */
    static boolean viaNetwork;

    public static void register() {
        NET = net.minecraftforge.fml.common.network.NetworkRegistry.INSTANCE.newSimpleChannel(Visceral.MODID);
        NET.registerMessage(Handler.class, Msg.class, 0, Side.CLIENT);
    }

    public static final class Msg implements net.minecraftforge.fml.common.network.simpleimpl.IMessage {
        int id;
        Info info;

        public Msg() {}

        Msg(int id, Info info) { this.id = id; this.info = info; }

        @Override
        public void fromBytes(io.netty.buffer.ByteBuf b) {
            id = b.readInt();
            info = Info.read(b);
        }

        @Override
        public void toBytes(io.netty.buffer.ByteBuf b) {
            b.writeInt(id);
            info.write(b);
        }
    }

    public static final class Handler implements net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler<Msg, net.minecraftforge.fml.common.network.simpleimpl.IMessage> {
        @Override
        public net.minecraftforge.fml.common.network.simpleimpl.IMessage onMessage(Msg m, net.minecraftforge.fml.common.network.simpleimpl.MessageContext ctx) {
            if (m.info != null) HURT.put(m.id, m.info.at(System.currentTimeMillis()));
            return null;
        }
    }

    /** Sends it to the other players nearby whose game has Visceral (your own game already knows). */
    private static void share(EntityLivingBase victim, Info info) {
        if (NET == null) return;
        MinecraftServer s = victim.getServer();
        if (s == null) return;
        String host = s.getServerOwner();
        Msg msg = null;
        for (net.minecraft.entity.player.EntityPlayerMP p : s.getPlayerList().getPlayers()) {
            if (p.dimension != victim.dimension || p.getDistanceSq(victim) > 96 * 96) continue;
            if (!viaNetwork && host != null && host.equals(p.getName())) continue;
            if (!hasVisceral(p)) continue;
            if (msg == null) msg = new Msg(victim.getEntityId(), info);
            NET.sendTo(msg, p);
        }
    }

    private static boolean hasVisceral(net.minecraft.entity.player.EntityPlayerMP p) {
        try {
            net.minecraftforge.fml.common.network.handshake.NetworkDispatcher d =
                    net.minecraftforge.fml.common.network.handshake.NetworkDispatcher.get(p.connection.getNetworkManager());
            if (d == null) return false;
            Map<String, String> mods = d.getModList();
            return mods != null && mods.containsKey(Visceral.MODID);
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
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
            SWING.put(event.getEntityPlayer().getEntityId(), new long[]{event.getTarget().getEntityId(), tick});
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

            int att = tru != null ? tru.getEntityId() : imm != null ? imm.getEntityId() : -1;
            Entity ownerE = tru instanceof IEntityOwnable ? ((IEntityOwnable) tru).getOwner() : null;
            int own = ownerE != null ? ownerE.getEntityId() : -1;
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
                    long[] sw = SWING.get(imm.getEntityId());
                    // hurt by this player's swing on this tick, but it wasn't what they aimed at: the sweep caught it
                    sweep = sw != null && sw[0] != victim.getEntityId() && sw[1] == victim.world.getTotalWorldTime();
                }
            } else if (kind == Kind.PROJECTILE && imm != null) {
                weapon = Weapons.Kind.PIERCE;
            }
            Info info = new Info(now, kind, att, own, ax, ay, az, hasPoint, px, py, pz, mx, my, mz, head, crit, sweep, weapon);
            if (!viaNetwork) HURT.put(victim.getEntityId(), info);
            share(victim, info);
            if (HURT.size() > 512) HURT.values().removeIf(i -> now - i.time > 5000);
            if (CRITS.size() > 64) CRITS.values().removeIf(t -> now - t > 1000);
        } catch (RuntimeException ignored) {
            // the server's view is a bonus; the client's own guesses still work without it
        }
    }
}
