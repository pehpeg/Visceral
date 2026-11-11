package com.visceral.client;

import com.visceral.Visceral;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

/** A "Visceral..." button in the pause menu's top-right corner, opening the settings screen. */
@Mod.EventBusSubscriber(modid = Visceral.MODID, value = Side.CLIENT)
public final class PauseButton {
    private PauseButton() {}

    private static final int ID = 0x5EC1;

    @SubscribeEvent
    public static void onInit(GuiScreenEvent.InitGuiEvent.Post event) {
        if (event.getGui() instanceof GuiIngameMenu) {
            event.getButtonList().add(new GuiButton(ID, event.getGui().width - 84, 4, 80, 20, "Visceral..."));
        }
    }

    @SubscribeEvent
    public static void onAction(GuiScreenEvent.ActionPerformedEvent.Pre event) {
        if (event.getGui() instanceof GuiIngameMenu && event.getButton().id == ID) {
            Minecraft.getMinecraft().displayGuiScreen(new GuiVisceral(event.getGui()));
        }
    }
}
