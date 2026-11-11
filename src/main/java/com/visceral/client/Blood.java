package com.visceral.client;

import com.visceral.Visceral;
import com.visceral.VisceralConfig;
import com.visceral.client.BloodTypes.Style;
import com.visceral.client.BloodTypes.Type;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Blood in the world, made to behave like the real thing:
 *  - droplets of every size (mostly fine spray, some fat drops) flying with gravity and drag
 *  - a drop landing straight on leaves a round splat with a crown of spines and beads; one landing
 *    at an angle leaves a stretched splat with its spines and drops thrown ahead, pointing the way
 *    it flew
 *  - on walls, bigger splats run: a trickle creeps down the wall over a few seconds
 *  - on ceilings, blood gathers and drips back down for a while, staining the floor below
 *  - fresh blood is glossy and bright; as it dries it loses its shine and darkens to maroon-brown
 * Stains take the world's light and fog. It all runs off one texture (and its gloss), drawn in two
 * batches, so a lot of blood stays cheap.
 */
@Mod.EventBusSubscriber(modid = Visceral.MODID, value = Side.CLIENT)
public final class Blood {
    private Blood() {}

    public static final ResourceLocation ATLAS = new ResourceLocation(Visceral.MODID, "textures/fx/gore.png");
    public static final ResourceLocation GLOSS = new ResourceLocation(Visceral.MODID, "textures/fx/gore_gloss.png");
    public static final ResourceLocation PRINTS = new ResourceLocation(Visceral.MODID, "textures/fx/prints.png");
    static final int P_BOOT = 0, P_PAW = 1;
    /** Tiles of the 4x4 atlas. 0-3 round splats, 4-7 angled splats (pointing +u). */
    static final int T_SPLAT0 = 0, T_SPLATS = 4, T_ANGLED0 = 4, T_DOT = 8, T_SMEAR = 9, T_SPATTER = 10, T_POOL = 11,
            T_PUFF = 12, T_DROP = 13, T_SHARD = 14, T_SCORCH = 15;

    private static final float DT = 0.05f;

    /** A droplet (or a spark, an ember, a puff of dust...). */
    static final class Drop {
        double x, y, z, px, py, pz;
        float vx, vy, vz;
        float size, grav, drag, age, life, alpha;
        int color, tile;
        Type type;
        boolean glow, streak, stains, puff, quiet, cloud, flat;
        float grow;         // puffs: how much bigger they get over their life
    }

    /** A stain on a block face. */
    static final class Stain {
        EnumFacing face;
        Type type;
        double plane;                  // the face's plane
        double u0, u1, v0, v1;         // the stain's full rectangle on the face (world units)
        double umin, umax, vmin, vmax; // the block face it's on: the stain is cut to it
        double cu, cv;                 // where it landed
        float born, life, grow, alpha, dryAt;
        int color, tile, orient, light;  // orient: 1 flip x, 2 flip y, 4 swap (picture turned 90 degrees)
        boolean run;                   // a trickle: revealed from the top down as it creeps
        int drips;                     // a ceiling stain: drops still to fall
        float nextDrip;
        BlockPos lightPos;
        BlockPos host;                 // the block it's on: if that goes (or changes shape), so does the stain
        float offset;                  // a hair off the face, a little more for each stain (no flicker)
        int atlas;                     // 0 gore.png, 1 prints.png (footprints)
    }

    private static final List<Drop> DROPS = new ArrayList<>();
    /** Droplets made while the others are being moved (a drop landing in water makes a cloud): added after. */
    private static final List<Drop> NEW_DROPS = new ArrayList<>();
    private static boolean moving;

    private static void addDrop(Drop d) {
        if (moving) NEW_DROPS.add(d); else DROPS.add(d);
    }
    private static final ArrayDeque<Stain> STAINS = new ArrayDeque<>();
    /** Floor stains by the block they're on, so feet can find wet blood quickly. */
    private static final java.util.Map<Long, List<Stain>> FLOOR = new java.util.HashMap<>();
    private static int clouds;

    private static void index(Stain s) {
        if (s.face != EnumFacing.UP || s.host == null) return;
        FLOOR.computeIfAbsent(s.host.toLong(), k -> new ArrayList<>(4)).add(s);
    }

    private static void unindex(Stain s) {
        if (s.face != EnumFacing.UP || s.host == null) return;
        List<Stain> l = FLOOR.get(s.host.toLong());
        if (l != null) { l.remove(s); if (l.isEmpty()) FLOOR.remove(s.host.toLong()); }
    }
    static final Random RAND = new Random();
    static float clock;
    private static World lastWorld;
    private static int stainSeq;

    static boolean on() {
        return VisceralConfig.enabled && VisceralConfig.blood.blood;
    }

    static float amount() {
        return (float) VisceralConfig.blood.amount;
    }

    // ------------------------------------------------------------------ spawning

    /**
     * A spray of n droplets from (x,y,z), thrown along (dx,dy,dz) (need not be normalised) at about
     * `speed` blocks/second, spreading `spread` (0 = a jet, 1 = everywhere).
     */
    public static void spray(Type t, double x, double y, double z, double dx, double dy, double dz, int n, float speed, float spread) {
        if (!on() || t.none()) return;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-4) { dx = 0; dy = 1; dz = 0; len = 1; }
        dx /= len; dy /= len; dz /= len;
        int cap = VisceralConfig.blood.maxDroplets;
        for (int k = 0; k < n && DROPS.size() < cap; k++) {
            Drop d = make(t, x, y, z);
            // fine droplets fly fastest and spread widest; fat drops are slower and stay on line
            float fine = 1f - Math.min(1f, d.size / 0.09f);
            float sp = speed * (0.35f + RAND.nextFloat() * 0.75f) * (0.8f + 0.5f * fine);
            float sprd = spread * (0.7f + 0.6f * fine);
            double rx = dx + RAND.nextGaussian() * sprd, ry = dy + RAND.nextGaussian() * sprd, rz = dz + RAND.nextGaussian() * sprd;
            double rl = Math.sqrt(rx * rx + ry * ry + rz * rz);
            if (rl < 1e-4) rl = 1;
            d.vx = (float) (rx / rl) * sp;
            d.vy = (float) (ry / rl) * sp + 1.0f;
            d.vz = (float) (rz / rl) * sp;
            addDrop(d);
        }
    }

    /** One droplet falling from (x,y,z): bleeding, a ceiling drip, the drip off a flying gib. */
    public static void drip(Type t, double x, double y, double z) {
        if (!on() || t.none() || DROPS.size() >= VisceralConfig.blood.maxDroplets) return;
        Drop d = make(t, x, y, z);
        d.vx = (RAND.nextFloat() - 0.5f) * 0.2f;
        d.vz = (RAND.nextFloat() - 0.5f) * 0.2f;
        d.vy = -0.2f;
        if (t.style == Style.LIQUID || t.style == Style.SLIME) d.size = 0.035f + RAND.nextFloat() * 0.03f;   // drips are fat
        addDrop(d);
    }

    private static Drop make(Type t, double x, double y, double z) {
        Drop d = new Drop();
        d.x = d.px = x; d.y = d.py = y; d.z = d.pz = z;
        d.type = t;
        d.color = vary(t.color, 0.1f);
        d.alpha = 0.95f;
        d.tile = T_DROP;
        d.streak = true;
        d.stains = t.stains() && VisceralConfig.blood.stains;
        d.grav = 9.8f;   // real gravity: a block is a metre
        d.drag = 1.2f;
        d.life = 4f;
        // mostly a fine spray, some medium drops, a few fat ones
        float r = RAND.nextFloat();
        d.size = r < 0.55f ? 0.012f + RAND.nextFloat() * 0.018f
                : r < 0.9f ? 0.03f + RAND.nextFloat() * 0.03f
                : 0.06f + RAND.nextFloat() * 0.04f;
        if (d.size < 0.03f) d.drag = 2.2f;   // fine mist slows down quickly in the air
        switch (t.style) {
            case SLIME:
                d.size = Math.max(d.size, 0.03f) * 1.6f;
                d.grav = 9.8f;
                d.drag = 1.0f;
                d.alpha = 0.9f;
                break;
            case DUST:
                // bone dust: a puff that drifts, and the odd chip of bone
                if (RAND.nextInt(3) == 0) {
                    d.tile = T_SHARD;
                    d.size = 0.03f + RAND.nextFloat() * 0.03f;
                    d.streak = false;
                    d.life = 1.2f;
                } else {
                    d.puff = true;
                    d.tile = T_PUFF;
                    d.streak = false;
                    d.size = 0.12f + RAND.nextFloat() * 0.14f;
                    d.grow = 2.2f;
                    d.grav = 0.8f;
                    d.drag = 5f;
                    d.alpha = 0.55f;
                    d.life = 0.6f + RAND.nextFloat() * 0.6f;
                }
                break;
            case SPARKS:
                d.glow = true;
                d.color = RAND.nextInt(3) == 0 ? 0xFFFFFF : vary(t.color, 0.1f);
                d.size = 0.02f + RAND.nextFloat() * 0.02f;
                d.grav = 9f;
                d.drag = 2.5f;
                d.life = 0.35f + RAND.nextFloat() * 0.45f;
                break;
            case EMBERS:
                d.glow = RAND.nextInt(4) != 0;
                if (!d.glow) {
                    d.puff = true;
                    d.tile = T_PUFF;
                    d.streak = false;
                    d.color = 0x2A2622;
                    d.alpha = 0.45f;
                    d.size = 0.15f + RAND.nextFloat() * 0.15f;
                    d.grow = 2.5f;
                    d.grav = -1.2f;
                    d.drag = 4f;
                    d.life = 0.9f + RAND.nextFloat() * 0.8f;
                    d.stains = false;
                } else {
                    d.color = RAND.nextInt(3) == 0 ? 0xFFE080 : vary(t.color, 0.1f);
                    d.size = 0.025f + RAND.nextFloat() * 0.025f;
                    d.grav = 6f;
                    d.drag = 2f;
                    d.life = 0.6f + RAND.nextFloat() * 0.9f;
                }
                break;
            case ECTO:
                d.glow = true;
                d.alpha = 0.8f;
                d.grav = 2.5f;
                d.drag = 2.2f;
                d.life = 0.7f + RAND.nextFloat() * 0.8f;
                break;
            default:
                break;
        }
        return d;
    }

    /**
     * Blood in water: it spreads out across the surface in a slick, drifting and fading. (Drawn on
     * the surface: the game draws water over anything just under it.)
     */
    static void cloud(Type t, double x, double y, double z, float dropSize) {
        if (clouds >= 160 || DROPS.size() >= VisceralConfig.blood.maxDroplets) return;
        World w = Minecraft.getMinecraft().world;
        BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos(); 
        bp.setPos(x, y, z);
        int up = 0;
        while (up < 6 && w.getBlockState(bp.up()).getMaterial() == Material.WATER) { bp.setPos(x, bp.getY() + 1, z); up++; }
        double surface = bp.getY() + 0.89 + 0.012;
        Drop d = new Drop();
        d.flat = true;
        d.x = d.px = x; d.y = d.py = surface; d.z = d.pz = z;
        d.type = t;
        d.puff = true;
        d.cloud = true;
        d.tile = T_PUFF;
        d.color = dark(vary(t.color, 0.1f), 0.85f);
        d.alpha = Math.min(0.55f, 0.25f + dropSize * 4f);
        d.size = 0.12f + dropSize * 3f;
        d.grow = 4.5f;
        d.alpha = Math.min(0.6f, 0.35f + dropSize * 4f);
        d.size = 0.2f + dropSize * 4f;
        d.vx = (RAND.nextFloat() - 0.5f) * 0.3f;
        d.vz = (RAND.nextFloat() - 0.5f) * 0.3f;
        d.grav = 0f;
        d.drag = 1.2f;
        d.life = 5f + RAND.nextFloat() * 4f;
        clouds++;
        addDrop(d);
    }

    // ------------------------------------------------------------------ footprints

    /** Wet blood under these feet (a floor stain still fresh), or null. */
    static Stain wetFloor(World w, double x, double y, double z) {
        BlockPos host = new BlockPos(Math.floor(x), Math.floor(y - 0.05), Math.floor(z));
        List<Stain> l = FLOOR.get(host.toLong());
        if (l == null) return null;
        for (Stain s : l) {
            if (s.atlas != 0 || s.tile == T_SCORCH || s.type == null) continue;
            if (clock - s.born > s.dryAt * 0.8f) continue;     // dried: nothing to step in
            if (Math.abs(s.plane - y) > 0.3) continue;
            double mu = (s.u0 + s.u1) * 0.5, mv = (s.v0 + s.v1) * 0.5, hu = (s.u1 - s.u0) * 0.32, hv = (s.v1 - s.v0) * 0.32;
            if (Math.abs(x - mu) < hu && Math.abs(z - mv) < hv && (s.u1 - s.u0) > 0.2) return s;
        }
        return null;
    }

    /**
     * A footprint at (x, ground, z), walking toward yaw (degrees, the game's), left or right foot,
     * a boot or a paw, `size` blocks long, `strength` 0-1 (how much blood is left on the feet).
     */
    static void print(Type t, int color, double x, double y, double z, float yaw, boolean left, boolean paw, float size, float strength) {
        if (!on() || !VisceralConfig.extras.footprints) return;
        World w = Minecraft.getMinecraft().world;
        if (w == null) return;
        double top = ground(w, x, y + 0.1, z);
        if (Double.isNaN(top)) return;
        double fx = -Math.sin(Math.toRadians(yaw)), fz = Math.cos(Math.toRadians(yaw));
        // left and right feet sit a little apart, across the way it's walking
        double side = (left ? 1 : -1) * size * 0.28;
        x += fz * side; z -= fx * side;
        BlockPos host = new BlockPos(Math.floor(x), Math.floor(top - 0.01), Math.floor(z));
        IBlockState st = w.getBlockState(host);
        AxisAlignedBB bb = st.getCollisionBoundingBox(w, host);
        if (bb == null) return;
        bb = bb.offset(host);
        // pictures point toe-up (-v); turn them to the nearest of the four ways it's walking
        int orient;
        if (Math.abs(fx) > Math.abs(fz)) orient = 4 | (fx > 0 ? 2 : 0);
        else orient = fz > 0 ? 2 : 0;
        if (!left) orient |= 1;
        Stain s = addStain(t, EnumFacing.UP, bb, x, bb.maxY, z, size, size, 0f, paw ? P_PAW : P_BOOT, orient, host.up(), host);
        s.atlas = 1;
        s.color = color;
        s.alpha = 0.35f + 0.6f * strength;
        s.dryAt = Math.max(5f, s.dryAt * 0.5f);   // a thin print dries quickly
    }

    /** A burst of mist (or dust, smoke, a flash for sparks) where a hit lands, with a fine spatter. */
    public static void mist(Type t, double x, double y, double z, float size) {
        if (!on() || t.none()) return;
        boolean wet = t.style == Style.LIQUID || t.style == Style.SLIME;
        int n = 3 + RAND.nextInt(3);
        for (int k = 0; k < n && DROPS.size() < VisceralConfig.blood.maxDroplets; k++) {
            Drop d = new Drop();
            d.x = d.px = x + (RAND.nextDouble() - 0.5) * 0.15 * size;
            d.y = d.py = y + (RAND.nextDouble() - 0.5) * 0.15 * size;
            d.z = d.pz = z + (RAND.nextDouble() - 0.5) * 0.15 * size;
            d.type = t;
            d.puff = true;
            d.tile = T_PUFF;
            d.color = t.style == Style.EMBERS ? 0x3A302A : t.style == Style.SPARKS ? 0xFFF0C0 : wet ? dark(vary(t.color, 0.1f), 0.8f) : vary(t.color, 0.1f);
            d.glow = t.style == Style.SPARKS || t.style == Style.ECTO;
            d.alpha = t.style == Style.SPARKS ? 0.7f : wet ? 0.38f : 0.5f;
            d.size = (wet ? 0.14f : 0.18f) * size + RAND.nextFloat() * 0.1f * size;
            d.grow = wet ? 3f : 2.4f;
            d.vx = (RAND.nextFloat() - 0.5f) * 1.2f;
            d.vy = (RAND.nextFloat() - 0.3f) * 1.0f;
            d.vz = (RAND.nextFloat() - 0.5f) * 1.2f;
            d.grav = wet ? 1.2f : 0.5f;
            d.drag = 6f;
            d.life = t.style == Style.SPARKS ? 0.18f : 0.3f + RAND.nextFloat() * 0.3f;
            addDrop(d);
        }
        if (wet) {
            // the fine spatter thrown off with the mist
            for (int k = 0; k < 10 && DROPS.size() < VisceralConfig.blood.maxDroplets; k++) {
                Drop d = make(t, x, y, z);
                d.size = 0.008f + RAND.nextFloat() * 0.01f;
                d.drag = 3f;
                double a = RAND.nextDouble() * Math.PI * 2;
                float sp = 3f + RAND.nextFloat() * 4f;
                d.vx = (float) Math.cos(a) * sp;
                d.vy = (RAND.nextFloat() - 0.3f) * sp;
                d.vz = (float) Math.sin(a) * sp;
                d.quiet = true;
                addDrop(d);
            }
        }
    }

    /**
     * A stain on the ground under (x,y,z) (searching a few blocks down), `size` blocks across,
     * spreading out over `grow` seconds: blood pools, the splat under a burst.
     */
    public static void groundStain(Type t, double x, double y, double z, float size, float grow, int tile) {
        if (!on() || !t.stains() || !VisceralConfig.blood.stains) return;
        World w = Minecraft.getMinecraft().world;
        if (w == null) return;
        double top = ground(w, x, y, z);
        if (Double.isNaN(top)) return;
        // one stain, laid over every block it covers (each piece cut to its own block, the picture
        // continuing from one to the next), so a pool spreads across the floor instead of stopping
        // square at the edge of the block it started on
        int orient = RAND.nextInt(8);
        double half = size * 0.5;
        Stain first = null;
        for (int bx = (int) Math.floor(x - half); bx <= (int) Math.floor(x + half); bx++) {
            for (int bz = (int) Math.floor(z - half); bz <= (int) Math.floor(z + half); bz++) {
                BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();
                for (int dy = 0; dy < 3; dy++) {
                    bp.setPos(bx, Math.floor(top - 0.01) - dy + 1, bz);
                    IBlockState st = w.getBlockState(bp);
                    if (st.getMaterial().isLiquid()) break;
                    AxisAlignedBB bb = st.getCollisionBoundingBox(w, bp);
                    if (bb == null || bb.maxY - bb.minY <= 0) continue;
                    bb = bb.offset(bp);
                    if (Math.abs(bb.maxY - top) > 0.55) break;   // a step up or down: the pool doesn't climb it
                    if (!w.isAirBlock(bp.up()) && w.getBlockState(bp.up()).getCollisionBoundingBox(w, bp.up()) != null) break;
                    Stain s = addStain(t, EnumFacing.UP, bb, x, bb.maxY, z, size, size, grow, tile, orient, bp.up(), bp);
                    if (first == null) {
                        first = s;
                        if (tile == T_POOL) s.dryAt *= 2.5f;   // pools stay wet for much longer than splatter
                    } else {
                        s.color = first.color; s.life = first.life; s.dryAt = first.dryAt; s.born = first.born;
                    }
                    break;
                }
            }
        }
    }

    /** The top of the ground under (x,y,z), searching a few blocks down; NaN if none (or water). */
    private static double ground(World w, double x, double y, double z) {
        BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();
        for (int dy = 0; dy < 4; dy++) {
            bp.setPos(x, y + 0.2 - dy, z);
            IBlockState st = w.getBlockState(bp);
            if (st.getMaterial().isLiquid()) return Double.NaN;
            AxisAlignedBB bb = st.getCollisionBoundingBox(w, bp);
            if (bb == null || bb.maxY - bb.minY <= 0) continue;
            bb = bb.offset(bp);
            if (bb.maxY > y + 0.3) continue;   // a block we're standing in, not on
            return bb.maxY;
        }
        return Double.NaN;
    }

    /** A stain `su` x `sv` blocks (along the face's u and v) centred on (x,y,z). */
    private static Stain addStain(Type t, EnumFacing face, AxisAlignedBB bb, double x, double y, double z,
                                  float su, float sv, float grow, int tile, int orient, BlockPos lightPos, BlockPos host) {
        Stain s = new Stain();
        s.face = face;
        s.type = t;
        double cu, cv;
        switch (face.getAxis()) {
            case Y: s.plane = y; cu = x; cv = z; s.umin = bb.minX; s.umax = bb.maxX; s.vmin = bb.minZ; s.vmax = bb.maxZ; break;
            case Z: s.plane = z; cu = x; cv = y; s.umin = bb.minX; s.umax = bb.maxX; s.vmin = bb.minY; s.vmax = bb.maxY; break;
            default: s.plane = x; cu = z; cv = y; s.umin = bb.minZ; s.umax = bb.maxZ; s.vmin = bb.minY; s.vmax = bb.maxY; break;
        }
        s.cu = cu; s.cv = cv;
        s.u0 = cu - su * 0.5; s.u1 = cu + su * 0.5;
        s.v0 = cv - sv * 0.5; s.v1 = cv + sv * 0.5;
        s.born = clock;
        s.life = VisceralConfig.blood.stainSeconds * (0.85f + RAND.nextFloat() * 0.3f);
        s.dryAt = Math.min(s.life * 0.45f, 40f + RAND.nextFloat() * 20f);
        s.grow = grow;
        s.tile = tile;
        s.orient = orient;
        boolean slime = t.style == Style.SLIME;
        boolean scorch = t.style == Style.EMBERS;
        if (scorch) { s.tile = T_SCORCH; s.color = 0x1C1612; } else s.color = vary(t.color, 0.06f);
        s.alpha = slime ? 0.82f : scorch ? 0.75f : 0.96f;
        s.lightPos = lightPos.toImmutable();
        s.host = host.toImmutable();
        s.light = Minecraft.getMinecraft().world.getCombinedLight(s.lightPos, 0);
        s.offset = 0.0015f + (stainSeq++ % 24) * 0.00025f;
        STAINS.addLast(s);
        index(s);
        while (STAINS.size() > VisceralConfig.blood.maxStains) unindex(STAINS.pollFirst());
        return s;
    }

    // ------------------------------------------------------------------ simulation

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent event) {
        Guard.run("Blood.onTick", () -> onTick0(event));
    }

    private static void onTick0(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world != lastWorld) {
            lastWorld = mc.world;
            DROPS.clear();
            STAINS.clear();
            FLOOR.clear();
            clouds = 0;
        }
        if (mc.world == null || mc.isGamePaused()) return;
        clock += DT;
        World w = mc.world;
        moving = true;
        try {
            moveDrops(w);
        } finally {
            moving = false;
            DROPS.addAll(NEW_DROPS);
            NEW_DROPS.clear();
        }
        // stains: fade out at the end of their life; light looked up again now and then; ceilings drip
        boolean relight = ((int) (clock / DT)) % 10 == 0;
        boolean recheck = ((int) (clock / DT)) % 3 == 0;
        Iterator<Stain> si = STAINS.iterator();
        double px = mc.player == null ? 0 : mc.player.posX, pz = mc.player == null ? 0 : mc.player.posZ;
        double range = VisceralConfig.blood.range;
        List<Stain> dripping = null;
        while (si.hasNext()) {
            Stain s = si.next();
            if (clock - s.born > s.life) { si.remove(); unindex(s); continue; }
            if (relight) s.light = w.getCombinedLight(s.lightPos, 0);
            if (recheck && !onBlock(w, s)) { si.remove(); unindex(s); continue; }
            if (s.drips > 0 && clock >= s.nextDrip) {
                s.drips--;
                s.nextDrip = clock + 0.6f + RAND.nextFloat() * 1.6f;
                // ceiling stains are on a DOWN face: u = x, v = z, the plane is the ceiling's height
                if ((s.cu - px) * (s.cu - px) + (s.cv - pz) * (s.cv - pz) < range * range) {
                    if (dripping == null) dripping = new ArrayList<>();
                    dripping.add(s);
                }
            }
        }
        // (after the loop: a drop spawned now can't touch the stain list while it's being walked)
        if (dripping != null) {
            for (Stain s : dripping) {
                double x = s.cu + (RAND.nextDouble() - 0.5) * 0.04, z = s.cv + (RAND.nextDouble() - 0.5) * 0.04;
                drip(s.type, x, s.plane - 0.03, z);
                if (RAND.nextInt(3) == 0) Sfx.drip(x, s.plane, z);
            }
        }
    }

    /** The stain's block is still there, with its face where the stain is (not mined, not replaced by a slab...). */
    private static boolean onBlock(World w, Stain s) {
        if (s.host == null || !w.isBlockLoaded(s.host)) return true;
        IBlockState st = w.getBlockState(s.host);
        if (st.getMaterial() == Material.AIR) return false;
        AxisAlignedBB bb = st.getCollisionBoundingBox(w, s.host);
        if (bb == null || bb.maxX <= bb.minX) return false;
        bb = bb.offset(s.host);
        double plane;
        switch (s.face) {
            case UP: plane = bb.maxY; break;
            case DOWN: plane = bb.minY; break;
            case EAST: plane = bb.maxX; break;
            case WEST: plane = bb.minX; break;
            case SOUTH: plane = bb.maxZ; break;
            default: plane = bb.minZ; break;
        }
        return Math.abs(plane - s.plane) < 1e-3;
    }

    private static final BlockPos.MutableBlockPos MPOS = new BlockPos.MutableBlockPos();

    private static void moveDrops(World w) {
        Iterator<Drop> it = DROPS.iterator();
        while (it.hasNext()) {
            Drop d = it.next();
            d.age += DT;
            if (d.age >= d.life || d.y < -64) { if (d.cloud) clouds--; it.remove(); continue; }
            d.px = d.x; d.py = d.y; d.pz = d.z;
            float f = (float) Math.exp(-d.drag * DT);
            d.vx *= f; d.vy *= f; d.vz *= f;
            d.vy -= d.grav * DT;
            double nx = d.x + d.vx * DT, ny = d.y + d.vy * DT, nz = d.z + d.vz * DT;
            if (d.puff) { d.x = nx; d.y = ny; d.z = nz; continue; }
            if (collide(w, d, nx, ny, nz)) it.remove();
        }
    }

    /** Moves a droplet to (nx,ny,nz) in small steps; true if it hit something (and stained it). */
    private static boolean collide(World w, Drop d, double nx, double ny, double nz) {
        double sx = d.x, sy = d.y, sz = d.z;
        double len = Math.sqrt((nx - sx) * (nx - sx) + (ny - sy) * (ny - sy) + (nz - sz) * (nz - sz));
        int steps = Math.max(1, (int) Math.ceil(len / 0.2));
        double lx = sx, ly = sy, lz = sz;
        for (int i = 1; i <= steps; i++) {
            double k = (double) i / steps;
            double x = sx + (nx - sx) * k, y = sy + (ny - sy) * k, z = sz + (nz - sz) * k;
            MPOS.setPos(x, y, z);
            if (!w.isBlockLoaded(MPOS)) return true;
            IBlockState st = w.getBlockState(MPOS);
            Material m = st.getMaterial();
            if (m.isLiquid()) {
                // into water, blood spreads in a cloud; into lava, it hisses off as smoke
                if (m == Material.WATER && (d.type.style == Style.LIQUID || d.type.style == Style.SLIME || d.type.style == Style.ECTO)) cloud(d.type, x, y, z, d.size);
                else if (m == Material.LAVA && RAND.nextInt(3) == 0) mist(BloodTypes.ember(), x, y, z, 0.4f);
                return true;
            }
            if (m != Material.AIR) {
                AxisAlignedBB bb = st.getCollisionBoundingBox(w, MPOS);
                if (bb != null && bb.maxX > bb.minX) {
                    bb = bb.offset(MPOS);
                    if (x >= bb.minX && x <= bb.maxX && y >= bb.minY && y <= bb.maxY && z >= bb.minZ && z <= bb.maxZ) {
                        if (d.stains) land(w, d, bb, lx, ly, lz);
                        return true;
                    }
                }
            }
            lx = x; ly = y; lz = z;
        }
        d.x = nx; d.y = ny; d.z = nz;
        return false;
    }

    /** A droplet that was at (lx,ly,lz) entered box bb: the face it came through gets a stain. */
    private static void land(World w, Drop d, AxisAlignedBB bb, double lx, double ly, double lz) {
        BlockPos host = MPOS.toImmutable();
        double ox = lx < bb.minX ? bb.minX - lx : lx > bb.maxX ? lx - bb.maxX : 0;
        double oy = ly < bb.minY ? bb.minY - ly : ly > bb.maxY ? ly - bb.maxY : 0;
        double oz = lz < bb.minZ ? bb.minZ - lz : lz > bb.maxZ ? lz - bb.maxZ : 0;
        EnumFacing face;
        double x = lx, y = ly, z = lz;
        if (oy >= ox && oy >= oz) { face = ly > bb.maxY ? EnumFacing.UP : EnumFacing.DOWN; y = face == EnumFacing.UP ? bb.maxY : bb.minY; }
        else if (ox >= oz) { face = lx > bb.maxX ? EnumFacing.EAST : EnumFacing.WEST; x = face == EnumFacing.EAST ? bb.maxX : bb.minX; }
        else { face = lz > bb.maxZ ? EnumFacing.SOUTH : EnumFacing.NORTH; z = face == EnumFacing.SOUTH ? bb.maxZ : bb.minZ; }
        if (ox == 0 && oy == 0 && oz == 0) { face = EnumFacing.UP; y = bb.maxY; }

        // how it hit: straight on (normal speed) vs skidding along the face (tangential, in face u/v)
        double vn, du, dv;
        switch (face.getAxis()) {
            case Y: vn = Math.abs(d.vy); du = d.vx; dv = d.vz; break;
            case Z: vn = Math.abs(d.vz); du = d.vx; dv = d.vy; break;
            default: vn = Math.abs(d.vx); du = d.vz; dv = d.vy; break;
        }
        double vt = Math.sqrt(du * du + dv * dv);
        float speed = (float) Math.sqrt(vn * vn + vt * vt);
        // faster, fatter drops splat wider
        float size = d.size * (3.2f + Math.min(7f, speed) * 0.85f) * (0.85f + RAND.nextFloat() * 0.35f);
        int fx = face == EnumFacing.EAST ? 1 : face == EnumFacing.WEST ? -1 : 0;
        int fy = face == EnumFacing.UP ? 1 : face == EnumFacing.DOWN ? -1 : 0;
        int fz = face == EnumFacing.SOUTH ? 1 : face == EnumFacing.NORTH ? -1 : 0;
        BlockPos light = new BlockPos(Math.floor(x + fx * 0.01), Math.floor(y + fy * 0.01), Math.floor(z + fz * 0.01));
        double ratio = vt / (vn + 0.15);
        Stain s;
        if (size < 0.09f) {
            s = addStain(d.type, face, bb, x, y, z, size, size, 0.05f, RAND.nextInt(3) == 0 ? T_SPATTER : T_DOT, RAND.nextInt(8), light, host);
        } else if (ratio > 0.65) {
            // landed at an angle: stretched along the way it was going, its spines thrown ahead
            float along = size * (float) Math.min(2.1, 1.0 + 0.35 * ratio), across = size * 0.8f;
            int orient;
            double cu, cv;
            boolean alongU = Math.abs(du) >= Math.abs(dv);
            if (alongU) orient = du < 0 ? 1 : 0;
            else orient = 4 | (dv < 0 ? 1 : 0);
            double ax = alongU ? Math.signum(du) : 0, av = alongU ? 0 : Math.signum(dv);
            // the splat's middle sits a little ahead of where it hit
            double shift = along * 0.2;
            double sx = x, sy = y, sz = z;
            switch (face.getAxis()) {
                case Y: sx += ax * shift; sz += av * shift; break;
                case Z: sx += ax * shift; sy += av * shift; break;
                default: sz += ax * shift; sy += av * shift; break;
            }
            s = addStain(d.type, face, bb, sx, sy, sz, alongU ? along : across, alongU ? across : along, 0.06f,
                    T_ANGLED0 + RAND.nextInt(4), orient | (RAND.nextBoolean() ? 2 : 0), light, host);
        } else {
            s = addStain(d.type, face, bb, x, y, z, size, size, 0.06f, T_SPLAT0 + RAND.nextInt(T_SPLATS), RAND.nextInt(8), light, host);
        }
        if (s == null) return;
        boolean wet = d.type.style == Style.LIQUID || d.type.style == Style.SLIME;
        if (wet && face.getAxis() != EnumFacing.Axis.Y && size > 0.14f && RAND.nextInt(100) < 55) {
            // a trickle runs down the wall from it, creeping over a few seconds
            float len = Math.min(1.2f, size * (1.4f + RAND.nextFloat() * 1.6f));
            float wid = len;
            double top = y + size * 0.15;
            Stain r = addStain(d.type, face, bb, x, top - len * 0.36, z, wid, len, 2.5f + RAND.nextFloat() * 4f, T_SMEAR,
                    RAND.nextBoolean() ? 1 : 0, light, host);
            r.run = true;
        }
        if (wet && face == EnumFacing.DOWN && size > 0.1f) {
            // blood on a ceiling gathers and drips back down for a while
            s.drips = 1 + RAND.nextInt(Math.max(1, (int) (size * 12)));
            s.nextDrip = clock + 0.8f + RAND.nextFloat() * 2f;
        }
        if (!d.quiet && size > 0.35f && RAND.nextInt(3) == 0) Sfx.splat(d.type, x, y, z, Math.min(1f, size));
    }

    // ------------------------------------------------------------------ drawing

    private static final float[] RIGHT = new float[3], UP = new float[3];
    private static int mippedAtlas = -1, mippedGloss = -1;

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRenderWorld(RenderWorldLastEvent event) {
        Guard.timed("Blood.onRenderWorld", () -> onRenderWorld0(event));
    }

    private static void onRenderWorld0(RenderWorldLastEvent event) {
        if (DROPS.isEmpty() && STAINS.isEmpty()) return;
        GlGuard.push();
        GlGuard.worldLit();
        try {
            draw(event.getPartialTicks());
        } catch (RuntimeException ex) {
            Visceral.LOG.debug("blood drawing failed", ex);
        } finally {
            GlGuard.pop();
        }
    }

    /**
     * Binds one of the gore textures, smoothed and with mipmaps (made once per texture), so the
     * detail holds up close and far stains don't shimmer.
     */
    static void bind(ResourceLocation rl) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.getTextureManager().bindTexture(rl);
        ITextureObject t = mc.getTextureManager().getTexture(rl);
        if (t == null) return;
        int id = t.getGlTextureId();
        boolean atlas = rl == ATLAS;
        if (id == (atlas ? mippedAtlas : mippedGloss)) return;
        if (atlas) mippedAtlas = id; else mippedGloss = id;
        try {
            // the game loads textures allowing only the full-size level; allow the smaller ones too
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 8);
            GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LOD, 8f);
            GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
        } catch (Throwable e) {
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        }
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
    }

    private static void draw(float pt) {
        Minecraft mc = Minecraft.getMinecraft();
        Entity cam = mc.getRenderViewEntity();
        if (cam == null) return;
        double cx = cam.lastTickPosX + (cam.posX - cam.lastTickPosX) * pt;
        double cy = cam.lastTickPosY + (cam.posY - cam.lastTickPosY) * pt;
        double cz = cam.lastTickPosZ + (cam.posZ - cam.lastTickPosZ) * pt;
        float now = clock + pt * DT;
        float yaw = (float) Math.toRadians(mc.getRenderManager().playerViewY);
        float pitch = (float) Math.toRadians(mc.getRenderManager().playerViewX);
        if (mc.gameSettings.thirdPersonView == 2) pitch = -pitch;
        RIGHT[0] = (float) Math.cos(yaw); RIGHT[1] = 0; RIGHT[2] = (float) Math.sin(yaw);
        float[] fwd = {(float) (-Math.sin(yaw) * Math.cos(pitch)), (float) -Math.sin(pitch), (float) (Math.cos(yaw) * Math.cos(pitch))};
        UP[0] = RIGHT[1] * fwd[2] - RIGHT[2] * fwd[1];
        UP[1] = RIGHT[2] * fwd[0] - RIGHT[0] * fwd[2];
        UP[2] = RIGHT[0] * fwd[1] - RIGHT[1] * fwd[0];
        double eye = cam.getEyeHeight();
        // only what's in view and near enough; the shine only up close
        FRUSTUM.setPosition(cx, cy, cz);
        double far = VisceralConfig.blood.renderDistance;
        far2 = far * far;
        gloss2 = Math.min(far2, 28 * 28);
        camX = cx; camY = cy; camZ = cz;

        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.004f);
        GlStateManager.depthMask(false);
        GlStateManager.disableCull();
        GlStateManager.enableDepth();
        GlStateManager.color(1f, 1f, 1f, 1f);
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();

        // 1. stains (pulled a little toward you so they never flicker into the block), then their wet shine
        GlStateManager.enablePolygonOffset();
        GlStateManager.doPolygonOffset(-3f, -3f);
        bind(ATLAS);
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP);
        for (Stain s : STAINS) if (s.atlas == 0) stain(buf, s, now, cx, cy, cz, false);
        tess.draw();
        mc.getTextureManager().bindTexture(PRINTS);
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP);
        for (Stain s : STAINS) if (s.atlas == 1) stain(buf, s, now, cx, cy, cz, false);
        tess.draw();
        bind(GLOSS);
        GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP);
        for (Stain s : STAINS) if (s.atlas == 0) stain(buf, s, now, cx, cy, cz, true);
        tess.draw();
        GlStateManager.doPolygonOffset(0f, 0f);
        GlStateManager.disablePolygonOffset();

        // 2. droplets and puffs in the world's light, then the shine on the droplets
        GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        bind(ATLAS);
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP);
        for (Drop d : DROPS) if (!d.glow) drop(buf, d, pt, cx, cy, cz, eye, mc.world, false);
        tess.draw();
        GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        bind(GLOSS);
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP);
        for (Drop d : DROPS) if (!d.glow && !d.puff && d.tile == T_DROP) drop(buf, d, pt, cx, cy, cz, eye, mc.world, true);
        tess.draw();
        // 3. glowing ones (sparks, embers, ecto): light added on top, bright even in the dark
        bind(ATLAS);
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP);
        for (Drop d : DROPS) if (d.glow) drop(buf, d, pt, cx, cy, cz, eye, null, false);
        tess.draw();
        GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1f);
        GlStateManager.depthMask(true);
        GlStateManager.enableCull();
    }

    private static final net.minecraft.client.renderer.culling.Frustum FRUSTUM = new net.minecraft.client.renderer.culling.Frustum();
    private static double far2, gloss2, camX, camY, camZ;

    private static boolean visible(double x0, double y0, double z0, double x1, double y1, double z1, double maxDist2) {
        double mx = (x0 + x1) * 0.5 - camX, my = (y0 + y1) * 0.5 - camY, mz = (z0 + z1) * 0.5 - camZ;
        if (mx * mx + my * my + mz * mz > maxDist2) return false;
        return FRUSTUM.isBoxInFrustum(x0 - 0.05, y0 - 0.05, z0 - 0.05, x1 + 0.05, y1 + 0.05, z1 + 0.05);
    }

    static int stainCount() { return STAINS.size(); }

    /** Every droplet and stain gone (the settings screen's "Clear all gore"). */
    static void clearAll() {
        DROPS.clear();
        STAINS.clear();
        FLOOR.clear();
        clouds = 0;
    }

    private static float tu(int tile) { return (tile % 4) * 0.25f; }
    private static float tv(int tile) { return (tile / 4) * 0.25f; }

    private static float smooth(float k) {
        k = Math.max(0f, Math.min(1f, k));
        return k * k * (3f - 2f * k);
    }

    private static void stain(BufferBuilder buf, Stain s, float now, double cx, double cy, double cz, boolean gloss) {
        float age = now - s.born;
        if (age < 0) return;
        {
            double p0 = s.plane;
            boolean vis;
            switch (s.face.getAxis()) {
                case Y: vis = visible(s.u0, p0, s.v0, s.u1, p0, s.v1, gloss ? gloss2 : far2); break;
                case Z: vis = visible(s.u0, s.v0, p0, s.u1, s.v1, p0, gloss ? gloss2 : far2); break;
                default: vis = visible(p0, s.v0, s.u0, p0, s.v1, s.u1, gloss ? gloss2 : far2); break;
            }
            if (!vis) return;
        }
        float a = s.alpha;
        float left = s.life - age;
        if (left < 6f) a *= Math.max(0f, left / 6f);   // the last seconds: fades
        // drying: the shine goes first, then it darkens to maroon-brown
        float dry = smooth(age / s.dryAt);
        float wet = 1f - smooth(age / (s.dryAt * 0.6f));
        if (gloss) {
            if (s.tile == T_SCORCH || wet <= 0.01f) return;
            a *= 0.5f * wet * (s.type != null && s.type.style == Style.SLIME ? 1.4f : 1f);
        }
        if (a <= 0.01f) return;
        float g = s.grow <= 0 ? 1f : Math.min(1f, age / s.grow);
        g = 1f - (1f - g) * (1f - g);
        double u0, u1, v0, v1, vreveal;
        if (s.run) {
            // a trickle: full size from the start, shown from the top down as it creeps
            u0 = s.u0; u1 = s.u1; v0 = s.v0; v1 = s.v1;
            vreveal = v1 - (v1 - v0) * (0.16 + 0.84 * g);
        } else {
            double mu = (s.u0 + s.u1) * 0.5, mv = (s.v0 + s.v1) * 0.5, k = 0.3 + 0.7 * g;
            u0 = mu + (s.u0 - mu) * k; u1 = mu + (s.u1 - mu) * k;
            v0 = mv + (s.v0 - mv) * k; v1 = mv + (s.v1 - mv) * k;
            vreveal = -1e9;
        }
        // cut to the block face (and to how far a trickle has crept), with the picture cut the same way
        double cu0 = Math.max(u0, s.umin), cu1 = Math.min(u1, s.umax);
        double cv0 = Math.max(Math.max(v0, s.vmin), vreveal), cv1 = Math.min(v1, s.vmax);
        if (cu0 >= cu1 || cv0 >= cv1) return;
        float fu0 = (float) ((cu0 - u0) / (u1 - u0)), fu1 = (float) ((cu1 - u0) / (u1 - u0));
        float fv0 = (float) ((cv0 - v0) / (v1 - v0)), fv1 = (float) ((cv1 - v0) / (v1 - v0));
        float shade;
        switch (s.face) {
            case UP: shade = 1f; break;
            case DOWN: shade = 0.5f; break;
            case NORTH: case SOUTH: shade = 0.8f; break;
            default: shade = 0.6f; break;
        }
        float r, gr, b;
        if (gloss) {
            r = gr = b = shade;
        } else {
            int c = s.color;
            float dr = s.tile == T_SCORCH ? 1f : 1f - 0.55f * dry, dg = s.tile == T_SCORCH ? 1f : 1f - 0.65f * dry, db = s.tile == T_SCORCH ? 1f : 1f - 0.7f * dry;
            if (s.type != null && s.type.style == Style.SLIME) { dr = 1f - 0.25f * dry; dg = 1f - 0.3f * dry; db = 1f - 0.3f * dry; }
            r = ((c >> 16) & 255) / 255f * shade * dr;
            gr = ((c >> 8) & 255) / 255f * shade * dg;
            b = (c & 255) / 255f * shade * db;
            a = Math.min(1f, a * (1f + 0.04f * dry));
        }
        int lj = s.light >> 16 & 65535, lk = s.light & 65535;
        double p = s.plane + (s.face.getAxisDirection() == EnumFacing.AxisDirection.POSITIVE ? s.offset : -s.offset);
        double[][] q = {{cu0, cv0}, {cu1, cv0}, {cu1, cv1}, {cu0, cv1}};
        float[][] uv = {{fu0, fv0}, {fu1, fv0}, {fu1, fv1}, {fu0, fv1}};
        float bu = tu(s.tile), bv = tv(s.tile);
        for (int i = 0; i < 4; i++) {
            float fu = uv[i][0], fv = uv[i][1];
            if ((s.orient & 4) != 0) { float t = fu; fu = fv; fv = t; }   // picture turned to lie along v
            if ((s.orient & 1) != 0) fu = 1f - fu;
            if ((s.orient & 2) != 0) fv = 1f - fv;
            float tx = bu + 0.002f + fu * 0.246f, ty = bv + 0.002f + fv * 0.246f;
            double x, y, z;
            switch (s.face.getAxis()) {
                case Y: x = q[i][0]; y = p; z = q[i][1]; break;
                case Z: x = q[i][0]; y = q[i][1]; z = p; break;
                default: x = p; y = q[i][1]; z = q[i][0]; break;
            }
            buf.pos(x - cx, y - cy, z - cz).tex(tx, ty).color(r, gr, b, a).lightmap(lj, lk).endVertex();
        }
    }

    private static void drop(BufferBuilder buf, Drop d, float pt, double cx, double cy, double cz, double eye, World w, boolean gloss) {
        float k = d.age / d.life;
        double x = d.px + (d.x - d.px) * pt, y = d.py + (d.y - d.py) * pt, z = d.pz + (d.z - d.pz) * pt;
        double rr = Math.max(0.1, d.size * (d.puff ? 1 + d.grow : 1) * 0.6);
        if (!visible(x - rr, y - rr, z - rr, x + rr, y + rr, z + rr, gloss ? gloss2 : far2)) return;
        float a = d.alpha;
        if (d.puff) a *= (1f - k) * (1f - k);
        else if (k > 0.75f) a *= (1f - k) / 0.25f;
        if (gloss) a *= 0.55f;
        if (a < 0.01f) return;
        int light;
        if (w == null) light = 0xF000F0;
        else { MPOS.setPos(x, y, z); light = w.getCombinedLight(MPOS, 0); }
        int lj = light >> 16 & 65535, lk = light & 65535;
        int c = gloss ? 0xFFFFFF : d.color;
        float r = ((c >> 16) & 255) / 255f, g = ((c >> 8) & 255) / 255f, b = (c & 255) / 255f;
        float bu = tu(d.tile), bv = tv(d.tile);
        float u0 = bu + 0.002f, u1 = bu + 0.248f, v0 = bv + 0.002f, v1 = bv + 0.248f;
        float fx = (float) (x - cx), fy = (float) (y - cy), fz = (float) (z - cz);
        if (d.streak) {
            // stretched along its motion, so a fast droplet is a streak and a slow one a bead
            float sx = (float) (x - (d.px + (d.x - d.px) * (pt - 1.2f))), sy = (float) (y - (d.py + (d.y - d.py) * (pt - 1.2f))),
                    sz = (float) (z - (d.pz + (d.z - d.pz) * (pt - 1.2f)));
            float sl = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
            if (sl > d.size * 0.6f) {
                float tx = fx - sx, ty = fy - sy, tz = fz - sz;
                float vx = fx, vy = (float) (fy - eye), vz = fz;
                float nx = sy * vz - sz * vy, ny = sz * vx - sx * vz, nz = sx * vy - sy * vx;
                float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (nl > 1e-6f) {
                    float hw = d.size * 0.5f;
                    nx = nx / nl * hw; ny = ny / nl * hw; nz = nz / nl * hw;
                    float ex = sx / sl * hw, ey = sy / sl * hw, ez = sz / sl * hw;
                    buf.pos(tx - nx, ty - ny, tz - nz).tex(u0, v1).color(r, g, b, a * 0.6f).lightmap(lj, lk).endVertex();
                    buf.pos(tx + nx, ty + ny, tz + nz).tex(u1, v1).color(r, g, b, a * 0.6f).lightmap(lj, lk).endVertex();
                    buf.pos(fx + ex + nx, fy + ey + ny, fz + ez + nz).tex(u1, v0).color(r, g, b, a).lightmap(lj, lk).endVertex();
                    buf.pos(fx + ex - nx, fy + ey - ny, fz + ez - nz).tex(u0, v0).color(r, g, b, a).lightmap(lj, lk).endVertex();
                    return;
                }
            }
        }
        float s = d.size * 0.5f * (d.puff ? 1f + d.grow * (1f - (1f - k) * (1f - k)) : 1f);
        if (d.flat) {
            buf.pos(fx - s, fy, fz - s).tex(u0, v0).color(r, g, b, a).lightmap(lj, lk).endVertex();
            buf.pos(fx - s, fy, fz + s).tex(u0, v1).color(r, g, b, a).lightmap(lj, lk).endVertex();
            buf.pos(fx + s, fy, fz + s).tex(u1, v1).color(r, g, b, a).lightmap(lj, lk).endVertex();
            buf.pos(fx + s, fy, fz - s).tex(u1, v0).color(r, g, b, a).lightmap(lj, lk).endVertex();
            return;
        }
        float rx = RIGHT[0] * s, ry = RIGHT[1] * s, rz = RIGHT[2] * s, ux = UP[0] * s, uy = UP[1] * s, uz = UP[2] * s;
        buf.pos(fx - rx - ux, fy - ry - uy, fz - rz - uz).tex(u0, v1).color(r, g, b, a).lightmap(lj, lk).endVertex();
        buf.pos(fx + rx - ux, fy + ry - uy, fz + rz - uz).tex(u1, v1).color(r, g, b, a).lightmap(lj, lk).endVertex();
        buf.pos(fx + rx + ux, fy + ry + uy, fz + rz + uz).tex(u1, v0).color(r, g, b, a).lightmap(lj, lk).endVertex();
        buf.pos(fx - rx + ux, fy - ry + uy, fz - rz + uz).tex(u0, v0).color(r, g, b, a).lightmap(lj, lk).endVertex();
    }

    /** A colour a little lighter or darker, so a spray isn't one flat colour. */
    static int vary(int c, float amt) {
        float f = 1f + (RAND.nextFloat() * 2f - 1f) * amt;
        int r = Math.min(255, Math.max(0, (int) (((c >> 16) & 255) * f)));
        int g = Math.min(255, Math.max(0, (int) (((c >> 8) & 255) * f)));
        int b = Math.min(255, Math.max(0, (int) ((c & 255) * f)));
        return (r << 16) | (g << 8) | b;
    }

    static int dark(int c, float f) {
        return ((int) (((c >> 16) & 255) * f) << 16) | ((int) (((c >> 8) & 255) * f) << 8) | (int) ((c & 255) * f);
    }

    static int dropCount() { return DROPS.size(); }
}
