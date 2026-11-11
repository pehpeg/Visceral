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
            new Toggle("Kick gibs", () -> VisceralConfig.gibs.kickable, v -> VisceralConfig.gibs.kickable = v),
            new Toggle("Wounds", () -> VisceralConfig.extras.wounds, v -> VisceralConfig.extras.wounds = v),
            new Toggle("Stumps", () -> VisceralConfig.extras.stumps, v -> VisceralConfig.extras.stumps = v),
            new Toggle("Bleeding", () -> VisceralConfig.extras.bleeding, v -> VisceralConfig.extras.bleeding = v),
            new Toggle("Pools", () -> VisceralConfig.extras.bloodPools, v -> VisceralConfig.extras.bloodPools = v),
            new Toggle("Footprints", () -> VisceralConfig.extras.footprints, v -> VisceralConfig.extras.footprints = v),
            new Toggle("Flies", () -> VisceralConfig.extras.flies, v -> VisceralConfig.extras.flies = v),
            new Toggle("Weapon blood", () -> VisceralConfig.extras.bloodyWeapon, v -> VisceralConfig.extras.bloodyWeapon = v),
            new Toggle("Screen blood", () -> VisceralConfig.extras.screenBlood, v -> VisceralConfig.extras.screenBlood = v),
            new Toggle("Sounds", () -> VisceralConfig.extras.sounds, v -> VisceralConfig.extras.sounds = v),
            new Toggle("Only my kills", () -> VisceralConfig.gibs.onlyYourKills, v -> VisceralConfig.gibs.onlyYourKills = v),
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
        int cx = width / 2;
        // presets
        VisceralConfig.Preset[] ps = VisceralConfig.Preset.values();
        int pw = 62;
        int px = cx - (ps.length * (pw + 4) - 4) / 2;
        for (int i = 0; i < ps.length; i++) {
            buttonList.add(new GuiButton(ID_PRESET + i, px + i * (pw + 4), 18, pw, 20, presetLabel(ps[i])));
        }
        // everything else on one grid: the switches, then the amounts and tools. Three wide columns
        // when the screen is tall enough, four narrower ones when it isn't (a big GUI scale).
        int extras = 10;
        int cols = height >= 44 + (rows(toggles.length, 3) + rows(extras, 3)) * 22 + 26 ? 3 : 4;
        int gap = 4;
        int bw = Math.min(128, (width - 12) / cols - gap);
        int step = 44 + (rows(toggles.length, cols) + rows(extras, cols)) * 22 + 26 <= height ? 22 : 21;
        int left = cx - (cols * (bw + gap) - gap) / 2, top = 44;
        for (int i = 0; i < toggles.length; i++) {
            buttonList.add(new GuiButton(ID_TOGGLE + i, left + (i % cols) * (bw + gap), top + (i / cols) * step, bw, 20, toggleLabel(i)));
        }
        int y0 = top + rows(toggles.length, cols) * step;
        int[] k = {0};
        java.util.function.IntFunction<int[]> at = n -> new int[]{left + (n % cols) * (bw + gap), y0 + (n / cols) * step};
        int[] p;
        p = at.apply(k[0]++); buttonList.add(new GuiSlider(ID_SLIDER, p[0], p[1], bw, 20, "Blood: ", "x", 0.0, 3.0, VisceralConfig.blood.amount, true, true,
                sl -> { VisceralConfig.blood.amount = round(sl.getValue()); custom(); }));
        p = at.apply(k[0]++); buttonList.add(new GuiSlider(ID_SLIDER + 1, p[0], p[1], bw, 20, "Force: ", "x", 0.0, 3.0, VisceralConfig.gibs.force, true, true,
                sl -> VisceralConfig.gibs.force = round(sl.getValue())));
        p = at.apply(k[0]++); buttonList.add(new GuiSlider(ID_SLIDER + 2, p[0], p[1], bw, 20, "Volume: ", "x", 0.0, 2.0, VisceralConfig.extras.volume, true, true,
                sl -> VisceralConfig.extras.volume = round(sl.getValue())));
        p = at.apply(k[0]++); buttonList.add(new GuiSlider(ID_SLIDER + 3, p[0], p[1], bw, 20, "Stains: ", "s", 10, 600, VisceralConfig.blood.stainSeconds, false, true,
                sl -> { VisceralConfig.blood.stainSeconds = sl.getValueInt(); custom(); }));
        p = at.apply(k[0]++); buttonList.add(new GuiSlider(ID_SLIDER + 4, p[0], p[1], bw, 20, "Gibs: ", "s", 3, 300, VisceralConfig.gibs.gibSeconds, false, true,
                sl -> { VisceralConfig.gibs.gibSeconds = sl.getValueInt(); custom(); }));
        p = at.apply(k[0]++); buttonList.add(new GuiSlider(ID_SLIDER + 5, p[0], p[1], bw, 20, "Burst: ", "%", 0, 100, VisceralConfig.gibs.gibChance * 100, false, true,
                sl -> VisceralConfig.gibs.gibChance = sl.getValueInt() / 100.0));
        p = at.apply(k[0]++); buttonList.add(new GuiButton(ID_COLOUR, p[0], p[1], bw, 20, colourLabel()));
        p = at.apply(k[0]++); buttonList.add(new GuiButton(ID_RELOAD, p[0], p[1], bw, 20, "Reload list"));
        p = at.apply(k[0]++); buttonList.add(new GuiButton(ID_CLEAR, p[0], p[1], bw, 20, "Clear gore"));
        p = at.apply(k[0]++); buttonList.add(new GuiButton(ID_DETAILS, p[0], p[1], bw, 20, "All settings..."));
        int yd = Math.min(y0 + rows(extras, cols) * step + 4, height - 22);
        buttonList.add(new GuiButton(ID_DONE, cx - 75, yd, 150, 20, "Done"));
    }

    private static int rows(int n, int cols) {
        return (n + cols - 1) / cols;
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
        for (int i = 0; i < COLOURS.length; i++) if (COLOURS[i].equalsIgnoreCase(c)) return "Colour: " + COLOUR_NAMES[i];
        return "Colour: " + c;
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
            b.displayString = "Reloaded";
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
        drawCenteredString(fontRenderer, "Visceral " + Visceral.VERSION, width / 2, 6, 0xE8C4C4);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return true;
    }
}
