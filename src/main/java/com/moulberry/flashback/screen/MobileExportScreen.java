package com.moulberry.flashback.screen;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.MobileCompat;
import com.moulberry.flashback.combo_options.VideoContainer;
import com.moulberry.flashback.exporting.ExportJob;
import com.moulberry.flashback.exporting.ExportSettings;
import com.moulberry.flashback.playback.ReplayServer;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;

public class MobileExportScreen extends Screen {

    private final Screen lastScreen;
    private String resolution = "1920x1080";
    private int fps = 60;
    private EditBox startBox;
    private EditBox endBox;
    private int tickLabelY = 118;

    public MobileExportScreen(Screen lastScreen) {
        super(Component.literal("Flashback Mobile Export"));
        this.lastScreen = lastScreen;
    }

    private static int totalTicks() {
        ReplayServer replayServer = Flashback.getReplayServer();
        return replayServer != null ? Math.max(0, replayServer.getTotalReplayTicks()) : 0;
    }

    private static int parseTick(String value, int fallback) {
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (Exception e) {
            return fallback;
        }
    }

    @Override
    protected void init() {
        super.init();
        int total = totalTicks();
        int cx = this.width / 2;
        int y = 70;

        this.addRenderableWidget(CycleButton.builder((String s) -> Component.literal(s), this.resolution)
            .withValues("1280x720", "1920x1080")
            .create(cx - 100, y, 200, 20, Component.literal("Resolution"),
                (button, value) -> this.resolution = value));
        y += 26;
        this.addRenderableWidget(CycleButton.builder((Integer v) -> Component.literal(v + " fps"), this.fps)
            .withValues(30, 60)
            .create(cx - 100, y, 200, 20, Component.literal("Framerate"),
                (button, value) -> this.fps = value));
        y += 26;
        this.tickLabelY = y - 4;
        String startValue = this.startBox != null ? this.startBox.getValue() : "0";
        String endValue = this.endBox != null ? this.endBox.getValue() : String.valueOf(total);
        this.startBox = new EditBox(this.font, cx - 100, y, 96, 20, Component.literal("Start tick"));
        this.startBox.setMaxLength(10);
        this.startBox.setValue(startValue);
        this.addRenderableWidget(this.startBox);
        this.endBox = new EditBox(this.font, cx + 4, y, 96, 20, Component.literal("End tick"));
        this.endBox.setMaxLength(10);
        this.endBox.setValue(endValue);
        this.addRenderableWidget(this.endBox);
        y += 30;
        this.addRenderableWidget(Button.builder(Component.literal("Export PNG + MP4"), b -> this.doExport())
            .bounds(cx - 100, y, 200, 20).build());
        y += 26;
        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> Minecraft.getInstance().setScreen(this.lastScreen))
            .bounds(cx - 100, y, 200, 20).build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int i, int j, float f) {
        super.render(guiGraphics, i, j, f);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 40, 0xFFFFFFFF);
        guiGraphics.drawString(this.font, "Start / end tick:", this.width / 2 - 100, this.tickLabelY, 0xFFAAAAAA);
    }

    private void doExport() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.gui == null) {
            return;
        }
        if (!Flashback.isInReplay()) {
            minecraft.gui.getChat().addMessage(Component.literal("Not in a replay"));
            return;
        }
        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState == null || minecraft.player == null) {
            minecraft.gui.getChat().addMessage(Component.literal("Replay not ready yet"));
            return;
        }
        if (Flashback.EXPORT_JOB != null) {
            minecraft.gui.getChat().addMessage(Component.literal("Export already running"));
            return;
        }
        try {
            int total = totalTicks();
            if (total <= 0) {
                minecraft.gui.getChat().addMessage(Component.literal("Replay is empty, nothing to export"));
                return;
            }
            int start = Math.min(Math.max(0, parseTick(this.startBox.getValue(), 0)), total);
            int end = Math.min(Math.max(0, parseTick(this.endBox.getValue(), total)), total);
            if (end <= start) {
                minecraft.gui.getChat().addMessage(Component.literal(
                    "Invalid range: end tick must be greater than start tick (0-" + total + ")"));
                return;
            }
            String[] wh = this.resolution.split("x");
            int resX = Integer.parseInt(wh[0]);
            int resY = Integer.parseInt(wh[1]);
            var player = minecraft.player;
            Path output = MobileCompat.newUniqueMobileExportDir();
            var settings = new ExportSettings(null, editorState.copy(),
                player.position(), player.getYRot(), player.getXRot(),
                resX, resY, start, end,
                (double) this.fps, false, VideoContainer.PNG_SEQUENCE, null, null, 0, false, false, false,
                false, false, null,
                output, "%04d");
            Flashback.EXPORT_JOB = new ExportJob(settings);
            minecraft.setScreen(null);
            minecraft.gui.getChat().addMessage(Component.literal("Mobile export started to " + output));
        } catch (Exception e) {
            Flashback.LOGGER.error("Mobile export failed", e);
            Minecraft fallback = Minecraft.getInstance();
            if (fallback != null && fallback.gui != null) {
                fallback.gui.getChat().addMessage(Component.literal("Mobile export failed: " + e.getMessage()));
            }
        }
    }
}
