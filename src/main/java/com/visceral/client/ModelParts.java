package com.visceral.client;

import com.visceral.Visceral;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.ModelChicken;
import net.minecraft.client.model.ModelOcelot;
import net.minecraft.client.model.ModelQuadruped;
import net.minecraft.client.model.ModelRabbit;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.model.ModelWolf;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderLivingBase;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.lang.reflect.Method;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads a creature's body parts out of its own model, whatever mod it's from. Almost every 1.12.2
 * creature is drawn from boxes grouped into parts (ModelRenderer): head, body, arms, legs, tail,
 * wings... This finds those parts, poses them exactly as the creature stands right now, and works
 * out where each one is in the world, so they can fly off as gibs that look like the real thing.
 *
 * Which part is what, for any model: the parts that turn when the creature looks around are its
 * head; the parts that swing when it walks are its limbs; the one in the middle of its mass is its
 * body. Found by posing the model a few different ways and watching what moves.
 */
public final class ModelParts {
    private ModelParts() {}

    static final float S = 0.0625f;

    /** A body part: one or more of the model's top-level pieces that sit together (a head and its hat layer). */
    static final class Group {
        final List<ModelRenderer> roots = new ArrayList<>();
        /** Every piece under these roots, posed as at capture: {pointX, pointY, pointZ, angleX, angleY, angleZ, offX, offY, offZ}. */
        final Map<ModelRenderer, float[]> pose = new IdentityHashMap<>();
        final float[] lin = new float[9];    // part space -> world (rotation and scale), row-major
        double wx, wy, wz;                   // the part's middle, in the world
        float cx, cy, cz;                    // the part's middle, in part space (blocks)
        float hx, hy, hz;                    // its half size, in part space (blocks)
        float vol;                           // size in model pixels^3
        float mx, my, mz;                    // middle in model space (blocks, y down), for telling parts apart
        double sx, sy, sz;                   // where it joins the body, around the creature (turned with it)
        boolean head, limb, core;
        /** The children drawn with each piece (small ones like a beak stay on; big ones are pieces of their own). */
        final Map<ModelRenderer, List<ModelRenderer>> kids = new IdentityHashMap<>();
        /** The parts this one hangs from, in nested models (if one of them is torn off, so is this). */
        final List<ModelRenderer> path = new ArrayList<>();
        float[] modelMat;                    // model space of the piece's joint
        float[] minP = {1e9f, 1e9f, 1e9f}, maxP = {-1e9f, -1e9f, -1e9f};   // pixels, part space

        /** About how big it is in the world (for collisions and blood amounts). */
        float worldRadius() {
            float ax = len(lin[0], lin[3], lin[6]) * hx, ay = len(lin[1], lin[4], lin[7]) * hy, az = len(lin[2], lin[5], lin[8]) * hz;
            return Math.max(0.04f, Math.min(1.2f, Math.min(ax, Math.min(ay, az)) * 0.85f + Math.max(ax, Math.max(ay, az)) * 0.15f));
        }
    }

    static final class Capture {
        ModelBase model;
        ResourceLocation tex;
        RenderLivingBase<?> renderer;
        final List<Group> groups = new ArrayList<>();
        float theta;    // the creature's turn, degrees (as drawn: 180 - body yaw)
    }

    private static Method preRender, getTex;
    private static boolean looked;

    private static void lookup() {
        if (looked) return;
        looked = true;
        try {
            preRender = ObfuscationReflectionHelper.findMethod(RenderLivingBase.class, "func_77041_b", void.class, EntityLivingBase.class, float.class);
        } catch (Throwable t) {
            Visceral.LOG.warn("Visceral: can't size creatures by their renderer ({}); big and small creatures' gibs may be off", t.toString());
        }
        try {
            getTex = ObfuscationReflectionHelper.findMethod(Render.class, "func_110775_a", ResourceLocation.class, Entity.class);
        } catch (Throwable t) {
            Visceral.LOG.warn("Visceral: can't find creature textures ({}); gibs will be plain chunks", t.toString());
        }
    }

    // ------------------------------------------------------------------ capture

    /** The creature's body parts as they are right now, or null if its model isn't one made of parts. */
    static Capture capture(EntityLivingBase e, float pt) {
        lookup();
        Minecraft mc = Minecraft.getMinecraft();
        Render<?> r = mc.getRenderManager().getEntityRenderObject(e);
        if (!(r instanceof RenderLivingBase) || getTex == null) return null;
        RenderLivingBase<?> rl = (RenderLivingBase<?>) r;
        ModelBase model = rl.getMainModel();
        if (model == null || model.boxList == null || model.boxList.isEmpty()) return null;
        ResourceLocation tex;
        try {
            tex = (ResourceLocation) getTex.invoke(rl, e);
        } catch (Throwable t) {
            return null;
        }
        if (tex == null) return null;

        List<ModelRenderer> roots = roots(model);
        if (roots.isEmpty()) return null;

        // pose the model as this creature, watching which parts follow the head and which the legs
        float yaw = lerpAngle(e.prevRenderYawOffset, e.renderYawOffset, pt);
        float headYaw = MathHelper.wrapDegrees(lerpAngle(e.prevRotationYawHead, e.rotationYawHead, pt) - yaw);
        float pitch = e.prevRotationPitch + (e.rotationPitch - e.prevRotationPitch) * pt;
        float lsa = Math.min(1f, e.prevLimbSwingAmount + (e.limbSwingAmount - e.prevLimbSwingAmount) * pt);
        float ls = e.limbSwing - e.limbSwingAmount * (1f - pt);
        float age = e.ticksExisted + pt;
        Set<ModelRenderer> heads = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<ModelRenderer> limbs = Collections.newSetFromMap(new IdentityHashMap<>());
        try {
            model.swingProgress = e.getSwingProgress(pt);
            model.isRiding = e.isRiding();
            model.isChild = e.isChild();
            pose(model, e, ls, lsa, age, 0f, 0f, pt);
            Map<ModelRenderer, float[]> a0 = snap(model);
            pose(model, e, ls, lsa, age, 40f, 25f, pt);
            Map<ModelRenderer, float[]> a1 = snap(model);
            pose(model, e, 0f, 1f, age, headYaw, pitch, pt);
            Map<ModelRenderer, float[]> b0 = snap(model);
            pose(model, e, 1.7f, 1f, age, headYaw, pitch, pt);
            Map<ModelRenderer, float[]> b1 = snap(model);
            for (Map.Entry<ModelRenderer, float[]> en : a0.entrySet()) {
                ModelRenderer mr = en.getKey();
                if (differ(en.getValue(), a1.get(mr))) heads.add(mr);
                else if (differ(b0.get(mr), b1.get(mr))) limbs.add(mr);
            }
        } catch (Throwable t) {
            // a model that doesn't like being posed by someone else: keep whatever pose it has
        }
        try {
            pose(model, e, ls, lsa, age, headYaw, pitch, pt);
        } catch (Throwable ignored) { }

        Capture cap = new Capture();
        cap.model = model;
        cap.tex = tex;
        cap.renderer = rl;
        cap.theta = 180f - yaw;

        // the creature's own transform, as RenderLivingBase draws it: turn, flip, its renderer's scale, lift
        float[] m0 = mul(scale(-1f, -1f, 1f), preMatrix(rl, e, pt));
        m0 = mul(m0, translate(0f, -1.501f, 0f));
        if (e.isChild() && (model instanceof ModelBiped || model instanceof ModelQuadruped || model instanceof ModelChicken
                || model instanceof ModelWolf || model instanceof ModelOcelot || model instanceof ModelRabbit)) {
            m0 = mul(m0, mul(scale(0.5f, 0.5f, 0.5f), translate(0f, 24f * S, 0f)));
        }
        float[] m = mul(rotY(cap.theta), m0);
        double ex = e.lastTickPosX + (e.posX - e.lastTickPosX) * pt;
        double ey = e.lastTickPosY + (e.posY - e.lastTickPosY) * pt;
        double ez = e.lastTickPosZ + (e.posZ - e.lastTickPosZ) * pt;

        // every piece of the body: the top-level parts, and inside them every part big enough to be a
        // limb of its own (modded models often hang the whole body off one or two parts)
        float total = 0f;
        for (ModelRenderer mr : roots) if (!mr.isHidden && mr.showModel) total += subVol(mr, 0);
        total = Math.max(1f, total);
        List<Piece> pieces = new ArrayList<>();
        for (ModelRenderer mr : roots) {
            if (mr.isHidden || !mr.showModel) continue;
            split(mr, ident(), total, pieces, new ArrayList<>(), 0);
        }
        // pieces at the same joint that overlap (a head and its hat layer) go together
        for (Piece pc : pieces) {
            Group into = null;
            for (Group g : cap.groups) if (same(g.modelMat, pc.mat) && overlap(g, pc)) { into = g; break; }
            if (into == null) {
                into = new Group();
                into.modelMat = pc.mat;
                into.path.addAll(pc.path);
                cap.groups.add(into);
            }
            into.roots.add(pc.node);
            into.kids.put(pc.node, pc.kept);
            bounds(into, pc);
            savePose(into.pose, pc.node);
            if (anyIn(heads, pc)) into.head = true;
            else if (anyIn(limbs, pc)) into.limb = true;
        }
        if (cap.groups.isEmpty()) return null;
        for (Group g : cap.groups) {
            if (g.head) g.limb = false;
            float[] p = g.modelMat;
            float[] w = mul(m, p), w0 = mul(m0, p);
            for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) g.lin[i * 3 + j] = w[i * 4 + j];
            g.cx = (g.minP[0] + g.maxP[0]) * 0.5f * S;
            g.cy = (g.minP[1] + g.maxP[1]) * 0.5f * S;
            g.cz = (g.minP[2] + g.maxP[2]) * 0.5f * S;
            g.hx = Math.max(0.5f, (g.maxP[0] - g.minP[0]) * 0.5f) * S;
            g.hy = Math.max(0.5f, (g.maxP[1] - g.minP[1]) * 0.5f) * S;
            g.hz = Math.max(0.5f, (g.maxP[2] - g.minP[2]) * 0.5f) * S;
            g.vol = Math.max(1f, (g.maxP[0] - g.minP[0]) * (g.maxP[1] - g.minP[1]) * (g.maxP[2] - g.minP[2]));
            float[] c = apply(w, g.cx, g.cy, g.cz);
            g.wx = ex + c[0]; g.wy = ey + c[1]; g.wz = ez + c[2];
            float[] mc0 = apply(p, g.cx, g.cy, g.cz);
            g.mx = mc0[0]; g.my = mc0[1]; g.mz = mc0[2];
            float[] joint = apply(w0, 0f, 0f, 0f);
            g.sx = joint[0]; g.sy = joint[1]; g.sz = joint[2];
        }
        classify(cap);
        return cap;
    }

    /** The body is the part nearest the middle of the creature's mass; limbs are what's left that isn't too big. */
    private static void classify(Capture cap) {
        float tv = 0, ax = 0, ay = 0, az = 0, maxV = 0;
        for (Group g : cap.groups) {
            tv += g.vol; ax += g.mx * g.vol; ay += g.my * g.vol; az += g.mz * g.vol;
            maxV = Math.max(maxV, g.vol);
        }
        ax /= tv; ay /= tv; az /= tv;
        Group core = null;
        double best = 1e18;
        for (Group g : cap.groups) {
            if (g.head) continue;
            double d = (g.mx - ax) * (g.mx - ax) + (g.my - ay) * (g.my - ay) + (g.mz - az) * (g.mz - az);
            d /= Math.cbrt(g.vol);   // bigger parts are likelier the body
            if (d < best) { best = d; core = g; }
        }
        if (core != null) { core.core = true; core.limb = false; }
        boolean any = false;
        for (Group g : cap.groups) if (g.limb) { any = true; break; }
        if (!any && core != null) {
            // nothing swung when it walked (a modded model with its own animation): go by size
            for (Group g : cap.groups) {
                if (g.head || g.core) continue;
                g.limb = g.vol <= core.vol * 0.6f && g.vol >= maxV * 0.04f && g.my >= core.my - 0.2f;
            }
        }
        // too small to be worth tearing off (a beak, a nose)
        for (Group g : cap.groups) if (g.limb && g.vol < maxV * 0.03f) g.limb = false;
    }

    /** One piece: a part, the small children that stay on it, where it sits in the model, what it hangs from. */
    static final class Piece {
        ModelRenderer node;
        float[] mat;
        final List<ModelRenderer> kept = new ArrayList<>();
        final List<ModelRenderer> path = new ArrayList<>();
    }

    /**
     * Breaks a part into pieces: the part with its small children (eyes, a beak, a jaw, a forearm),
     * and each child big enough to be a limb as a piece of its own, and so on down. A part with no boxes of its own (a pivot that only holds others) gives up all its children.
     */
    private static void split(ModelRenderer node, float[] parentMat, float total, List<Piece> out, List<ModelRenderer> path, int depth) {
        Piece pc = new Piece();
        pc.node = node;
        pc.mat = mul(parentMat, part(node));
        pc.path.addAll(path);
        float own = ownVol(node);
        List<ModelRenderer> off = new ArrayList<>();
        if (node.childModels != null && depth < 16) {
            for (ModelRenderer c : node.childModels) {
                if (c.isHidden || !c.showModel) { pc.kept.add(c); continue; }   // not drawn anyway
                // its own piece if it's limb-sized and either clearly smaller than what holds it (an arm
                // off a body) or big in its own right (a head); a forearm stays on its arm, a tail's
                // segments stay together
                float sv = subVol(c, 0);
                boolean big = sv >= 0.05f * total && (own >= 1.2f * sv || sv >= 0.15f * total);
                if (own <= 0f || big) off.add(c);
                else pc.kept.add(c);
            }
        }
        if (own > 0f || hasKeptBoxes(pc)) out.add(pc);
        if (off.isEmpty()) return;
        List<ModelRenderer> down = new ArrayList<>(path);
        down.add(node);
        for (ModelRenderer c : off) split(c, pc.mat, total, out, down, depth + 1);
    }

    private static boolean hasKeptBoxes(Piece pc) {
        for (ModelRenderer c : pc.kept) if (!c.isHidden && c.showModel && hasBoxes(c)) return true;
        return false;
    }

    private static float ownVol(ModelRenderer mr) {
        float v = 0f;
        if (mr.cubeList != null) {
            for (ModelBox b : mr.cubeList) {
                v += Math.abs((b.posX2 - b.posX1) * (b.posY2 - b.posY1) * (b.posZ2 - b.posZ1));
            }
        }
        return v;
    }

    private static float subVol(ModelRenderer mr, int depth) {
        if (mr.isHidden || !mr.showModel) return 0f;
        float v = ownVol(mr);
        if (mr.childModels != null && depth < 16) for (ModelRenderer c : mr.childModels) v += subVol(c, depth + 1);
        return v;
    }

    /** The piece, or a child that stays on it, is in the set. */
    private static boolean anyIn(Set<ModelRenderer> set, Piece pc) {
        if (set.contains(pc.node)) return true;
        for (ModelRenderer c : pc.kept) if (inTree(set, c, 0)) return true;
        return false;
    }

    private static boolean inTree(Set<ModelRenderer> set, ModelRenderer mr, int depth) {
        if (set.contains(mr)) return true;
        if (mr.childModels != null && depth < 16) for (ModelRenderer c : mr.childModels) if (inTree(set, c, depth + 1)) return true;
        return false;
    }

    private static boolean same(float[] a, float[] b) {
        for (int i = 0; i < 16; i++) if (Math.abs(a[i] - b[i]) > 1e-4f) return false;
        return true;
    }

    /** Every part's own turn, to see which ones move when the model is posed differently. */
    private static Map<ModelRenderer, float[]> snap(ModelBase model) {
        Map<ModelRenderer, float[]> out = new IdentityHashMap<>();
        for (ModelRenderer mr : model.boxList) out.put(mr, new float[]{mr.rotateAngleX, mr.rotateAngleY, mr.rotateAngleZ});
        return out;
    }

    private static void pose(ModelBase model, EntityLivingBase e, float ls, float lsa, float age, float headYaw, float pitch, float pt) {
        model.setLivingAnimations(e, ls, lsa, pt);
        model.setRotationAngles(ls, lsa, age, headYaw, pitch, S, e);
    }

    private static float[][] angles(List<ModelRenderer> roots) {
        float[][] out = new float[roots.size()][];
        for (int i = 0; i < roots.size(); i++) {
            List<Float> l = new ArrayList<>();
            collectAngles(roots.get(i), l, 0);
            float[] a = new float[l.size()];
            for (int k = 0; k < a.length; k++) a[k] = l.get(k);
            out[i] = a;
        }
        return out;
    }

    private static void collectAngles(ModelRenderer mr, List<Float> out, int depth) {
        out.add(mr.rotateAngleX); out.add(mr.rotateAngleY); out.add(mr.rotateAngleZ);
        if (mr.childModels != null && depth < 16) for (ModelRenderer c : mr.childModels) collectAngles(c, out, depth + 1);
    }

    private static boolean differ(float[] a, float[] b) {
        if (a.length != b.length) return true;
        for (int i = 0; i < a.length; i++) if (Math.abs(a[i] - b[i]) > 0.02f) return true;
        return false;
    }

    /** The model's top-level pieces (the ones no other piece carries). */
    static List<ModelRenderer> roots(ModelBase model) {
        Set<ModelRenderer> kids = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ModelRenderer mr : model.boxList) collectKids(mr, kids, 0);
        Set<ModelRenderer> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<ModelRenderer> out = new ArrayList<>();
        for (ModelRenderer mr : model.boxList) if (!kids.contains(mr) && seen.add(mr)) out.add(mr);
        return out;
    }

    private static void collectKids(ModelRenderer mr, Set<ModelRenderer> kids, int depth) {
        if (mr.childModels == null || depth > 16) return;
        for (ModelRenderer c : mr.childModels) if (kids.add(c)) collectKids(c, kids, depth + 1);
    }

    private static boolean hasBoxes(ModelRenderer mr) {
        if (mr.cubeList != null && !mr.cubeList.isEmpty()) return true;
        if (mr.childModels != null) for (ModelRenderer c : mr.childModels) if (hasBoxes(c)) return true;
        return false;
    }

    private static boolean sameJoint(ModelRenderer a, ModelRenderer b) {
        return Math.abs(a.rotationPointX - b.rotationPointX) < 1e-3f && Math.abs(a.rotationPointY - b.rotationPointY) < 1e-3f
                && Math.abs(a.rotationPointZ - b.rotationPointZ) < 1e-3f && Math.abs(a.rotateAngleX - b.rotateAngleX) < 1e-3f
                && Math.abs(a.rotateAngleY - b.rotateAngleY) < 1e-3f && Math.abs(a.rotateAngleZ - b.rotateAngleZ) < 1e-3f
                && Math.abs(a.offsetX - b.offsetX) < 1e-4f && Math.abs(a.offsetY - b.offsetY) < 1e-4f && Math.abs(a.offsetZ - b.offsetZ) < 1e-4f;
    }

    /** Most of the smaller one inside the bigger one: one part drawn twice (skin and its outer layer). */
    private static boolean overlap(Group g, Piece pc) {
        Group t = new Group();
        bounds(t, pc);
        float ix = Math.min(g.maxP[0], t.maxP[0]) - Math.max(g.minP[0], t.minP[0]);
        float iy = Math.min(g.maxP[1], t.maxP[1]) - Math.max(g.minP[1], t.minP[1]);
        float iz = Math.min(g.maxP[2], t.maxP[2]) - Math.max(g.minP[2], t.minP[2]);
        if (ix <= 0 || iy <= 0 || iz <= 0) return false;
        float vg = (g.maxP[0] - g.minP[0]) * (g.maxP[1] - g.minP[1]) * (g.maxP[2] - g.minP[2]);
        float vt = (t.maxP[0] - t.minP[0]) * (t.maxP[1] - t.minP[1]) * (t.maxP[2] - t.minP[2]);
        return ix * iy * iz >= 0.5f * Math.min(vg, vt);
    }

    /** A piece's size: its own boxes and the children that stay on it. */
    private static void bounds(Group g, Piece pc) {
        boolean any = false;
        if (pc.node.cubeList != null && !pc.node.cubeList.isEmpty()) {
            List<ModelRenderer> saved = pc.node.childModels;
            pc.node.childModels = null;
            try { bounds(g, pc.node, 0f, 0f, 0f, 0); } finally { pc.node.childModels = saved; }
            any = true;
        }
        for (ModelRenderer c : pc.kept) {
            if (c.isHidden || !c.showModel || !hasBoxes(c)) continue;
            bounds(g, c, c.rotationPointX, c.rotationPointY, c.rotationPointZ, 1);
            any = true;
        }
        if (!any) for (int i = 0; i < 3; i++) { g.minP[i] = -1f; g.maxP[i] = 1f; }
    }

    private static void bounds(Group g, ModelRenderer mr, float ox, float oy, float oz, int depth) {
        if (mr.cubeList != null) {
            for (ModelBox b : mr.cubeList) {
                g.minP[0] = Math.min(g.minP[0], ox + Math.min(b.posX1, b.posX2));
                g.minP[1] = Math.min(g.minP[1], oy + Math.min(b.posY1, b.posY2));
                g.minP[2] = Math.min(g.minP[2], oz + Math.min(b.posZ1, b.posZ2));
                g.maxP[0] = Math.max(g.maxP[0], ox + Math.max(b.posX1, b.posX2));
                g.maxP[1] = Math.max(g.maxP[1], oy + Math.max(b.posY1, b.posY2));
                g.maxP[2] = Math.max(g.maxP[2], oz + Math.max(b.posZ1, b.posZ2));
            }
        }
        if (mr.childModels != null && depth < 16) {
            for (ModelRenderer c : mr.childModels) bounds(g, c, ox + c.rotationPointX, oy + c.rotationPointY, oz + c.rotationPointZ, depth + 1);
        }
        if (g.minP[0] > g.maxP[0]) {   // no boxes at all
            for (int i = 0; i < 3; i++) { g.minP[i] = -1f; g.maxP[i] = 1f; }
        }
    }

    // ------------------------------------------------------------------ a creature's colours

    private static final Map<ResourceLocation, Integer> AVG = new java.util.HashMap<>();

    /**
     * The average colour of a creature's texture (its opaque pixels), for chunks of creatures whose
     * model can't be read into parts: they burst into bits of their own colours. -1 if unknown.
     */
    static int averageColor(Entity e) {
        lookup();
        if (getTex == null) return -1;
        try {
            Render<?> r = Minecraft.getMinecraft().getRenderManager().getEntityRenderObject(e);
            if (r == null) return -1;
            return averageColor((ResourceLocation) getTex.invoke(r, e));
        } catch (Throwable t) {
            return -1;
        }
    }

    /** A texture's pixels, read once: {width, height, argb...}; an empty array if it can't be read. */
    private static final Map<ResourceLocation, int[]> PIXELS = new java.util.HashMap<>();

    /** After a resource reload: textures are read again (a resource pack may have changed them). */
    public static void forgetTextures() {
        PIXELS.clear();
        AVG.clear();
    }

    private static int[] pixels(ResourceLocation tex) {
        int[] px = PIXELS.get(tex);
        if (px != null) return px;
        px = new int[0];
        try (java.io.InputStream in = Minecraft.getMinecraft().getResourceManager().getResource(tex).getInputStream()) {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(in);
            int w = img.getWidth(), h = img.getHeight();
            if (w * h <= 2048 * 2048) {
                px = new int[2 + w * h];
                px[0] = w; px[1] = h;
                img.getRGB(0, 0, w, h, px, 2, w);
            }
        } catch (Throwable t) {
            // a texture that isn't a resource (a downloaded skin) or can't be read
        }
        PIXELS.put(tex, px);
        return px;
    }

    /** The average colour of a texture's opaque pixels, -1 if unknown. Worked out once per texture. */
    static int averageColor(ResourceLocation tex) {
        if (tex == null) return -1;
        Integer c = AVG.get(tex);
        if (c != null) return c;
        int[] px = pixels(tex);
        int out = -1;
        if (px.length > 2) {
            int w = px[0], h = px[1];
            out = average(px, 0, 0, w, h, Math.max(1, w / 64));
        }
        AVG.put(tex, out);
        return out;
    }

    private static long[] acc = new long[4];

    /** The average of the opaque pixels in [x0,x1) x [y0,y1), -1 if none. */
    private static int average(int[] px, int x0, int y0, int x1, int y1, int step) {
        acc[0] = acc[1] = acc[2] = acc[3] = 0;
        add(px, x0, y0, x1, y1, step);
        return result();
    }

    private static void add(int[] px, int x0, int y0, int x1, int y1, int step) {
        int w = px[0], h = px[1];
        x0 = Math.max(0, x0); y0 = Math.max(0, y0); x1 = Math.min(w, x1); y1 = Math.min(h, y1);
        for (int y = y0; y < y1; y += step) {
            for (int x = x0; x < x1; x += step) {
                int p = px[2 + y * w + x];
                if ((p >>> 24) < 128) continue;
                acc[0] += (p >> 16) & 255; acc[1] += (p >> 8) & 255; acc[2] += p & 255; acc[3]++;
            }
        }
    }

    private static int result() {
        long n = acc[3];
        return n == 0 ? -1 : (int) (acc[0] / n) << 16 | (int) (acc[1] / n) << 8 | (int) (acc[2] / n);
    }

    private static java.lang.reflect.Field quadsField;
    private static boolean quadsLooked;

    /** ModelBox's faces (a private field, found by its type so it works under any mapping). */
    private static net.minecraft.client.model.TexturedQuad[] quads(ModelBox box) throws IllegalAccessException {
        if (!quadsLooked) {
            quadsLooked = true;
            for (java.lang.reflect.Field f : ModelBox.class.getDeclaredFields()) {
                if (f.getType() == net.minecraft.client.model.TexturedQuad[].class) { f.setAccessible(true); quadsField = f; }
            }
        }
        return quadsField == null ? null : (net.minecraft.client.model.TexturedQuad[]) quadsField.get(box);
    }

    /**
     * The colour of one body part's own skin: the average of the texture where that part's boxes are
     * painted (a zombie's head is green even though its texture is mostly shirt). Falls back to the
     * whole texture's average; -1 if unknown.
     */
    static int partColor(ResourceLocation tex, Group g) {
        if (tex == null || g == null) return -1;
        try {
            int[] px = pixels(tex);
            if (px.length <= 2) return -1;
            int w = px[0], h = px[1];
            acc[0] = acc[1] = acc[2] = acc[3] = 0;
            for (ModelRenderer mr : g.roots) {
                if (mr.cubeList == null) continue;
                for (ModelBox box : mr.cubeList) {
                    net.minecraft.client.model.TexturedQuad[] qs = quads(box);
                    if (qs == null) return averageColor(tex);
                    for (net.minecraft.client.model.TexturedQuad q : qs) {
                        if (q == null || q.vertexPositions == null) continue;
                        float u0 = 1, v0 = 1, u1 = 0, v1 = 0;
                        for (net.minecraft.client.model.PositionTextureVertex v : q.vertexPositions) {
                            u0 = Math.min(u0, v.texturePositionX); u1 = Math.max(u1, v.texturePositionX);
                            v0 = Math.min(v0, v.texturePositionY); v1 = Math.max(v1, v.texturePositionY);
                        }
                        add(px, Math.round(u0 * w), Math.round(v0 * h), Math.round(u1 * w), Math.round(v1 * h), 1);
                    }
                }
            }
            int c = result();
            return c >= 0 ? c : averageColor(tex);
        } catch (Throwable t) {
            return averageColor(tex);
        }
    }

    // ------------------------------------------------------------------ poses

    static void savePose(Map<ModelRenderer, float[]> into, ModelRenderer mr) {
        savePose(into, mr, 0);
    }

    private static void savePose(Map<ModelRenderer, float[]> into, ModelRenderer mr, int depth) {
        into.put(mr, new float[]{mr.rotationPointX, mr.rotationPointY, mr.rotationPointZ, mr.rotateAngleX, mr.rotateAngleY,
                mr.rotateAngleZ, mr.offsetX, mr.offsetY, mr.offsetZ, mr.showModel ? 1 : 0, mr.isHidden ? 1 : 0});
        if (mr.childModels != null && depth < 16) for (ModelRenderer c : mr.childModels) savePose(into, c, depth + 1);
    }

    static void applyPose(Map<ModelRenderer, float[]> pose) {
        for (Map.Entry<ModelRenderer, float[]> en : pose.entrySet()) {
            ModelRenderer mr = en.getKey();
            float[] v = en.getValue();
            mr.rotationPointX = v[0]; mr.rotationPointY = v[1]; mr.rotationPointZ = v[2];
            mr.rotateAngleX = v[3]; mr.rotateAngleY = v[4]; mr.rotateAngleZ = v[5];
            mr.offsetX = v[6]; mr.offsetY = v[7]; mr.offsetZ = v[8];
            mr.showModel = v[9] > 0.5f; mr.isHidden = v[10] > 0.5f;
        }
    }

    /**
     * Draws a part where the GL matrix already is (its middle at the origin, turned and scaled as
     * the gib is): its pose set as captured, its joint moved to the origin, then everything put back
     * the way the creature's renderer had it.
     */
    static void drawGroup(Group g, boolean whole) {
        Map<ModelRenderer, float[]> was = new IdentityHashMap<>();
        Map<ModelRenderer, List<ModelRenderer>> kidsWas = new IdentityHashMap<>();
        for (ModelRenderer mr : g.roots) {
            savePose(was, mr);
            kidsWas.put(mr, mr.childModels);
        }
        try {
            applyPose(g.pose);
            GlStateManager.translate(-g.cx, -g.cy, -g.cz);
            for (ModelRenderer mr : g.roots) {
                mr.rotationPointX = mr.rotationPointY = mr.rotationPointZ = 0f;
                mr.rotateAngleX = mr.rotateAngleY = mr.rotateAngleZ = 0f;
                mr.offsetX = mr.offsetY = mr.offsetZ = 0f;
                mr.showModel = true;
                mr.isHidden = false;
                // a burst part draws only the children that stay on it (the rest are gibs of their
                // own); a torn-off limb draws everything below it
                if (!whole) {
                    List<ModelRenderer> k = g.kids.get(mr);
                    mr.childModels = k == null || k.isEmpty() ? null : k;
                }
                mr.render(S);
            }
        } finally {
            for (Map.Entry<ModelRenderer, List<ModelRenderer>> en : kidsWas.entrySet()) en.getKey().childModels = en.getValue();
            applyPose(was);
        }
    }

    // ------------------------------------------------------------------ the renderer's own scale

    private static final FloatBuffer FB = BufferUtils.createFloatBuffer(16);

    /** What the creature's renderer adds before drawing it (bigger, smaller, squished): read off the GL matrix. */
    private static float[] preMatrix(RenderLivingBase<?> rl, EntityLivingBase e, float pt) {
        if (preRender == null) return ident();
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.pushMatrix();
        try {
            GlStateManager.loadIdentity();
            preRender.invoke(rl, e, pt);
            FB.clear();
            GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, FB);
            float[] m = new float[16];
            for (int c = 0; c < 4; c++) for (int r = 0; r < 4; r++) m[r * 4 + c] = FB.get(c * 4 + r);
            if (Float.isNaN(m[0]) || Math.abs(m[15]) < 1e-6f) return ident();
            return m;
        } catch (Throwable t) {
            return ident();
        } finally {
            GlStateManager.popMatrix();
            GlStateManager.color(1f, 1f, 1f, 1f);
        }
    }

    // ------------------------------------------------------------------ 4x4 maths (row-major)

    static float[] ident() {
        return new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    static float[] translate(float x, float y, float z) {
        float[] m = ident();
        m[3] = x; m[7] = y; m[11] = z;
        return m;
    }

    static float[] scale(float x, float y, float z) {
        float[] m = ident();
        m[0] = x; m[5] = y; m[10] = z;
        return m;
    }

    static float[] rotY(float deg) {
        float r = (float) Math.toRadians(deg), c = MathHelper.cos(r), s = MathHelper.sin(r);
        return new float[]{c, 0, s, 0, 0, 1, 0, 0, -s, 0, c, 0, 0, 0, 0, 1};
    }

    static float[] rotX(float rad) {
        float c = MathHelper.cos(rad), s = MathHelper.sin(rad);
        return new float[]{1, 0, 0, 0, 0, c, -s, 0, 0, s, c, 0, 0, 0, 0, 1};
    }

    static float[] rotZ(float rad) {
        float c = MathHelper.cos(rad), s = MathHelper.sin(rad);
        return new float[]{c, -s, 0, 0, s, c, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    static float[] rotYr(float rad) {
        return rotY((float) Math.toDegrees(rad));
    }

    /** A piece's own transform, as ModelRenderer.render applies it: offset, joint, then turn Z, Y, X. */
    static float[] part(ModelRenderer mr) {
        float[] m = translate(mr.offsetX, mr.offsetY, mr.offsetZ);
        m = mul(m, translate(mr.rotationPointX * S, mr.rotationPointY * S, mr.rotationPointZ * S));
        m = mul(m, rotZ(mr.rotateAngleZ));
        m = mul(m, rotYr(mr.rotateAngleY));
        m = mul(m, rotX(mr.rotateAngleX));
        return m;
    }

    static float[] mul(float[] a, float[] b) {
        float[] o = new float[16];
        for (int r = 0; r < 4; r++)
            for (int c = 0; c < 4; c++)
                o[r * 4 + c] = a[r * 4] * b[c] + a[r * 4 + 1] * b[4 + c] + a[r * 4 + 2] * b[8 + c] + a[r * 4 + 3] * b[12 + c];
        return o;
    }

    static float[] apply(float[] m, float x, float y, float z) {
        return new float[]{m[0] * x + m[1] * y + m[2] * z + m[3], m[4] * x + m[5] * y + m[6] * z + m[7], m[8] * x + m[9] * y + m[10] * z + m[11]};
    }

    static float len(float a, float b, float c) {
        return (float) Math.sqrt(a * a + b * b + c * c);
    }

    private static float lerpAngle(float a, float b, float t) {
        return a + MathHelper.wrapDegrees(b - a) * t;
    }
}
