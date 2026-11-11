package com.visceral;

import com.visceral.client.BloodTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;

/**
 * Visceral — gore for every creature, modded ones included: blood sprays and stains, creatures
 * bursting into their own body parts, limbs torn off, bleeding and blood pools.
 * Purely client-side: it watches what the game already tells your client (a creature got hurt, it
 * died, its health), so it works on any server and needs nothing from other mods.
 */
@Mod(
        modid = Visceral.MODID,
        name = Visceral.NAME,
        version = Visceral.VERSION,
        clientSideOnly = true,
        acceptedMinecraftVersions = "[1.12.2]"
)
public class Visceral {
    public static final String MODID = "visceral";
    public static final String NAME = "Visceral";
    public static final String VERSION = "0.2.0";
    public static final Logger LOG = LogManager.getLogger(MODID);

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        BloodTypes.init(new File(event.getModConfigurationDirectory(), "visceral-blood.json"));
        VisceralConfig.applyPreset();
        // F3+T reloads the blood list, and Visceral's textures come back with the rest
        ((IReloadableResourceManager) Minecraft.getMinecraft().getResourceManager())
                .registerReloadListener((IResourceManagerReloadListener) rm -> { BloodTypes.reload(); com.visceral.client.Wounds.reload(); });
        // the scripted in-game test (only in test builds, see addon.gradle)
        if (Boolean.getBoolean("visceral.autotest")) {
            try {
                Class.forName("com.visceral.client.AutoTest").getMethod("start").invoke(null);
            } catch (ReflectiveOperationException e) {
                LOG.error("Visceral test requested but not in this build", e);
            }
        }
    }
}
