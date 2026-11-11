package com.visceral;

import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Saved to config/visceral.cfg. Editable in game via Mods -> Visceral -> Config; changes apply at once.
 * Which creature bleeds what is in config/visceral-blood.json instead (see BloodTypes).
 */
@Config(modid = Visceral.MODID)
public class VisceralConfig {

    @Config.Comment("Master switch. Off: no gore at all, every creature looks and dies like vanilla.")
    public static boolean enabled = true;

    @Config.Comment("Blood: sprays, droplets and the stains they leave.")
    public static final Blood blood = new Blood();

    @Config.Comment("Gibs: creatures bursting into their own body parts, and losing limbs.")
    public static final Gibs gibs = new Gibs();

    @Config.Comment("Bleeding, blood pools, blood on your screen and sounds.")
    public static final Extras extras = new Extras();

    public static class Blood {
        @Config.Comment("Blood on hits.")
        public boolean blood = true;

        @Config.Comment("How much blood: 1 = normal, 2 = twice as many droplets, 0.3 = a light touch.")
        @Config.RangeDouble(min = 0.0, max = 5.0)
        public double amount = 1.0;

        @Config.Comment("Droplets that land leave stains on blocks (floors, walls, ceilings).")
        public boolean stains = true;

        @Config.Comment("Seconds a stain stays before it fades away.")
        @Config.RangeInt(min = 3, max = 3600)
        public int stainSeconds = 90;

        @Config.Comment("Most stains at once; the oldest fade first when there are more.")
        @Config.RangeInt(min = 10, max = 4000)
        public int maxStains = 900;

        @Config.Comment("Most droplets in the air at once.")
        @Config.RangeInt(min = 50, max = 5000)
        public int maxDroplets = 1200;

        @Config.Comment("Only creatures within this many blocks of you bleed (performance).")
        @Config.RangeInt(min = 8, max = 128)
        public int range = 48;
    }

    public static class Gibs {
        @Config.Comment("Creatures can burst into gibs when they die: their real body parts (head, arms, legs...) fly off.")
        public boolean deathGibs = true;

        @Config.Comment("Chance (0-1) that a death bursts into gibs. The rest die normally (and leave a blood pool).")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double gibChance = 1.0;

        @Config.Comment("Only creatures you kill burst (others die normally).")
        public boolean onlyYourKills = false;

        @Config.Comment("Bits of meat (or bone, metal, slime...) fly out with the body parts.")
        public boolean chunks = true;

        @Config.Comment("How many chunks per burst, for a creature the size of a zombie.")
        @Config.RangeInt(min = 0, max = 60)
        public int chunkCount = 10;

        @Config.Comment("How hard the parts are thrown.")
        @Config.RangeDouble(min = 0.0, max = 4.0)
        public double force = 1.0;

        @Config.Comment("Seconds gibs lie around before they sink away.")
        @Config.RangeInt(min = 2, max = 600)
        public int gibSeconds = 25;

        @Config.Comment("Most gibs and chunks at once; the oldest sink first.")
        @Config.RangeInt(min = 10, max = 2000)
        public int maxGibs = 300;

        @Config.Comment("A heavy hit can tear a limb off a creature that survives it. It keeps fighting without it.")
        public boolean limbLoss = true;

        @Config.Comment("A limb can come off when one hit takes at least this much (0-1) of the creature's max health.")
        @Config.RangeDouble(min = 0.05, max = 1.0)
        public double limbLossDamage = 0.3;

        @Config.Comment("Chance (0-1) of losing a limb on such a hit.")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double limbLossChance = 0.4;

        @Config.Comment("Walking into gibs knocks them about.")
        public boolean kickable = true;

        @Config.Comment({"Not every death is a burst: some creatures lose their head (the neck pumping as they fall),",
                "some a limb, and fall with a pool spreading under them. Off: every gib death is a burst."})
        public boolean deathStyles = true;

        @Config.Comment("Players gib and lose limbs too (other players; never yourself in first person).")
        public boolean players = true;
    }

    public static class Extras {
        @Config.Comment("Badly hurt creatures drip blood as they move, leaving a trail.")
        public boolean bleeding = true;

        @Config.Comment("Creatures start dripping below this share (0-1) of their max health.")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double bleedBelow = 0.5;

        @Config.Comment("Walking through wet blood leaves bloody footprints (yours and creatures'), fainter with each step.")
        public boolean footprints = true;

        @Config.Comment("Flies gather and buzz over gore that's been lying around a while.")
        public boolean flies = true;

        @Config.Comment("Hurt creatures get bloody: blood soaks into their skin, fur and armour, more the more hurt they are.")
        public boolean wounds = true;

        @Config.Comment("Raw flesh and bone where a limb or head came off: on the creature and on the part that flew off.")
        public boolean stumps = true;

        @Config.Comment("A creature that dies without bursting leaves a pool of blood spreading under it.")
        public boolean bloodPools = true;

        @Config.Comment("Blood splatters on your screen when you're hurt, or when you kill something up close.")
        public boolean screenBlood = true;

        @Config.Comment("How strong the screen blood is.")
        @Config.RangeDouble(min = 0.0, max = 2.0)
        public double screenBloodStrength = 1.0;

        @Config.Comment("Squelches, cracks and splats.")
        public boolean sounds = true;

        @Config.Comment("Sound volume.")
        @Config.RangeDouble(min = 0.0, max = 2.0)
        public double volume = 1.0;
    }

    @Mod.EventBusSubscriber(modid = Visceral.MODID)
    public static class Sync {
        @SubscribeEvent
        public static void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
            if (event.getModID().equals(Visceral.MODID)) {
                ConfigManager.sync(Visceral.MODID, Config.Type.INSTANCE);
            }
        }
    }
}
