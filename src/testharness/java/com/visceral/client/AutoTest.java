package com.visceral.client;

import com.visceral.Visceral;
import com.visceral.VisceralConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.entity.RenderLiving;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameType;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.client.registry.RenderingRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.registry.EntityEntry;
import net.minecraftforge.fml.common.registry.EntityEntryBuilder;

import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * The scripted in-game test, run by GitHub with no screen attached: a flat creative world, a small
 * stone room with a ceiling, then each test creature in turn is spawned in front of you, hit, has a
 * limb torn off, and is killed, with screenshots at each step and a report of what Visceral saw.
 * Only in test builds (see addon.gradle); the game closes itself when done.
 */
public final class AutoTest {
    private AutoTest() {}

    private static final String[] MOBS = {
            "minecraft:zombie", "minecraft:skeleton", "minecraft:creeper", "minecraft:spider", "minecraft:slime",
            "minecraft:pig", "minecraft:chicken", "minecraft:villager", "minecraft:villager_golem", "minecraft:blaze",
            "minecraft:enderman", "minecraft:wolf", "minecraft:sheep", "minecraft:cow", "minecraft:magma_cube",
            "visceral:test_nested", "visceral:test_custom"};

    private static final class Step {
        final int at;
        final Runnable r;
        Step(int at, Runnable r) { this.at = at; this.r = r; }
    }

    private static final List<Step> STEPS = new ArrayList<>();
    private static final List<String> REPORT = new ArrayList<>();
    private static int tick, phase, baseY, mobId = -1;
    private static boolean launched;
    private static File out;

    public static void start() {
        MinecraftForge.EVENT_BUS.register(AutoTest.class);
        RenderingRegistry.registerEntityRenderingHandler(TestNested.class, m -> new RenderNested(m));
        RenderingRegistry.registerEntityRenderingHandler(TestCustom.class, m -> new RenderCustom(m));
        Visceral.LOG.info("[autotest] armed");
    }

    @SubscribeEvent
    public static void onRegisterEntities(RegistryEvent.Register<EntityEntry> e) {
        e.getRegistry().register(EntityEntryBuilder.create().entity(TestNested.class)
                .id(new ResourceLocation(Visceral.MODID, "test_nested"), 900).name("visceral.test_nested")
                .tracker(64, 3, true).build());
        e.getRegistry().register(EntityEntryBuilder.create().entity(TestCustom.class)
                .id(new ResourceLocation(Visceral.MODID, "test_custom"), 901).name("visceral.test_custom")
                .tracker(64, 3, true).build());
    }

    // ------------------------------------------------------------------ the script

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        mc.gameSettings.pauseOnLostFocus = false;
        try {
            if (!launched) {
                if (mc.currentScreen instanceof GuiMainMenu) {
                    launched = true;
                    out = new File(mc.gameDir, "visceral-test");
                    out.mkdirs();
                    mc.getSaveLoader().deleteWorldDirectory("visceraltest");
                    WorldSettings ws = new WorldSettings(1234L, GameType.CREATIVE, false, false, WorldType.FLAT);
                    ws.enableCommands();
                    mc.launchIntegratedServer("visceraltest", "visceraltest", ws);
                }
                return;
            }
            if (mc.world == null || mc.player == null || mc.getIntegratedServer() == null) return;
            if (mc.currentScreen != null && !(mc.currentScreen instanceof GuiVisceral)) mc.displayGuiScreen(null);
            tick++;
            if (phase == 0 && tick > 60) { phase = 1; plan(mc); }
            while (!STEPS.isEmpty() && STEPS.get(0).at <= tick) STEPS.remove(0).r.run();
        } catch (Throwable t) {
            note("TEST ERROR: " + t);
            Visceral.LOG.error("[autotest] failed", t);
            finish(mc);
        }
    }

    private static void at(int t, Runnable r) {
        int i = 0;
        while (i < STEPS.size() && STEPS.get(i).at <= t) i++;
        STEPS.add(i, new Step(t, r));
    }

    private static void server(Runnable r) {
        MinecraftServer s = Minecraft.getMinecraft().getIntegratedServer();
        s.addScheduledTask(r);
    }

    private static WorldServer sworld() {
        return Minecraft.getMinecraft().getIntegratedServer().getWorld(0);
    }

    private static EntityPlayerMP splayer() {
        return Minecraft.getMinecraft().getIntegratedServer().getPlayerList().getPlayers().get(0);
    }

    private static void plan(Minecraft mc) {
        // the test makes things happen on purpose: limbs come off on the heavy hit, every death bursts
        VisceralConfig.gibs.limbLossChance = 1.0;
        VisceralConfig.gibs.limbLossDamage = 0.25;
        VisceralConfig.gibs.gibChance = 1.0;
        VisceralConfig.extras.screenBlood = false;   // screenshots of the world, not of blood on the lens
        mc.gameSettings.hideGUI = true;
        mc.gameSettings.chatVisibility = net.minecraft.entity.player.EntityPlayer.EnumChatVisibility.HIDDEN;
        mc.gameSettings.tutorialStep = net.minecraft.client.tutorial.TutorialSteps.NONE;
        mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
        int t = tick;
        at(t, () -> server(() -> {
            MinecraftServer s = Minecraft.getMinecraft().getIntegratedServer();
            for (String c : new String[]{"gamerule doMobSpawning false", "gamerule doDaylightCycle false", "time set 6000",
                    "weather clear", "kill @e[type=!player]"}) {
                s.getCommandManager().executeCommand(s, c);
            }
            WorldServer w = sworld();
            baseY = 4;   // a default flat world: bedrock, dirt, dirt, grass, so you stand at y=4
            // a stone room: back wall, side walls, a ceiling over the arena, lit
            for (int x = -4; x <= 4; x++) {
                for (int y = 0; y < 4; y++) {
                    w.setBlockState(new BlockPos(x, baseY + y, 5), Blocks.STONE.getDefaultState());
                }
                for (int z = 1; z <= 5; z++) w.setBlockState(new BlockPos(x, baseY + 3, z), Blocks.COBBLESTONE.getDefaultState());
            }
            for (int z = 0; z <= 5; z++) {
                for (int y = 0; y < 3; y++) {
                    w.setBlockState(new BlockPos(-4, baseY + y, z), Blocks.STONE.getDefaultState());
                    w.setBlockState(new BlockPos(4, baseY + y, z), Blocks.STONE.getDefaultState());
                }
            }
            w.setBlockState(new BlockPos(-3, baseY + 3, 3), Blocks.GLOWSTONE.getDefaultState());
            w.setBlockState(new BlockPos(3, baseY + 3, 3), Blocks.GLOWSTONE.getDefaultState());
            EntityPlayerMP p = splayer();
            p.inventory.currentItem = 0;   // bare hands: small hits, so the limb step comes before the kill
            p.connection.setPlayerLocation(0.5, baseY, -0.2, 0f, 8f);
        }));
        t += 40;
        for (int i = 0; i < MOBS.length; i++) {
            final int idx = i;
            final String id = MOBS[i];
            final String tag = String.format("%02d_%s", i + 1, id.substring(id.indexOf(':') + 1));
            at(t, () -> { spawn(id); Tracker.testStyle = idx % 3; });
            at(t + 20, () -> { inspect(tag); shot(tag + "_0_standing"); });
            at(t + 25, AutoTest::attack);
            at(t + 28, () -> shot(tag + "_1_hit"));
            at(t + 45, AutoTest::attack);
            at(t + 70, () -> hurt(0.4f));
            at(t + 73, () -> shot(tag + "_2_limb"));
            at(t + 90, () -> shot(tag + "_3_limb_later"));
            at(t + 100, () -> { attack(); hurt(100f); });
            at(t + 103, () -> shot(tag + "_4_burst"));
            at(t + 112, () -> shot(tag + "_5_burst_later"));
            at(t + 150, () -> { shot(tag + "_6_after"); counts(tag); });
            at(t + 155, () -> server(() -> {
                Entity e = sworld().getEntityByID(mobId);
                if (e != null) e.setDead();
            }));
            t += 165;
        }
        // round 2: what hurt it changes how it bleeds and dies
        t = special(t);
        // an overview of the room and all the blood, from further back, then a later one once it's dried a bit
        at(t, () -> server(() -> splayer().connection.setPlayerLocation(0.5, baseY + 1, -3.5, 0f, 18f)));
        t += 120;   // (a few seconds for flies to find the old gore)
        at(t + 20, () -> shot("99_overview"));
        at(t + 25, () -> server(() -> splayer().connection.setPlayerLocation(-2.5, baseY + 1, -1.5, -30f, 25f)));
        at(t + 45, () -> shot("99_overview_side"));
        // your weapon after a fight, then the settings screen
        at(t + 46, () -> server(() -> {
            EntityPlayerMP p = splayer();
            p.inventory.setInventorySlotContents(0, new net.minecraft.item.ItemStack(net.minecraft.init.Items.IRON_SWORD));
            p.connection.setPlayerLocation(0.5, baseY, 0.5, 0f, 10f);
        }));
        at(t + 50, () -> {
            for (int i = 0; i < 8; i++) HeldBlood.hit(0x8A0B0B);
            Minecraft m = Minecraft.getMinecraft();
            m.gameSettings.hideGUI = false;
        });
        at(t + 54, () -> shot("96_bloody_weapon"));
        at(t + 56, () -> Minecraft.getMinecraft().displayGuiScreen(new GuiVisceral(null)));
        at(t + 60, () -> shot("97_settings"));
        at(t + 64, () -> {
            Minecraft m = Minecraft.getMinecraft();
            m.gameSettings.showDebugInfo = true;
        });
        at(t + 68, () -> shot("98_f3"));
        at(t + 72, () -> finish(Minecraft.getMinecraft()));
    }

    private static int special(int t) {
        // an arrow through a zombie (it bleeds where the arrow went in, and sprays out the far side)
        at(t, () -> { spawn("minecraft:zombie"); Tracker.testStyle = 0; });
        at(t + 20, () -> server(() -> {
            WorldServer w = sworld();
            net.minecraft.entity.projectile.EntityTippedArrow arrow = new net.minecraft.entity.projectile.EntityTippedArrow(w, splayer());
            arrow.setPosition(0.5, baseY + 1.3, 0.3);
            arrow.shoot(0, -0.05, 1, 2.6f, 0f);
            arrow.setDamage(3.0);
            w.spawnEntity(arrow);
        }));
        at(t + 24, () -> shot("50_arrow_hit"));
        at(t + 40, () -> shot("50_arrow_later"));
        at(t + 45, () -> server(() -> { Entity e = sworld().getEntityByID(mobId); if (e != null) e.setDead(); }));
        t += 55;
        // an explosion beside a pig: it always bursts, thrown hard and high
        at(t, () -> spawn("minecraft:pig"));
        at(t + 20, () -> server(() -> sworld().createExplosion(null, 1.6, baseY + 0.3, 2.9, 2.5f, false)));
        at(t + 23, () -> shot("51_explosion"));
        at(t + 32, () -> shot("51_explosion_later"));
        t += 50;
        // a cow burnt to death: blackened gibs and smoke, no blood
        at(t, () -> spawn("minecraft:cow"));
        at(t + 15, () -> server(() -> { Entity e = sworld().getEntityByID(mobId); if (e != null) e.setFire(10); }));
        at(t + 20, () -> server(() -> {
            Entity e = sworld().getEntityByID(mobId);
            if (e instanceof EntityLivingBase) { ((EntityLivingBase) e).hurtResistantTime = 0; e.attackEntityFrom(DamageSource.ON_FIRE, 1000f); }
        }));
        at(t + 24, () -> shot("52_fire_death"));
        at(t + 40, () -> shot("52_fire_later"));
        t += 55;
        // a villager standing in water: its blood clouds the water, its parts float
        at(t, () -> server(() -> {
            WorldServer w = sworld();
            for (int x = -1; x <= 2; x++) for (int z = 2; z <= 4; z++) {
                w.setBlockState(new BlockPos(x, baseY - 1, z), Blocks.WATER.getDefaultState());
                w.setBlockState(new BlockPos(x, baseY - 2, z), Blocks.WATER.getDefaultState());
            }
        }));
        at(t + 5, () -> spawn("minecraft:villager"));
        at(t + 25, AutoTest::attack);
        at(t + 30, () -> hurt(0.4f));
        at(t + 36, () -> shot("53_water_hit"));
        at(t + 45, () -> { attack(); hurt(100f); Tracker.testStyle = 0; });
        at(t + 52, () -> shot("53_water_burst"));
        at(t + 75, () -> shot("53_water_later"));
        at(t + 80, () -> server(() -> {
            WorldServer w = sworld();
            for (int x = -1; x <= 2; x++) for (int z = 2; z <= 4; z++) {
                w.setBlockState(new BlockPos(x, baseY - 1, z), Blocks.DIRT.getDefaultState());
                w.setBlockState(new BlockPos(x, baseY - 2, z), Blocks.DIRT.getDefaultState());
            }
        }));
        t += 90;
        // a pool to walk through, then a walk across the room: footprints, kicked gibs, flies
        at(t, () -> { spawn("minecraft:pig"); Tracker.testStyle = 2; });
        at(t + 20, () -> hurt(100f));
        at(t + 80, () -> server(() -> splayer().connection.setPlayerLocation(0.5, baseY, 1.6, 0f, 55f)));
        at(t + 90, () -> walk(true));
        at(t + 112, () -> walk(false));
        at(t + 114, () -> server(() -> {
            EntityPlayerMP p = splayer();
            p.connection.setPlayerLocation(p.posX, p.posY, p.posZ, 180f, 60f);
        }));
        at(t + 122, () -> shot("54_footprints"));
        t += 130;
        // burning to death slowly, as in daylight: a zombie on 2 health, set alight, left to burn
        // (with "only your kills": it isn't yours, so it shouldn't burst; it should crumble to ash)
        at(t, () -> { spawn("minecraft:zombie"); Tracker.testStyle = -1; VisceralConfig.gibs.onlyYourKills = true; });
        at(t + 15, () -> server(() -> {
            Entity e = sworld().getEntityByID(mobId);
            if (e instanceof EntityLivingBase) { ((EntityLivingBase) e).setHealth(2f); e.setFire(20); }
        }));
        for (int k = 20; k <= 100; k += 20) { final int kk = k; at(t + 15 + k, () -> shot("56_sunburn_" + kk)); }
        at(t + 118, () -> note("sunburn: " + Tracker.lastDeath));
        at(t + 120, () -> server(() -> { Entity e = sworld().getEntityByID(mobId); if (e != null) e.setDead(); }));
        t += 125;
        // a Fire Aspect sword: the hit sets it alight, and it dies of the hit or of the burn after
        at(t, () -> {
            spawn("minecraft:pig");
            server(() -> {
                net.minecraft.item.ItemStack sword = new net.minecraft.item.ItemStack(net.minecraft.init.Items.DIAMOND_SWORD);
                sword.addEnchantment(net.minecraft.init.Enchantments.FIRE_ASPECT, 2);
                splayer().inventory.setInventorySlotContents(1, sword);
                splayer().inventory.currentItem = 1;
            });
        });
        at(t + 5, () -> Minecraft.getMinecraft().player.inventory.currentItem = 1);
        at(t + 15, () -> server(() -> {
            Entity e = sworld().getEntityByID(mobId);
            if (e instanceof EntityLivingBase) ((EntityLivingBase) e).setHealth(9f);
        }));
        at(t + 20, AutoTest::attack);
        for (int k = 5; k <= 60; k += 11) { final int kk = k; at(t + 20 + k, () -> shot("57_fire_aspect_" + kk)); }
        at(t + 85, () -> note("fire aspect: " + Tracker.lastDeath));
        at(t + 88, () -> server(() -> {
            Entity e = sworld().getEntityByID(mobId); if (e != null) e.setDead();
            splayer().inventory.currentItem = 0;
        }));
        at(t + 89, () -> VisceralConfig.gibs.onlyYourKills = false);
        at(t + 90, () -> Minecraft.getMinecraft().player.inventory.currentItem = 0);
        t += 95;
        t = round5(t);
        return t;
    }

    private static void give(String id, net.minecraft.enchantment.Enchantment ench) {
        server(() -> {
            net.minecraft.item.Item item = net.minecraft.item.Item.getByNameOrId(id);
            net.minecraft.item.ItemStack st = new net.minecraft.item.ItemStack(item);
            if (ench != null) st.addEnchantment(ench, 1);
            splayer().inventory.setInventorySlotContents(1, st);
            splayer().inventory.currentItem = 1;
        });
    }

    private static void health(float hp) {
        server(() -> {
            Entity e = sworld().getEntityByID(mobId);
            if (e instanceof EntityLivingBase) ((EntityLivingBase) e).setHealth(hp);
        });
    }

    private static void clearMob() {
        server(() -> { Entity e = sworld().getEntityByID(mobId); if (e != null) e.setDead(); });
    }

    private static void report(String what) {
        note(what + ": " + Tracker.lastDeath);
    }

    /** Weapons, crits, sweeps, headshots, pets, rain. */
    private static int round5(int t) {
        Tracker.testStyle = -1;
        // from here the server's word comes by network, the way a friend on your LAN world gets it
        at(t, () -> Exact.viaNetwork = true);
        // a sweep: two zombies side by side, a sword swing at one; the sweep kills the other (cut in half)
        at(t, () -> spawn("minecraft:zombie"));
        at(t + 2, () -> server(() -> {
            WorldServer w = sworld();
            Entity e = EntityList.createEntityByIDFromName(new ResourceLocation("minecraft:zombie"), w);
            e.setLocationAndAngles(1.5, baseY, 2.4, 180f, 0f);
            ((EntityLiving) e).setNoAI(true);
            ((EntityLivingBase) e).setHealth(0.3f);   // a sweep does 1 damage, less after a zombie's armour
            w.spawnEntity(e);
            side = e.getEntityId();
        }));
        at(t + 3, () -> give("minecraft:iron_sword", null));
        at(t + 6, () -> Minecraft.getMinecraft().player.inventory.currentItem = 1);
        at(t + 15, () -> health(20f));
        at(t + 20, AutoTest::attack);
        at(t + 24, () -> shot("60_sweep"));
        at(t + 40, () -> shot("60_sweep_later"));
        at(t + 45, () -> report("sweep"));
        at(t + 48, () -> server(() -> {
            Entity e = sworld().getEntityByID(side); if (e != null) e.setDead();
        }));
        at(t + 49, AutoTest::clearMob);
        t += 55;
        // a critical with an axe: jump, hit on the way down
        at(t, () -> spawn("minecraft:pig"));
        at(t + 3, () -> give("minecraft:iron_axe", null));
        at(t + 6, () -> Minecraft.getMinecraft().player.inventory.currentItem = 1);
        at(t + 15, () -> health(3f));
        at(t + 20, () -> Minecraft.getMinecraft().player.jump());
        at(t + 27, AutoTest::attack);
        at(t + 30, () -> shot("61_axe_crit"));
        at(t + 45, () -> shot("61_axe_crit_later"));
        at(t + 50, () -> report("axe crit"));
        at(t + 52, AutoTest::clearMob);
        t += 58;
        // a hammer (a pickaxe counts): crushed
        at(t, () -> spawn("minecraft:villager"));
        at(t + 3, () -> give("minecraft:iron_pickaxe", null));
        at(t + 6, () -> Minecraft.getMinecraft().player.inventory.currentItem = 1);
        at(t + 15, () -> health(2f));
        at(t + 20, AutoTest::attack);
        at(t + 23, () -> shot("62_blunt"));
        at(t + 40, () -> shot("62_blunt_later"));
        at(t + 45, () -> report("blunt"));
        at(t + 47, AutoTest::clearMob);
        t += 52;
        // forced styles, to see them: a head popped, and cut in half, with an axe
        for (int st : new int[]{3, 4}) {
            final int style = st;
            final String mob = st == 3 ? "minecraft:zombie" : "minecraft:cow";
            at(t, () -> { spawn(mob); Tracker.testStyle = style; });
            at(t + 15, () -> health(2f));
            at(t + 20, AutoTest::attack);
            at(t + 23, () -> shot("63_style" + style));
            at(t + 40, () -> shot("63_style" + style + "_later"));
            at(t + 45, () -> { report("style " + style); Tracker.testStyle = -1; });
            at(t + 47, AutoTest::clearMob);
            t += 52;
        }
        at(t, () -> server(() -> splayer().inventory.currentItem = 0));
        at(t + 1, () -> Minecraft.getMinecraft().player.inventory.currentItem = 0);
        // a headshot: an arrow of yours through a zombie's eyes
        at(t + 2, () -> spawn("minecraft:zombie"));
        at(t + 17, () -> health(2f));
        at(t + 20, () -> server(() -> {
            WorldServer w = sworld();
            EntityLivingBase z = (EntityLivingBase) w.getEntityByID(mobId);
            net.minecraft.entity.projectile.EntityTippedArrow arrow = new net.minecraft.entity.projectile.EntityTippedArrow(w, splayer());
            arrow.setPosition(0.5, z.posY + z.getEyeHeight(), 0.6);
            arrow.shoot(0, 0, 1, 2.6f, 0f);
            arrow.setDamage(3.0);
            w.spawnEntity(arrow);
        }));
        at(t + 24, () -> shot("64_headshot"));
        at(t + 40, () -> shot("64_headshot_later"));
        at(t + 45, () -> report("headshot"));
        at(t + 47, AutoTest::clearMob);
        t += 52;
        // your wolf's kill counts as yours ("only your kills" on)
        at(t, () -> { spawn("minecraft:pig"); VisceralConfig.gibs.onlyYourKills = true; });
        at(t + 3, () -> server(() -> {
            WorldServer w = sworld();
            net.minecraft.entity.passive.EntityWolf wolf = new net.minecraft.entity.passive.EntityWolf(w);
            wolf.setLocationAndAngles(0.5, baseY, 1.2, 0f, 0f);
            wolf.setTamedBy(splayer());
            w.spawnEntity(wolf);
            side = wolf.getEntityId();
        }));
        at(t + 10, () -> health(1f));
        at(t + 12, () -> server(() -> {
            Entity wolf = sworld().getEntityByID(side), pig = sworld().getEntityByID(mobId);
            if (wolf instanceof EntityLiving && pig instanceof EntityLivingBase) {
                ((EntityLiving) wolf).setAttackTarget((EntityLivingBase) pig);
                ((EntityLiving) wolf).attackEntityAsMob(pig);
            }
        }));
        at(t + 16, () -> shot("65_pet_kill"));
        at(t + 40, () -> { report("pet"); VisceralConfig.gibs.onlyYourKills = false; });
        at(t + 42, () -> server(() -> {
            Entity e = sworld().getEntityByID(side); if (e != null) e.setDead();
        }));
        at(t + 43, AutoTest::clearMob);
        t += 48;
        // rain: blood out in the open washes away, blood under the roof stays
        at(t, () -> server(() -> splayer().connection.setPlayerLocation(0.5, baseY, -1.6, 0f, 50f)));
        at(t + 5, () -> {
            Blood.clearAll();
            BloodTypes.Type red = BloodTypes.byName("red");
            Blood.groundStain(red, 0.5, baseY + 0.1, 0.5, 1.2f, 0.1f, Blood.T_POOL);    // open sky
            Blood.groundStain(red, 0.5, baseY + 0.1, 1.9, 1.2f, 0.1f, Blood.T_POOL);    // under the roof
            note("rain: stains placed " + Blood.stainCount());
        });
        at(t + 15, () -> shot("66_rain_before"));
        at(t + 16, () -> server(() -> {
            MinecraftServer sv = Minecraft.getMinecraft().getIntegratedServer();
            sv.getCommandManager().executeCommand(sv, "weather rain");
        }));
        at(t + 300, () -> {
            World w = Minecraft.getMinecraft().world;
            BlockPos open = new BlockPos(0.5, baseY, 0.5), roof = new BlockPos(0.5, baseY, 1.9);
            note("rain at 300: raining " + w.isRaining() + ", open " + w.isRainingAt(open) + ", roofed " + w.isRainingAt(roof)
                    + ", stains " + Blood.stainCount());
        });
        at(t + 420, () -> { shot("66_rain_after"); note("rain: stains left " + Blood.stainCount()); });
        at(t + 425, () -> server(() -> {
            MinecraftServer sv = Minecraft.getMinecraft().getIntegratedServer();
            sv.getCommandManager().executeCommand(sv, "weather clear");
            splayer().connection.setPlayerLocation(0.5, baseY, -0.2, 0f, 8f);
        }));
        return t + 440;
    }

    /** Hold (or let go of) the walk-forward key, so you walk for real. */
    private static int side;

    private static void walk(boolean on) {
        Minecraft mc = Minecraft.getMinecraft();
        net.minecraft.client.settings.KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), on);
    }

    private static void spawn(String id) {
        server(() -> {
            WorldServer w = sworld();
            Entity e = EntityList.createEntityByIDFromName(new ResourceLocation(id), w);
            if (e == null) { note(id + ": could not create"); return; }
            e.setLocationAndAngles(0.5, baseY, 2.4, 180f, 0f);
            if (e instanceof EntityLiving) ((EntityLiving) e).setNoAI(true);
            if (e instanceof EntityLivingBase) {
                ((EntityLivingBase) e).rotationYawHead = 180f;
                ((EntityLivingBase) e).renderYawOffset = 180f;
            }
            w.spawnEntity(e);
            mobId = e.getEntityId();
            splayer().connection.setPlayerLocation(0.5, baseY, -0.2, 0f, 8f);
        });
    }

    private static EntityLivingBase target() {
        Entity e = Minecraft.getMinecraft().world.getEntityByID(mobId);
        return e instanceof EntityLivingBase ? (EntityLivingBase) e : null;
    }

    /** A real attack from you: aimed at its middle, through the game's own attack (as a click would). */
    private static void attack() {
        Minecraft mc = Minecraft.getMinecraft();
        EntityLivingBase e = target();
        if (e == null) return;
        double dx = e.posX - mc.player.posX, dz = e.posZ - mc.player.posZ;
        double dy = (e.posY + e.height * 0.55) - (mc.player.posY + mc.player.getEyeHeight());
        mc.player.rotationYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        mc.player.rotationPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        mc.player.resetCooldown();
        mc.playerController.attackEntity(mc.player, e);
        mc.player.swingArm(EnumHand.MAIN_HAND);
    }

    /** A blow worth `share` of its max health (100 = certain death), dealt by you on the server. */
    private static void hurt(float share) {
        server(() -> {
            Entity e = sworld().getEntityByID(mobId);
            if (!(e instanceof EntityLivingBase)) return;
            EntityLivingBase l = (EntityLivingBase) e;
            l.hurtResistantTime = 0;
            float dmg = share >= 50f ? 1000f : l.getMaxHealth() * share;
            l.attackEntityFrom(DamageSource.causePlayerDamage(splayer()), dmg);
        });
    }

    private static final java.util.List<String> SHOTS = new java.util.ArrayList<>();

    /** Queues a screenshot: it's taken right after the next frame is drawn (GUI included), not of an older frame. */
    private static void shot(String name) {
        SHOTS.add(name);
    }

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.END || SHOTS.isEmpty()) return;
        Minecraft mc = Minecraft.getMinecraft();
        boolean close = false;
        for (String name : SHOTS) {
            ScreenShotHelper.saveScreenshot(out, name + ".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            if (name.equals("97_settings")) close = true;
        }
        if (close) mc.displayGuiScreen(null);
        SHOTS.clear();
    }

    /** What Visceral makes of this creature: its blood and the body parts it found. */
    private static void inspect(String tag) {
        EntityLivingBase e = target();
        if (e == null) { note(tag + ": not on the client"); return; }
        StringBuilder b = new StringBuilder(tag).append(": blood=").append(BloodTypes.of(e).name);
        try {
            ModelParts.Capture c = ModelParts.capture(e, 1f);
            if (c == null) b.append(" parts=NONE (chunks only)");
            else {
                b.append(" parts=").append(c.groups.size()).append(" [");
                for (ModelParts.Group g : c.groups) {
                    b.append(g.head ? "H" : g.core ? "B" : g.limb ? "L" : "o").append(g.path.isEmpty() ? "" : "^").append(' ');
                }
                b.append("]");
            }
        } catch (Throwable t) {
            b.append(" CAPTURE ERROR ").append(t);
        }
        note(b.toString());
    }

    private static void counts(String tag) {
        note(tag + ":   after death -> gibs=" + Gibs.count() + " droplets=" + Blood.dropCount() + " stains=" + Blood.stainCount()
                + String.format(" drawing=%.2fms", Guard.frameMs()));
    }

    private static void note(String s) {
        REPORT.add(s);
        Visceral.LOG.info("[autotest] " + s);
    }

    private static void finish(Minecraft mc) {
        STEPS.clear();
        try (PrintWriter w = new PrintWriter(new File(out == null ? new File(mc.gameDir, "visceral-test") : out, "report.txt"), "UTF-8")) {
            for (String s : REPORT) w.println(s);
        } catch (Exception ex) {
            Visceral.LOG.error("[autotest] couldn't write the report", ex);
        }
        Visceral.LOG.info("[autotest] done");
        mc.shutdown();
    }

    // ------------------------------------------------------------------ a creature with a nested model

    /** A zombie drawn with a model the way many mods build theirs: every part hanging off one pivot. */
    public static class TestNested extends EntityZombie {
        public TestNested(World w) { super(w); }
    }

    static final class ModelNested extends ModelBase {
        final ModelRenderer root, body, head, jaw, armR, foreR, armL, foreL, legR, shinR, legL, shinL;

        ModelNested() {
            textureWidth = 64; textureHeight = 64;
            root = new ModelRenderer(this, 0, 0); root.setRotationPoint(0f, 24f, 0f);
            body = part(16, 16, -4f, -12f, -2f, 8, 12, 4, 0f, -12f, 0f); root.addChild(body);
            head = part(0, 0, -4f, -8f, -4f, 8, 8, 8, 0f, -12f, 0f); body.addChild(head);
            jaw = part(32, 0, -2f, -1f, -5f, 4, 2, 2, 0f, -1f, 0f); head.addChild(jaw);
            armR = part(40, 16, -3f, -2f, -2f, 4, 6, 4, -5f, -10f, 0f); body.addChild(armR);
            foreR = part(40, 26, -2f, 0f, -2f, 4, 6, 4, -1f, 4f, 0f); armR.addChild(foreR);
            armL = part(40, 16, -1f, -2f, -2f, 4, 6, 4, 5f, -10f, 0f); body.addChild(armL);
            foreL = part(40, 26, -2f, 0f, -2f, 4, 6, 4, 1f, 4f, 0f); armL.addChild(foreL);
            legR = part(0, 16, -2f, 0f, -2f, 4, 6, 4, -2f, 0f, 0f); body.addChild(legR);
            shinR = part(0, 26, -2f, 0f, -2f, 4, 6, 4, 0f, 6f, 0f); legR.addChild(shinR);
            legL = part(0, 16, -2f, 0f, -2f, 4, 6, 4, 2f, 0f, 0f); body.addChild(legL);
            shinL = part(0, 26, -2f, 0f, -2f, 4, 6, 4, 0f, 6f, 0f); legL.addChild(shinL);
        }

        private ModelRenderer part(int u, int v, float x, float y, float z, int w, int h, int d, float px, float py, float pz) {
            ModelRenderer m = new ModelRenderer(this, u, v);
            m.addBox(x, y, z, w, h, d);
            m.setRotationPoint(px, py, pz);
            return m;
        }

        @Override
        public void render(Entity e, float ls, float lsa, float age, float yaw, float pitch, float scale) {
            setRotationAngles(ls, lsa, age, yaw, pitch, scale, e);
            root.render(scale);
        }

        @Override
        public void setRotationAngles(float ls, float lsa, float age, float yaw, float pitch, float scale, Entity e) {
            head.rotateAngleY = yaw * 0.017453292f;
            head.rotateAngleX = pitch * 0.017453292f;
            float sw = (float) Math.cos(ls * 0.6662f) * 1.2f * lsa;
            armR.rotateAngleX = sw; armL.rotateAngleX = -sw;
            legR.rotateAngleX = -sw; legL.rotateAngleX = sw;
        }
    }

    /** A creature drawn by its own renderer, not from model parts (like GeckoLib or OBJ creatures). */
    public static class TestCustom extends EntityZombie {
        public TestCustom(World w) { super(w); }
    }

    static final class RenderCustom extends net.minecraft.client.renderer.entity.Render<TestCustom> {
        private static final ResourceLocation TEX = new ResourceLocation("textures/entity/creeper/creeper.png");

        RenderCustom(RenderManager m) { super(m); }

        @Override
        public void doRender(TestCustom e, double x, double y, double z, float yaw, float pt) {
            // a plain green block where the creature is, drawn by hand
            net.minecraft.client.renderer.GlStateManager.pushMatrix();
            net.minecraft.client.renderer.GlStateManager.translate(x, y + 0.9, z);
            net.minecraft.client.renderer.GlStateManager.disableTexture2D();
            net.minecraft.client.renderer.GlStateManager.color(0.3f, 0.75f, 0.3f, 1f);
            net.minecraft.client.renderer.Tessellator t = net.minecraft.client.renderer.Tessellator.getInstance();
            net.minecraft.client.renderer.BufferBuilder b = t.getBuffer();
            b.begin(7, net.minecraft.client.renderer.vertex.DefaultVertexFormats.POSITION);
            float h = 0.4f;
            float[][] f = {{-h,-h,-h},{h,-h,-h},{h,h,-h},{-h,h,-h},{-h,-h,h},{-h,h,h},{h,h,h},{h,-h,h},{-h,-h,-h},{-h,h,-h},{-h,h,h},{-h,-h,h},
                    {h,-h,-h},{h,-h,h},{h,h,h},{h,h,-h},{-h,h,-h},{h,h,-h},{h,h,h},{-h,h,h},{-h,-h,-h},{-h,-h,h},{h,-h,h},{h,-h,-h}};
            for (float[] v : f) b.pos(v[0], v[1] * 2.2f, v[2]).endVertex();
            t.draw();
            net.minecraft.client.renderer.GlStateManager.color(1f, 1f, 1f, 1f);
            net.minecraft.client.renderer.GlStateManager.enableTexture2D();
            net.minecraft.client.renderer.GlStateManager.popMatrix();
        }

        @Override
        protected ResourceLocation getEntityTexture(TestCustom e) { return TEX; }
    }

    static final class RenderNested extends RenderLiving<TestNested> {
        private static final ResourceLocation TEX = new ResourceLocation("textures/entity/zombie/zombie.png");

        RenderNested(RenderManager m) { super(m, new ModelNested(), 0.5f); }

        @Override
        protected ResourceLocation getEntityTexture(TestNested e) { return TEX; }
    }
}
