package com.visceral.client;

import com.visceral.Visceral;
import com.visceral.VisceralConfig;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.config.GuiConfig;
import net.minecraftforge.fml.client.config.GuiSlider;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Visceral's settings screen: a preset row, an on/off switch for every feature, sliders for the
 * main amounts, and buttons to reload the blood list, clear all gore, or open every detailed
 * setting. Changes apply at once and are saved when you leave.
 * Opened from Mods -> Visceral -> Config, or the "Visceral..." button in the pause menu.
 */
public final class GuiVisceral extends GuiScreen {
    private final GuiScreen parent;
    private static final String[] COLOURS = {"", "#2E8B57", "#1A1A22", "#6A1FB0", "#C8B400", "#2060D0"};
    private static final String[] COLOUR_NAMES = {"natural", "green", "black", "purple", "yellow", "blue"};

    private static final class Toggle {
        final String label;
        final Supplier<Boolean> get;
        final Consumer<Boolean> set;
        Toggle(String label, Supplier<Boolean> get, Consumer<Boolean> set) { this.label = label; this.get = get; this.set = set; }
    }

    private final Toggle[] toggles = {
            new Toggle("Gore", () -> VisceralConfig.enabled, v -> VisceralConfig.enabled = v),
            new Toggle("Blood", () -> VisceralConfig.blood.blood, v -> VisceralConfig.blood.blood = v),
            new Toggle("Stains", () -> VisceralConfig.blood.stains, v -> VisceralConfig.blood.stains = v),
            new Toggle("Death gibs", () -> VisceralConfig.gibs.deathGibs, v -> VisceralConfig.gibs.deathGibs = v),
            new Toggle("Death styles", () -> VisceralConfig.gibs.deathStyles, v -> VisceralConfig.gibs.deathStyles = v),
            new Toggle("Limb loss", () -> VisceralConfig.gibs.limbLoss, v -> VisceralConfig.gibs.limbLoss = v),
            new Toggle("Chunks", () -> VisceralConfig.gibs.chunks, v -> VisceralConfig.gibs.chunks = v),
            new Toggle("Kickable gibs", () -> VisceralConfig.gibs.kickable, v -> VisceralConfig.gibs.kickable = v),
            new Toggle("Wounds", () -> VisceralConfig.extras.wounds, v -> VisceralConfig.extras.wounds = v),
            new Toggle("Stumps", () -> VisceralConfig.extras.stumps, v -> VisceralConfig.extras.stumps = v),
            new Toggle("Bleeding", () -> VisceralConfig.extras.bleeding, v -> VisceralConfig.extras.bleeding = v),
            new Toggle("Blood pools", () -> VisceralConfig.extras.bloodPools, v -> VisceralConfig.extras.bloodPools = v),
            new Toggle("Footprints", () -> VisceralConfig.extras.footprints, v -> VisceralConfig.extras.footprints = v),
            new Toggle("Flies", () -> VisceralConfig.extras.flies, v -> VisceralConfig.extras.flies = v),
            new Toggle("Bloody weapon", () -> VisceralConfig.extras.bloodyWeapon, v -> VisceralConfig.extras.bloodyWeapon = v),
            new Toggle("Screen blood", () -> VisceralConfig.extras.screenBlood, v -> VisceralConfig.extras.screenBlood = v),
            new Toggle("Sounds", () -> VisceralConfig.extras.sounds, v -> VisceralConfig.extras.sounds = v),
            new Toggle("Only your kills burst", () -> VisceralConfig.gibs.onlyYourKills, v -> VisceralConfig.gibs.onlyYourKills = v),
            new Toggle("Players gib", () -> VisceralConfig.gibs.players, v -> VisceralConfig.gibs.players = v),
            new Toggle("Mild mode", () -> VisceralConfig.extras.mild, v -> VisceralConfig.extras.mild = v),
    };

    private static final int ID_PRESET = 100, ID_TOGGLE = 200, ID_COLOUR = 300, ID_DETAILS = 301, ID_RELOAD = 302,
            ID_CLEAR = 303, ID_DONE = 304, ID_SLIDER = 400;

    public GuiVisceral(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        int cx = width / 2, y = 34;
        // presets
        VisceralConfig.Preset[] ps = VisceralConfig.Preset.values();
        int pw = 62;
        int px = cx - (ps.length * (pw + 4) - 4) / 2;
        for (int i = 0; i < ps.length; i++) {
            buttonList.add(new GuiButton(ID_PRESET + i, px + i * (pw + 4), y, pw, 20, presetLabel(ps[i])));
        }
        y += 28;
        // switches, in three columns
        int cols = 3, bw = 128, gap = 4;
        int left = cx - (cols * (bw + gap) - gap) / 2;
        for (int i = 0; i < toggles.length; i++) {
            buttonList.add(new GuiButton(ID_TOGGLE + i, left + (i % cols) * (bw + gap), y + (i / cols) * 22, bw, 20, toggleLabel(i)));
        }
        y += ((toggles.length + cols - 1) / cols) * 22 + 6;
        // amounts
        int sw = 128;
        buttonList.add(new GuiSlider(ID_SLIDER, left, y, sw, 20, "Blood: ", "x", 0.0, 3.0, VisceralConfig.blood.amount, true, true,
                s -> { VisceralConfig.blood.amount = round(s.getValue()); custom(); }));
        buttonList.add(new GuiSlider(ID_SLIDER + 1, left + bw + gap, y, sw, 20, "Gib force: ", "x", 0.0, 3.0, VisceralConfig.gibs.force, true, true,
                s -> VisceralConfig.gibs.force = round(s.getValue())));
        buttonList.add(new GuiSlider(ID_SLIDER + 2, left + 2 * (bw + gap), y, sw, 20, "Volume: ", "x", 0.0, 2.0, VisceralConfig.extras.volume, true, true,
                s -> VisceralConfig.extras.volume = round(s.getValue())));
        y += 22;
        buttonList.add(new GuiSlider(ID_SLIDER + 3, left, y, sw, 20, "Stains last: ", "s", 10, 600, VisceralConfig.blood.stainSeconds, false, true,
                s -> { VisceralConfig.blood.stainSeconds = s.getValueInt(); custom(); }));
        buttonList.add(new GuiSlider(ID_SLIDER + 4, left + bw + gap, y, sw, 20, "Gibs last: ", "s", 3, 300, VisceralConfig.gibs.gibSeconds, false, true,
                s -> { VisceralConfig.gibs.gibSeconds = s.getValueInt(); custom(); }));
        buttonList.add(new GuiSlider(ID_SLIDER + 5, left + 2 * (bw + gap), y, sw, 20, "Burst chance: ", "%", 0, 100, VisceralConfig.gibs.gibChance * 100, false, true,
                s -> VisceralConfig.gibs.gibChance = s.getValueInt() / 100.0));
        y += 22;
        buttonList.add(new GuiButton(ID_COLOUR, left, y, bw, 20, colourLabel()));
        buttonList.add(new GuiButton(ID_RELOAD, left + bw + gap, y, bw, 20, "Reload blood list"));
        buttonList.add(new GuiButton(ID_CLEAR, left + 2 * (bw + gap), y, bw, 20, "Clear all gore"));
        y += 28;
        buttonList.add(new GuiButton(ID_DETAILS, cx - 154, Math.min(y, height - 26), 150, 20, "Every setting..."));
        buttonList.add(new GuiButton(ID_DONE, cx + 4, Math.min(y, height - 26), 150, 20, "Done"));
    }

    private static double round(double v) {
        return Math.round(v * 20) / 20.0;
    }

    private static void custom() {
        VisceralConfig.preset = VisceralConfig.Preset.CUSTOM;
    }

    private static String presetLabel(VisceralConfig.Preset p) {
        String n = p.name().charAt(0) + p.name().substring(1).toLowerCase();
        return VisceralConfig.preset == p ? "§e[" + n + "]" : n;
    }

    private String toggleLabel(int i) {
        boolean v = toggles[i].get.get();
        return toggles[i].label + ": " + (v ? "§aON" : "§cOFF");
    }

    private static String colourLabel() {
        String c = VisceralConfig.extras.bloodColor == null ? "" : VisceralConfig.extras.bloodColor.trim();
        for (int i = 0; i < COLOURS.length; i++) if (COLOURS[i].equalsIgnoreCase(c)) return "Blood colour: " + COLOUR_NAMES[i];
        return "Blood colour: " + c;
    }

    @Override
    protected void actionPerformed(GuiButton b) {
        if (b.id >= ID_PRESET && b.id < ID_PRESET + VisceralConfig.Preset.values().length) {
            VisceralConfig.preset = VisceralConfig.Preset.values()[b.id - ID_PRESET];
            VisceralConfig.applyPreset();
            initGui();
        } else if (b.id >= ID_TOGGLE && b.id < ID_TOGGLE + toggles.length) {
            Toggle t = toggles[b.id - ID_TOGGLE];
            t.set.accept(!t.get.get());
            if (t.label.equals("Footprints") || t.label.equals("Flies")) custom();
            initGui();
        } else if (b.id == ID_COLOUR) {
            String c = VisceralConfig.extras.bloodColor == null ? "" : VisceralConfig.extras.bloodColor.trim();
            int i = 0;
            for (int k = 0; k < COLOURS.length; k++) if (COLOURS[k].equalsIgnoreCase(c)) i = k;
            VisceralConfig.extras.bloodColor = COLOURS[(i + 1) % COLOURS.length];
            BloodTypes.reload();
            b.displayString = colourLabel();
        } else if (b.id == ID_RELOAD) {
            BloodTypes.reload();
            b.displayString = "Blood list reloaded";
        } else if (b.id == ID_CLEAR) {
            Blood.clearAll();
            Gibs.clearAll();
            Flies.clearAll();
            HeldBlood.clear();
            b.displayString = "Cleared";
        } else if (b.id == ID_DETAILS) {
            save();
            mc.displayGuiScreen(new GuiConfig(this, Visceral.MODID, "Visceral: every setting"));
        } else if (b.id == ID_DONE) {
            save();
            mc.displayGuiScreen(parent);
        }
    }

    private static void save() {
        ConfigManager.sync(Visceral.MODID, Config.Type.INSTANCE);
    }

    @Override
    public void onGuiClosed() {
        save();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(fontRenderer, "Visceral " + Visceral.VERSION, width / 2, 10, 0xE8C4C4);
        drawCenteredString(fontRenderer, "§7Preset (sets the amounts below; changing an amount makes it Custom)", width / 2, 22, 0xA0A0A0);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return true;
    }
}
