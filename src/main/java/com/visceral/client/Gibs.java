package com.visceral.client;

import com.visceral.Visceral;
import com.visceral.VisceralConfig;
import com.visceral.client.BloodTypes.Style;
import com.visceral.client.BloodTypes.Type;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderLivingBase;
import net.minecraft.client.renderer.entity.layers.LayerHeldItem;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gibs: a creature's real body parts flying off, tumbling, bouncing and lying where they land,
 * bleeding, until they sink away. Also chunks (meat, bone, metal, slime), the hidden corpse of a
 * creature that burst, and limbs torn off creatures that are still alive: the limb is hidden on the
 * creature (and its armour and held item with it) while the real one flies off.
 */
@Mod.EventBusSubscriber(modid = Visceral.MODID, value = Side.CLIENT)
public final class Gibs {
    private Gibs() {}

    private static final float DT = 0.05f;

    static final class Gib {
        ModelParts.Group group;      // null: a chunk
        boolean whole;               // a torn-off limb: drawn with everything below it
        boolean charred;             // burnt: blackened, no blood, smoking for a while
        boolean flies;               // flies have found it
        float kickedAt = -10f;
        ResourceLocation tex;
        Type type;
        double x, y, z, px, py, pz;
        float vx, vy, vz;
        final float[] lin = new float[9], plin = new float[9];
        float wx, wy, wz;            // spin, radians/second around world axes
        float r;                     // collision half size
        float age, life;
        boolean ground, splatted, pooled;
        float chunk;                 // chunk size (blocks)
        int color;
    }

    private static final List<Gib> GIBS = new ArrayList<>();
    /** Creatures that burst: their corpse isn't drawn. */
    private static final Set<Integer> BURST = new HashSet<>();
    /** Parts torn off living creatures. */
    private static final Map<Integer, Lost> LOST = new HashMap<>();
    private static World lastWorld;

    static final class Lost {
        final List<ModelRenderer> parts = new ArrayList<>();
        final List<Integer> bipedSlots = new ArrayList<>();   // which ModelBiped piece (for armour layers)
        final List<Integer> rootIndex = new ArrayList<>();    // which top-level piece (for layers with the same model shape)
        boolean arm;                                          // an arm: its held item goes too
        ModelBase model;
        /** Where each lost part joined the body, around the creature (as Group.sx..), and the cut's size: {x, y, z, w, d}. */
        final List<float[]> stumps = new ArrayList<>();
    }

    /**
     * Where a point given around a creature (its turn taken out, as Group.sx/sy/sz) is in the world
     * right now: turned with the creature, and tipped over with it as a corpse falls.
     * Returns {x, y, z, turn in degrees}.
     */
    static double[] joint(EntityLivingBase e, double sx, double sy, double sz, float pt) {
        float yaw = e.prevRenderYawOffset + net.minecraft.util.math.MathHelper.wrapDegrees(e.renderYawOffset - e.prevRenderYawOffset) * pt;
        double x = sx, y = sy, z = sz;
        if (e.deathTime > 0) {
            // RenderLivingBase tips a dying creature over onto its side
            float f = Math.min(1f, (float) Math.sqrt(Math.max(0f, (e.deathTime + pt - 1f) / 20f * 1.6f)));
            double a = Math.toRadians(f * 90f), c = Math.cos(a), sn = Math.sin(a);
            double nx = x * c - y * sn, ny = x * sn + y * c;
            x = nx; y = ny;
        }
        double t = Math.toRadians(180f - yaw), c = Math.cos(t), sn = Math.sin(t);
        double wx = x * c + z * sn, wz = -x * sn + z * c;
        double ex = e.lastTickPosX + (e.posX - e.lastTickPosX) * pt, ey = e.lastTickPosY + (e.posY - e.lastTickPosY) * pt,
                ez = e.lastTickPosZ + (e.posZ - e.lastTickPosZ) * pt;
        return new double[]{ex + wx, ey + y, ez + wz, 180f - yaw};
    }

    static boolean burst(int id) {
        return BURST.contains(id);
    }

    static Lost lost(int id) {
        return LOST.get(id);
    }

    static void forget(int id) {
        BURST.remove(id);
        LOST.remove(id);
    }

    static int count() {
        return GIBS.size();
    }

    // ------------------------------------------------------------------ spawning

    /**
     * A creature bursts: every part it still has flies off, thrown away from (dx,dz) with `force`.
     * Returns false if its model couldn't be read (the caller throws chunks instead).
     */
    static boolean burst(EntityLivingBase e, ModelParts.Capture cap, Type t, double dx, double dz, float force) {
        return burst(e, cap, t, dx, dz, force, false, 0f);
    }

    /** As burst(), optionally burnt, and with `lift` extra upward throw (an explosion under it). */
    static boolean burst(EntityLivingBase e, ModelParts.Capture cap, Type t, double dx, double dz, float force, boolean charred, float lift) {
        BURST.add(e.getEntityId());
        if (cap == null) return false;
        Lost lost = LOST.get(e.getEntityId());
        double cx = e.posX, cy = e.posY + e.height * 0.5, cz = e.posZ;
        for (ModelParts.Group g : cap.groups) {
            if (isLost(e.getEntityId(), g)) continue;
            Gib gb = fromGroup(g, cap, t);
            // out from the creature's middle, plus the blow, plus up; heads pop higher
            double ox = g.wx - cx, oy = g.wy - cy, oz = g.wz - cz;
            double ol = Math.sqrt(ox * ox + oy * oy + oz * oz);
            if (ol < 1e-3) { ox = 0; oy = 1; oz = 0; ol = 1; }
            float out = (1.5f + Blood.RAND.nextFloat() * 2.5f) * force;
            float blow = (2f + Blood.RAND.nextFloat() * 2f) * force;
            gb.vx = (float) (ox / ol * out + dx * blow);
            gb.vy = (float) (oy / ol * out) + (3f + Blood.RAND.nextFloat() * 3f) * force * (g.head ? 1.5f : 1f);
            gb.vz = (float) (oz / ol * out + dz * blow);
            gb.vy += lift * (0.6f + Blood.RAND.nextFloat() * 0.8f);
            gb.charred = charred;
            spin(gb, (6f + Blood.RAND.nextFloat() * 10f) * Math.min(2f, force));
            add(gb);
        }
        return true;
    }

    /** One part torn off a living creature: hidden on it from now on, and thrown. */
    static void tearOff(EntityLivingBase e, ModelParts.Capture cap, ModelParts.Group g, Type t, double dx, double dz, float force) {
        Lost lost = LOST.get(e.getEntityId());
        if (lost == null || lost.model != cap.model) {
            lost = new Lost();
            lost.model = cap.model;
            LOST.put(e.getEntityId(), lost);
        }
        List<ModelRenderer> roots = ModelParts.roots(cap.model);
        for (ModelRenderer mr : g.roots) {
            lost.parts.add(mr);
            lost.rootIndex.add(roots.indexOf(mr));
            int slot = bipedSlot(cap.model, mr);
            lost.bipedSlots.add(slot);
            if (slot == 3 || slot == 4) lost.arm = true;
        }
        float sxl = ModelParts.len(g.lin[0], g.lin[3], g.lin[6]), szl = ModelParts.len(g.lin[2], g.lin[5], g.lin[8]);
        lost.stumps.add(new float[]{(float) g.sx, (float) g.sy, (float) g.sz, 2f * g.hx * sxl * 0.85f, 2f * g.hz * szl * 0.85f,
                g.limb || g.head ? 1f : 0f});
        Gib gb = fromGroup(g, cap, t);
        gb.whole = true;
        double ox = g.wx - e.posX, oz = g.wz - e.posZ;
        double ol = Math.sqrt(ox * ox + oz * oz);
        if (ol < 1e-3) { ox = dx; oz = dz; ol = 1; }
        gb.vx = (float) ((ox / ol * 1.5 + dx * 3) * force);
        gb.vy = (3f + Blood.RAND.nextFloat() * 2f) * force;
        gb.vz = (float) ((oz / ol * 1.5 + dz * 3) * force);
        spin(gb, 9f * force);
        add(gb);
    }

    /** This part, or one it hangs from, has been torn off already. */
    static boolean isLost(int id, ModelParts.Group g) {
        Lost l = LOST.get(id);
        if (l == null) return false;
        for (ModelRenderer mr : g.roots) if (l.parts.contains(mr)) return true;
        for (ModelRenderer mr : g.path) if (l.parts.contains(mr)) return true;
        return false;
    }

    /** n chunks of meat (bone, metal, slime...) from a box around (x,y,z). */
    static void chunks(Type t, double x, double y, double z, float w, float h, int n, double dx, double dz, float force) {
        chunks(t, x, y, z, w, h, n, dx, dz, force, false);
    }

    static void chunks(Type t, double x, double y, double z, float w, float h, int n, double dx, double dz, float force, boolean charred) {
        chunks(t, x, y, z, w, h, n, dx, dz, force, charred, -1);
    }

    /** As chunks(), coloured `color` (e.g. the creature's own colours) instead of the blood type's chunk colour, if not -1. */
    static void chunks(Type t, double x, double y, double z, float w, float h, int n, double dx, double dz, float force, boolean charred, int color) {
        if (t.none()) return;
        for (int k = 0; k < n; k++) {
            Gib gb = new Gib();
            gb.type = t;
            gb.x = gb.px = x + (Blood.RAND.nextDouble() - 0.5) * w;
            gb.y = gb.py = y + Blood.RAND.nextDouble() * h;
            gb.z = gb.pz = z + (Blood.RAND.nextDouble() - 0.5) * w;
            gb.chunk = (0.06f + Blood.RAND.nextFloat() * 0.1f) * (t.style == Style.SLIME ? 1.6f : 1f);
            gb.r = gb.chunk * 0.5f;
            int base = color >= 0 ? (Blood.RAND.nextInt(3) == 0 ? t.chunkColor : color) : t.chunkColor;
            gb.color = charred ? Blood.vary(0x2A1E18, 0.25f) : Blood.vary(base, 0.18f);
            gb.charred = charred;
            gb.lin[0] = gb.lin[4] = gb.lin[8] = 1f;
            randomTurn(gb);
            float sp = (2f + Blood.RAND.nextFloat() * 4f) * force;
            double a = Blood.RAND.nextDouble() * Math.PI * 2;
            gb.vx = (float) (Math.cos(a) * sp * 0.6 + dx * sp * 0.6);
            gb.vy = (3f + Blood.RAND.nextFloat() * 4f) * force;
            gb.vz = (float) (Math.sin(a) * sp * 0.6 + dz * sp * 0.6);
            spin(gb, 14f);
            gb.life = VisceralConfig.gibs.gibSeconds * (0.5f + Blood.RAND.nextFloat() * 0.3f);
            add(gb);
        }
    }

    private static Gib fromGroup(ModelParts.Group g, ModelParts.Capture cap, Type t) {
        Gib gb = new Gib();
        gb.group = g;
        gb.tex = cap.tex;
        gb.type = t;
        gb.x = gb.px = g.wx;
        gb.y = gb.py = g.wy;
        gb.z = gb.pz = g.wz;
        System.arraycopy(g.lin, 0, gb.lin, 0, 9);
        System.arraycopy(g.lin, 0, gb.plin, 0, 9);
        gb.r = g.worldRadius();
        gb.life = VisceralConfig.gibs.gibSeconds * (0.85f + Blood.RAND.nextFloat() * 0.3f);
        return gb;
    }

    private static void add(Gib g) {
        GIBS.add(g);
        while (GIBS.size() > VisceralConfig.gibs.maxGibs) {
            // the oldest starts sinking now instead of vanishing
            Gib old = null;
            for (Gib o : GIBS) if (o.life - o.age > 1.5f && (old == null || o.age > old.age)) old = o;
            if (old == null) { GIBS.remove(0); break; }
            old.life = old.age + 1.5f;
            break;
        }
    }

    private static void spin(Gib g, float amt) {
        g.wx = (Blood.RAND.nextFloat() - 0.5f) * 2f * amt;
        g.wy = (Blood.RAND.nextFloat() - 0.5f) * 2f * amt;
        g.wz = (Blood.RAND.nextFloat() - 0.5f) * 2f * amt;
    }

    private static void randomTurn(Gib g) {
        float[] r = axisAngle(Blood.RAND.nextFloat() - 0.5f, Blood.RAND.nextFloat() - 0.5f, Blood.RAND.nextFloat() - 0.5f,
                Blood.RAND.nextFloat() * 6.28f);
        float[] o = mul3(r, g.lin);
        System.arraycopy(o, 0, g.lin, 0, 9);
        System.arraycopy(o, 0, g.plin, 0, 9);
    }

    /** Which ModelBiped piece mr is (0 head, 1 hat, 2 body, 3 right arm, 4 left arm, 5 right leg, 6 left leg), or -1. */
    private static int bipedSlot(ModelBase model, ModelRenderer mr) {
        if (!(model instanceof ModelBiped)) return -1;
        ModelBiped b = (ModelBiped) model;
        if (mr == b.bipedHead) return 0;
        if (mr == b.bipedHeadwear) return 1;
        if (mr == b.bipedBody) return 2;
        if (mr == b.bipedRightArm) return 3;
        if (mr == b.bipedLeftArm) return 4;
        if (mr == b.bipedRightLeg) return 5;
        if (mr == b.bipedLeftLeg) return 6;
        return -1;
    }

    private static ModelRenderer bipedPart(ModelBiped b, int slot) {
        switch (slot) {
            case 0: return b.bipedHead;
            case 1: return b.bipedHeadwear;
            case 2: return b.bipedBody;
            case 3: return b.bipedRightArm;
            case 4: return b.bipedLeftArm;
            case 5: return b.bipedRightLeg;
            case 6: return b.bipedLeftLeg;
            default: return null;
        }
    }

    // ------------------------------------------------------------------ simulation

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent event) {
        Guard.run("Gibs.onTick", () -> onTick0(event));
    }

    private static void onTick0(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world != lastWorld) {
            lastWorld = mc.world;
            GIBS.clear();
            BURST.clear();
            LOST.clear();
        }
        if (mc.world == null || mc.isGamePaused()) return;
        World w = mc.world;
        Iterator<Gib> it = GIBS.iterator();
        while (it.hasNext()) {
            Gib g = it.next();
            g.age += DT;
            if (g.age >= g.life) { it.remove(); continue; }
            step(w, g);
        }
    }

    private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();

    private static void step(World w, Gib g) {
        g.px = g.x; g.py = g.y; g.pz = g.z;
        System.arraycopy(g.lin, 0, g.plin, 0, 9);
        POS.setPos(g.x, g.y, g.z);
        net.minecraft.block.material.Material mat = w.getBlockState(POS).getMaterial();
        if (mat == net.minecraft.block.material.Material.WATER) {
            // flesh floats up and bobs at the surface, bleeding a cloud into the water
            g.vy += (g.chunk > 0 ? 26f : 28f) * DT;
            g.vx *= 0.85f; g.vy *= 0.85f; g.vz *= 0.85f;
            g.wx *= 0.9f; g.wy *= 0.9f; g.wz *= 0.9f;
            g.ground = false;
            if (!g.charred && g.type.stains() && Blood.RAND.nextInt(12) == 0) Blood.cloud(g.type, g.x, g.y, g.z, 0.05f);
        } else if (mat == net.minecraft.block.material.Material.LAVA) {
            // into lava: it burns black and sinks, smoking
            if (!g.charred) { g.charred = true; Sfx.burn(g.x, g.y, g.z); }
            g.life = Math.min(g.life, g.age + 3f);
            g.vx *= 0.6f; g.vz *= 0.6f; g.vy = -0.3f;
            if (Blood.RAND.nextInt(4) == 0) Blood.mist(BloodTypes.ember(), g.x, g.y + 0.2, g.z, 0.5f);
        }
        boolean sinking = g.life - g.age < 1.5f;
        if (sinking) {
            // sinks into the ground, slowly
            g.y -= g.r * 1.4f * DT / 1.5f;
            return;
        }
        g.vy -= 24f * DT;
        float drag = g.ground ? 0f : 0.985f;
        if (!g.ground) { g.vx *= drag; g.vz *= drag; }
        double dx = g.vx * DT, dy = g.vy * DT, dz = g.vz * DT;
        double r = g.r;
        AxisAlignedBB bb = new AxisAlignedBB(g.x - r, g.y - r, g.z - r, g.x + r, g.y + r, g.z + r);
        List<AxisAlignedBB> boxes = w.getCollisionBoxes(null, bb.expand(dx, dy, dz));
        double ody = dy, odx = dx, odz = dz;
        for (AxisAlignedBB b : boxes) dy = b.calculateYOffset(bb, dy);
        bb = bb.offset(0, dy, 0);
        for (AxisAlignedBB b : boxes) dx = b.calculateXOffset(bb, dx);
        bb = bb.offset(dx, 0, 0);
        for (AxisAlignedBB b : boxes) dz = b.calculateZOffset(bb, dz);
        bb = bb.offset(0, 0, dz);
        g.x = (bb.minX + bb.maxX) * 0.5;
        g.y = (bb.minY + bb.maxY) * 0.5;
        g.z = (bb.minZ + bb.maxZ) * 0.5;
        float impact = 0f;
        boolean wasGround = g.ground;
        g.ground = false;
        if (dy != ody) {
            if (ody < 0) {
                impact = -g.vy;
                g.ground = true;
                g.vy = impact > 3f ? impact * 0.28f : 0f;   // a little bounce, then it stays down
                g.vx *= 0.55f; g.vz *= 0.55f;
                g.wx *= 0.5f; g.wy *= 0.6f; g.wz *= 0.5f;
                if (impact > 3f) {
                    // the bounce knocks it into a new tumble
                    g.wx += (Blood.RAND.nextFloat() - 0.5f) * impact * 1.2f;
                    g.wz += (Blood.RAND.nextFloat() - 0.5f) * impact * 1.2f;
                }
            } else {
                g.vy = -g.vy * 0.2f;
            }
        }
        if (dx != odx) { impact = Math.max(impact, Math.abs(g.vx)); g.vx = -g.vx * 0.3f; }
        if (dz != odz) { impact = Math.max(impact, Math.abs(g.vz)); g.vz = -g.vz * 0.3f; }
        if (g.ground && Math.abs(g.vx) < 0.15f && Math.abs(g.vz) < 0.15f) { g.vx = 0; g.vz = 0; }
        if (g.ground && wasGround) {
            // lying down: the tumble dies out, and anything long topples over instead of standing on end
            g.wx *= 0.6f; g.wy *= 0.6f; g.wz *= 0.6f;
            if (g.group != null) topple(g);
        }
        float wl = (float) Math.sqrt(g.wx * g.wx + g.wy * g.wy + g.wz * g.wz);
        if (wl > 1e-3f) {
            float[] rot = axisAngle(g.wx, g.wy, g.wz, wl * DT);
            float[] o = mul3(rot, g.lin);
            System.arraycopy(o, 0, g.lin, 0, 9);
        }

        Type t = g.type;
        boolean wet = t.stains() && !g.charred;
        if (g.charred && g.age < 4f && Blood.RAND.nextInt(10) == 0) Blood.mist(BloodTypes.ember(), g.x, g.y, g.z, 0.4f);
        // walking into gore knocks it about
        if (g.ground && VisceralConfig.gibs.kickable && (int) (g.age * 20) % 2 == 0) kick(w, g);
        // old meat draws flies
        if (wet && !g.flies && g.group != null && g.age > 9f && g.life - g.age > 6f && VisceralConfig.extras.flies) {
            g.flies = true;
            if (Blood.RAND.nextInt(100) < 40) Flies.swarm(g.x, g.y + 0.1, g.z, g.life - g.age);
        }
        // hitting something hard leaves a splat (and a sound)
        if (impact > 3.5f) {
            if (wet && !g.splatted) {
                Blood.groundStain(t, g.x, g.y, g.z, Math.min(1.4f, g.r * 5f + 0.2f), 0.08f, Blood.T_SPLAT0 + Blood.RAND.nextInt(Blood.T_SPLATS));
                Blood.spray(t, g.x, g.y - g.r * 0.5, g.z, g.vx, 1, g.vz, (int) (4 * Blood.amount()), 2.5f, 0.7f);
            }
            g.splatted = true;
            if (g.chunk == 0 || Blood.RAND.nextInt(4) == 0) Sfx.land(t, g.x, g.y, g.z, Math.min(1f, impact / 10f) * (g.chunk > 0 ? 0.5f : 1f));
        }
        // flying parts trail blood; once down, a pool spreads under the bigger ones
        if (wet && g.chunk == 0 && !g.ground && g.age < 2.5f && Blood.RAND.nextInt(3) == 0) Blood.drip(t, g.x, g.y, g.z);
        if (wet && g.chunk == 0 && g.ground && !g.pooled && g.age > 0.3f && Math.abs(g.vx) + Math.abs(g.vz) < 0.3f) {
            g.pooled = true;
            if (VisceralConfig.extras.bloodPools) Blood.groundStain(t, g.x, g.y, g.z, Math.min(1.6f, g.r * 4f + 0.25f), 3f, Blood.T_POOL);
        }
        if (t.style == Style.EMBERS && g.age < 3f && Blood.RAND.nextInt(5) == 0) Blood.mist(t, g.x, g.y, g.z, 0.5f);
    }

    /** A creature (you included) walking through a gib knocks it along. */
    private static void kick(World w, Gib g) {
        double r = Math.max(0.25, g.r + 0.15);
        AxisAlignedBB box = new AxisAlignedBB(g.x - r, g.y - 0.3, g.z - r, g.x + r, g.y + 0.8, g.z + r);
        for (EntityLivingBase e : w.getEntitiesWithinAABB(EntityLivingBase.class, box)) {
            double mx = e.posX - e.lastTickPosX, mz = e.posZ - e.lastTickPosZ;
            double sp = Math.sqrt(mx * mx + mz * mz);
            if (sp < 0.03 || e.isDead || e.deathTime > 0) continue;
            float k = (float) Math.min(1.2, sp * 6);
            double ox = g.x - e.posX, oz = g.z - e.posZ, ol = Math.max(1e-3, Math.sqrt(ox * ox + oz * oz));
            g.vx = (float) ((mx / sp * 0.7 + ox / ol * 0.3) * 4f * k);
            g.vz = (float) ((mz / sp * 0.7 + oz / ol * 0.3) * 4f * k);
            g.vy = 1.6f + 1.2f * k;
            g.ground = false;
            spin(g, 8f * k);
            if (Blood.clock - g.kickedAt > 0.5f) {
                g.kickedAt = Blood.clock;
                if (!g.charred && g.type.stains()) {
                    Sfx.splat(g.type, g.x, g.y, g.z, 0.6f);
                    Blood.spray(g.type, g.x, g.y, g.z, mx, 0.4, mz, 3, 2f, 0.6f);
                }
            }
            return;
        }
    }

    /** A long part resting on its end tips over until it lies along the ground. */
    private static void topple(Gib g) {
        float[] e = {ModelParts.len(g.lin[0], g.lin[3], g.lin[6]) * g.group.hx,
                ModelParts.len(g.lin[1], g.lin[4], g.lin[7]) * g.group.hy,
                ModelParts.len(g.lin[2], g.lin[5], g.lin[8]) * g.group.hz};
        int j = e[0] >= e[1] && e[0] >= e[2] ? 0 : e[1] >= e[2] ? 1 : 2;
        float other = Math.max(e[(j + 1) % 3], e[(j + 2) % 3]);
        if (e[j] < other * 1.4f) return;   // not long enough to care which way up it lies
        float ax = g.lin[j], ay = g.lin[3 + j], az = g.lin[6 + j];
        float al = (float) Math.sqrt(ax * ax + ay * ay + az * az);
        ax /= al; ay /= al; az /= al;
        if (Math.abs(ay) < 0.2f) return;
        // turn about the horizontal axis across it, the way that brings its long axis down
        float nx = az, nz = -ax;   // (a x up)
        float nl = (float) Math.sqrt(nx * nx + nz * nz);
        if (nl < 1e-4f) { nx = 1f; nz = 0f; nl = 1f; }
        float step = Math.min(0.22f, Math.abs(ay) * 0.6f + 0.05f);
        float[] r = axisAngle(nx / nl, 0f, nz / nl, ay > 0 ? step : -step);
        float ty = r[3] * ax + r[4] * ay + r[5] * az;
        if (Math.abs(ty) > Math.abs(ay)) r = axisAngle(nx / nl, 0f, nz / nl, ay > 0 ? -step : step);
        float[] o = mul3(r, g.lin);
        System.arraycopy(o, 0, g.lin, 0, 9);
    }

    // ------------------------------------------------------------------ hiding the corpse and torn-off parts

    /** A burst creature's body isn't drawn any more (its parts are flying around instead). */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderCorpse(RenderLivingEvent.Pre<EntityLivingBase> event) {
        Guard.run("Gibs.onRenderCorpse", () -> onRenderCorpse0(event));
    }

    private static void onRenderCorpse0(RenderLivingEvent.Pre<EntityLivingBase> event) {
        if (BURST.contains(event.getEntity().getEntityId())) event.setCanceled(true);
    }

    private static final List<ModelRenderer> HIDDEN = new ArrayList<>();
    private static final List<Object[]> REMOVED = new ArrayList<>();   // {list, index, layer}
    private static Field layersField;
    private static boolean layersLooked;
    private static final Map<Class<?>, List<Field>> MODEL_FIELDS = new HashMap<>();

    /** Lost parts are hidden while the creature is drawn (and put back right after, for the others like it). */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderPre(RenderLivingEvent.Pre<EntityLivingBase> event) {
        Guard.run("Gibs.onRenderPre", () -> onRenderPre0(event));
    }

    private static void onRenderPre0(RenderLivingEvent.Pre<EntityLivingBase> event) {
        if (LOST.isEmpty()) return;
        Lost l = LOST.get(event.getEntity().getEntityId());
        if (l == null) return;
        RenderLivingBase<?> r = event.getRenderer();
        if (r.getMainModel() != l.model) return;
        for (ModelRenderer mr : l.parts) hide(mr);
        try {
            List<?> layers = layers(r);
            if (layers == null) return;
            List<ModelRenderer> mainRoots = null;
            for (int i = layers.size() - 1; i >= 0; i--) {
                Object layer = layers.get(i);
                if (l.arm && layer instanceof LayerHeldItem) {
                    // the hand holding it is gone; so is what it held
                    REMOVED.add(new Object[]{layers, i, layer});
                    layers.remove(i);
                    continue;
                }
                for (ModelBase m : layerModels(layer)) {
                    if (m == l.model) continue;
                    // armour (and other layers shaped like a person): the same piece
                    if (m instanceof ModelBiped) {
                        for (int s : l.bipedSlots) if (s >= 0) hide(bipedPart((ModelBiped) m, s));
                        continue;
                    }
                    // layers with the creature's own shape (a sheep's wool): the piece in the same place
                    if (mainRoots == null) mainRoots = ModelParts.roots(l.model);
                    List<ModelRenderer> lr = ModelParts.roots(m);
                    if (lr.size() == mainRoots.size()) for (int idx : l.rootIndex) if (idx >= 0 && idx < lr.size()) hide(lr.get(idx));
                }
            }
        } catch (RuntimeException ex) {
            // a renderer laid out differently: only the main model's parts are hidden
        }
    }

    private static void hide(ModelRenderer mr) {
        if (mr != null && !mr.isHidden) {
            mr.isHidden = true;
            HIDDEN.add(mr);
        }
    }

    @SuppressWarnings("unchecked")
    private static void restore() {
        for (ModelRenderer mr : HIDDEN) mr.isHidden = false;
        HIDDEN.clear();
        for (int i = REMOVED.size() - 1; i >= 0; i--) {
            Object[] o = REMOVED.get(i);
            List<Object> list = (List<Object>) o[0];
            int idx = (Integer) o[1];
            if (!list.contains(o[2])) list.add(Math.min(idx, list.size()), o[2]);
        }
        REMOVED.clear();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderPost(RenderLivingEvent.Post<EntityLivingBase> event) {
        Guard.run("Gibs.onRenderPost", () -> onRenderPost0(event));
    }

    private static void onRenderPost0(RenderLivingEvent.Post<EntityLivingBase> event) {
        if (!HIDDEN.isEmpty() || !REMOVED.isEmpty()) restore();
    }

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        Guard.run("Gibs.onRenderTick", () -> onRenderTick0(event));
    }

    private static void onRenderTick0(TickEvent.RenderTickEvent event) {
        if (!HIDDEN.isEmpty() || !REMOVED.isEmpty()) restore();   // in case another mod skipped a Post
    }

    private static List<?> layers(RenderLivingBase<?> r) {
        if (!layersLooked) {
            layersLooked = true;
            try {
                layersField = ObfuscationReflectionHelper.findField(RenderLivingBase.class, "field_177097_h");
            } catch (Throwable t) {
                for (Field f : RenderLivingBase.class.getDeclaredFields()) {
                    if (List.class.isAssignableFrom(f.getType())) { f.setAccessible(true); layersField = f; break; }
                }
            }
        }
        if (layersField == null) return null;
        try {
            return (List<?>) layersField.get(r);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    /** Every model a layer holds (armour layers hold two), found by looking at its fields. */
    private static List<ModelBase> layerModels(Object layer) {
        List<Field> fs = MODEL_FIELDS.get(layer.getClass());
        if (fs == null) {
            fs = new ArrayList<>();
            for (Class<?> c = layer.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || !ModelBase.class.isAssignableFrom(f.getType())) continue;
                    try { f.setAccessible(true); fs.add(f); } catch (RuntimeException ignored) { }
                }
            }
            MODEL_FIELDS.put(layer.getClass(), fs);
        }
        List<ModelBase> out = new ArrayList<>(fs.size());
        for (Field f : fs) {
            try {
                Object o = f.get(layer);
                if (o instanceof ModelBase) out.add((ModelBase) o);
            } catch (IllegalAccessException ignored) { }
        }
        return out;
    }

    // ------------------------------------------------------------------ drawing

    private static final FloatBuffer MAT = BufferUtils.createFloatBuffer(16);

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderWorld(RenderWorldLastEvent event) {
        Guard.run("Gibs.onRenderWorld", () -> onRenderWorld0(event));
    }

    private static void onRenderWorld0(RenderWorldLastEvent event) {
        if (!HIDDEN.isEmpty() || !REMOVED.isEmpty()) restore();
        if (GIBS.isEmpty() && (LOST.isEmpty() || !stumpsOn())) return;
        float bx = OpenGlHelper.lastBrightnessX, by = OpenGlHelper.lastBrightnessY;
        GlGuard.push();
        GlGuard.worldLit();
        try {
            draw(event.getPartialTicks());
        } catch (RuntimeException ex) {
            Visceral.LOG.debug("gib drawing failed", ex);
        } finally {
            RenderHelper.disableStandardItemLighting();
            GlStateManager.disableRescaleNormal();
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, bx, by);
            GlGuard.pop();
        }
    }

    private static void draw(float pt) {
        Minecraft mc = Minecraft.getMinecraft();
        Entity cam = mc.getRenderViewEntity();
        if (cam == null) return;
        double cx = cam.lastTickPosX + (cam.posX - cam.lastTickPosX) * pt;
        double cy = cam.lastTickPosY + (cam.posY - cam.lastTickPosY) * pt;
        double cz = cam.lastTickPosZ + (cam.posZ - cam.lastTickPosZ) * pt;
        World w = mc.world;
        RenderHelper.enableStandardItemLighting();
        GlStateManager.enableRescaleNormal();
        GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        GlStateManager.enableCull();
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1f);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();
        float now;
        ResourceLocation bound = null;
        FRUSTUM.setPosition(cx, cy, cz);
        double far = Math.max(32, VisceralConfig.blood.renderDistance + 16);
        for (Gib g : GIBS) {
            double x = g.px + (g.x - g.px) * pt, y = g.py + (g.y - g.py) * pt, z = g.pz + (g.z - g.pz) * pt;
            double dx = x - cx, dy = y - cy, dz = z - cz, d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > far * far) continue;
            double rr = Math.max(0.3, g.r * 3 + g.chunk);
            if (!FRUSTUM.isBoxInFrustum(x - rr, y - rr, z - rr, x + rr, y + rr, z + rr)) continue;
            boolean close = d2 < 26 * 26;
            bp.setPos(x, y + 0.1, z);
            int light = w.getCombinedLight(bp, 0);
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, light % 65536, light / 65536);
            now = g.age + pt * DT;
            float left = g.life - now;
            float shrink = left < 1.5f ? Math.max(0f, left / 1.5f) : 1f;
            shrink = Math.min(shrink, Math.min(1f, 0.6f + now * 8f));   // pops out of the body quickly
            if (shrink <= 0.01f) continue;
            GlStateManager.pushMatrix();
            GlStateManager.translate(x - cx, y - cy, z - cz);
            MAT.clear();
            for (int c = 0; c < 4; c++) {
                for (int r = 0; r < 4; r++) {
                    float v;
                    if (r < 3 && c < 3) v = g.plin[r * 3 + c] + (g.lin[r * 3 + c] - g.plin[r * 3 + c]) * pt;
                    else v = r == c ? 1f : 0f;
                    MAT.put(v);
                }
            }
            MAT.flip();
            GlStateManager.multMatrix(MAT);
            GlStateManager.scale(shrink, shrink, shrink);
            if (g.group != null) {
                if (bound != g.tex) { mc.getTextureManager().bindTexture(g.tex); bound = g.tex; }
                GlStateManager.enableTexture2D();
                GlStateManager.color(1f, 1f, 1f, 1f);
                if (g.charred) GlStateManager.color(0.3f, 0.25f, 0.22f, 1f);
                boolean bloody = close && !g.charred && Wounds.on() && !g.type.glows() && !g.type.none()
                        && Wounds.begin(g.type.color, 4, System.identityHashCode(g));
                try {
                    ModelParts.drawGroup(g.group, g.whole);
                    if (bloody) { Wounds.end(); bloody = false; }
                    // the torn end: raw flesh and bone where it came off
                    GlStateManager.color(1f, 1f, 1f, 1f);
                    if (close && stumpsOn() && g.type.stains() && !g.charred) {
                        cap(2f * g.group.hx * 0.8f, 2f * g.group.hz * 0.8f, g.group.limb || g.group.head);
                        mc.getTextureManager().bindTexture(g.tex);
                        bound = g.tex;
                    }
                } catch (RuntimeException ex) {
                    g.life = g.age;   // a model that won't draw like this: drop this gib
                } finally {
                    if (bloody) Wounds.end();
                }
            } else {
                GlStateManager.disableTexture2D();
                bound = null;
                cube(g.chunk, g.color, g.type.style == Style.SLIME ? 0.8f : 1f);
                GlStateManager.enableTexture2D();
            }
            GlStateManager.popMatrix();
        }
        GlStateManager.color(1f, 1f, 1f, 1f);
        if (stumpsOn() && !LOST.isEmpty()) {
            for (Map.Entry<Integer, Lost> en : LOST.entrySet()) {
                Entity ent = w.getEntityByID(en.getKey());
                if (!(ent instanceof EntityLivingBase) || BURST.contains(en.getKey())) continue;
                EntityLivingBase e = (EntityLivingBase) ent;
                if (!BloodTypes.of(e).stains()) continue;
                for (float[] st : en.getValue().stumps) {
                    double[] p = joint(e, st[0], st[1], st[2], pt);
                    bp.setPos(p[0], p[1], p[2]);
                    int light = w.getCombinedLight(bp, 0);
                    OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, light % 65536, light / 65536);
                    GlStateManager.pushMatrix();
                    GlStateManager.translate(p[0] - cx, p[1] - cy, p[2] - cz);
                    GlStateManager.rotate((float) p[3], 0f, 1f, 0f);
                    if (e.deathTime > 0) {
                        float f = Math.min(1f, (float) Math.sqrt(Math.max(0f, (e.deathTime + pt - 1f) / 20f * 1.6f)));
                        GlStateManager.rotate(f * 90f, 0f, 0f, 1f);
                    }
                    cap(st[3], st[4], st[5] > 0.5f);
                    GlStateManager.popMatrix();
                }
            }
        }
    }

    private static final ResourceLocation FLESH = new ResourceLocation(Visceral.MODID, "textures/fx/flesh.png");
    private static final net.minecraft.client.renderer.culling.Frustum FRUSTUM = new net.minecraft.client.renderer.culling.Frustum();

    static boolean stumpsOn() {
        return VisceralConfig.enabled && VisceralConfig.extras.stumps;
    }

    /** The cut end of a limb: a thin slab of raw flesh with bone in the middle, w x d blocks, at the joint. */
    private static void cap(float w, float d, boolean bone) {
        float u0 = bone ? 0f : 0.5f, u1 = u0 + 0.5f;
        w = Math.max(0.08f, w);
        d = Math.max(0.08f, d);
        float hw = w * 0.5f, hd = d * 0.5f, h = Math.min(0.05f, Math.min(w, d) * 0.18f);
        Minecraft.getMinecraft().getTextureManager().bindTexture(FLESH);
        GlStateManager.enableTexture2D();
        GlStateManager.color(1f, 1f, 1f, 1f);
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder b = tess.getBuffer();
        b.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_NORMAL);
        // both flat faces show the cut (whichever way the part hangs); the edges show the fatty rim
        b.pos(-hw, h, -hd).tex(u0, 0).normal(0, 1, 0).endVertex(); b.pos(-hw, h, hd).tex(u0, 1).normal(0, 1, 0).endVertex();
        b.pos(hw, h, hd).tex(u1, 1).normal(0, 1, 0).endVertex(); b.pos(hw, h, -hd).tex(u1, 0).normal(0, 1, 0).endVertex();
        b.pos(-hw, -h, -hd).tex(u0, 0).normal(0, -1, 0).endVertex(); b.pos(hw, -h, -hd).tex(u1, 0).normal(0, -1, 0).endVertex();
        b.pos(hw, -h, hd).tex(u1, 1).normal(0, -1, 0).endVertex(); b.pos(-hw, -h, hd).tex(u0, 1).normal(0, -1, 0).endVertex();
        float e0 = 0.02f, e1 = 0.12f;
        b.pos(hw, -h, -hd).tex(u0 + e0 * 0.5f, 0).normal(1, 0, 0).endVertex(); b.pos(hw, h, -hd).tex(u0 + e1 * 0.5f, 0).normal(1, 0, 0).endVertex();
        b.pos(hw, h, hd).tex(u0 + e1 * 0.5f, 1).normal(1, 0, 0).endVertex(); b.pos(hw, -h, hd).tex(u0 + e0 * 0.5f, 1).normal(1, 0, 0).endVertex();
        b.pos(-hw, -h, -hd).tex(u0 + e0 * 0.5f, 0).normal(-1, 0, 0).endVertex(); b.pos(-hw, -h, hd).tex(u0 + e0 * 0.5f, 1).normal(-1, 0, 0).endVertex();
        b.pos(-hw, h, hd).tex(u0 + e1 * 0.5f, 1).normal(-1, 0, 0).endVertex(); b.pos(-hw, h, -hd).tex(u0 + e1 * 0.5f, 0).normal(-1, 0, 0).endVertex();
        b.pos(-hw, -h, hd).tex(u0, e0).normal(0, 0, 1).endVertex(); b.pos(hw, -h, hd).tex(u1, e0).normal(0, 0, 1).endVertex();
        b.pos(hw, h, hd).tex(u1, e1).normal(0, 0, 1).endVertex(); b.pos(-hw, h, hd).tex(u0, e1).normal(0, 0, 1).endVertex();
        b.pos(-hw, -h, -hd).tex(u0, e0).normal(0, 0, -1).endVertex(); b.pos(-hw, h, -hd).tex(u0, e1).normal(0, 0, -1).endVertex();
        b.pos(hw, h, -hd).tex(u1, e1).normal(0, 0, -1).endVertex(); b.pos(hw, -h, -hd).tex(u1, e0).normal(0, 0, -1).endVertex();
        tess.draw();
    }

    /** A plain lit cube s blocks across, centred. */
    private static void cube(float s, int c, float a) {
        float h = s * 0.5f;
        GlStateManager.color(((c >> 16) & 255) / 255f, ((c >> 8) & 255) / 255f, (c & 255) / 255f, a);
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder b = tess.getBuffer();
        b.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_NORMAL);
        // +Y, -Y, +X, -X, +Z, -Z (counter-clockwise from outside)
        b.pos(-h, h, -h).normal(0, 1, 0).endVertex(); b.pos(-h, h, h).normal(0, 1, 0).endVertex();
        b.pos(h, h, h).normal(0, 1, 0).endVertex(); b.pos(h, h, -h).normal(0, 1, 0).endVertex();
        b.pos(-h, -h, -h).normal(0, -1, 0).endVertex(); b.pos(h, -h, -h).normal(0, -1, 0).endVertex();
        b.pos(h, -h, h).normal(0, -1, 0).endVertex(); b.pos(-h, -h, h).normal(0, -1, 0).endVertex();
        b.pos(h, -h, -h).normal(1, 0, 0).endVertex(); b.pos(h, h, -h).normal(1, 0, 0).endVertex();
        b.pos(h, h, h).normal(1, 0, 0).endVertex(); b.pos(h, -h, h).normal(1, 0, 0).endVertex();
        b.pos(-h, -h, -h).normal(-1, 0, 0).endVertex(); b.pos(-h, -h, h).normal(-1, 0, 0).endVertex();
        b.pos(-h, h, h).normal(-1, 0, 0).endVertex(); b.pos(-h, h, -h).normal(-1, 0, 0).endVertex();
        b.pos(-h, -h, h).normal(0, 0, 1).endVertex(); b.pos(h, -h, h).normal(0, 0, 1).endVertex();
        b.pos(h, h, h).normal(0, 0, 1).endVertex(); b.pos(-h, h, h).normal(0, 0, 1).endVertex();
        b.pos(-h, -h, -h).normal(0, 0, -1).endVertex(); b.pos(-h, h, -h).normal(0, 0, -1).endVertex();
        b.pos(h, h, -h).normal(0, 0, -1).endVertex(); b.pos(h, -h, -h).normal(0, 0, -1).endVertex();
        tess.draw();
    }

    // ------------------------------------------------------------------ 3x3 maths (row-major)

    static float[] mul3(float[] a, float[] b) {
        float[] o = new float[9];
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 3; c++)
                o[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c];
        return o;
    }

    /** Rotation by `ang` radians around (x,y,z). */
    static float[] axisAngle(float x, float y, float z, float ang) {
        float l = (float) Math.sqrt(x * x + y * y + z * z);
        if (l < 1e-6f) return new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1};
        x /= l; y /= l; z /= l;
        float c = (float) Math.cos(ang), s = (float) Math.sin(ang), t = 1 - c;
        return new float[]{
                t * x * x + c, t * x * y - s * z, t * x * z + s * y,
                t * x * y + s * z, t * y * y + c, t * y * z - s * x,
                t * x * z - s * y, t * y * z + s * x, t * z * z + c};
    }
}
