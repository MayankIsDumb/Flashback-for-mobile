package com.moulberry.flashback.screen;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.Utils;
import com.moulberry.flashback.keyframe.Keyframe;
import com.moulberry.flashback.keyframe.change.KeyframeChangeFreeze;
import com.moulberry.flashback.keyframe.impl.CameraKeyframe;
import com.moulberry.flashback.keyframe.impl.FOVKeyframe;
import com.moulberry.flashback.keyframe.impl.FreezeKeyframe;
import com.moulberry.flashback.keyframe.impl.TimeOfDayKeyframe;
import com.moulberry.flashback.keyframe.impl.TimelapseKeyframe;
import com.moulberry.flashback.keyframe.types.CameraKeyframeType;
import com.moulberry.flashback.playback.ReplayServer;
import com.moulberry.flashback.state.EditorScene;
import com.moulberry.flashback.state.EditorSceneHistoryAction;
import com.moulberry.flashback.state.EditorSceneHistoryEntry;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import com.moulberry.flashback.state.KeyframeTrack;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Phase 2 (route 2): keyframe management without the ImGui timeline.
 *
 * <p>Registers {@code /flashback keyframe add|list|clear} client commands.
 * Construction defaults mirror
 * {@code editor/ui/windows/TimelineWindow.java} exactly:</p>
 * <ul>
 *   <li>camera: {@code CameraKeyframeType.INSTANCE.createDirect()} (camera entity,
 *       falls back to player; roll taken from {@code replayVisuals.overrideRollAmount}
 *       when enabled, else 0).</li>
 *   <li>fov: popup prefill ({@code replayVisuals.overrideFovAmount} when
 *       {@code overrideFov}, else {@code options.fov}), clamped to the popup
 *       slider range 1..110.</li>
 *   <li>freeze: popup defaults (not frozen, delay 0); delay clamped 0..10.</li>
 *   <li>timelapse: first key on an empty track is {@code TimelapseKeyframe(0)},
 *       popup default input is {@code "1s"}; values parsed with
 *       {@code Utils.stringToTime} (same charset as the popup).</li>
 *   <li>time: popup prefill ({@code replayVisuals.overrideTimeOfDay} when
 *       {@code >= 0}, else {@code level.getDayTime() % 24000}).</li>
 * </ul>
 *
 * <p>"Now" is {@code ReplayServer.getReplayTick()}, the same tick the timeline
 * uses for its cursor. New tracks are appended at the end of the current scene
 * (same as the timeline's "Add element" popup, via an AddTrack history entry);
 * keyframes are added via {@code EditorScene.setKeyframe} so desktop undo stays
 * coherent. Persistence is via {@code EditorState.markDirty()} plus the existing
 * {@code EditorStateManager} autosave, same as timeline edits.</p>
 *
 * <p>Clear scope: current scene only. Other scenes are left untouched; this is
 * the safer scope because a scene the user cannot see on mobile should never be
 * wiped by a chat command.</p>
 */
public class MobileKeyframes {

    /**
     * Attaches the {@code keyframe} subcommand to an existing {@code /flashback}
     * literal builder. Uses the same {@link ClientCommandManager} /
     * {@link FabricClientCommandSource} classes as {@code Flashback.java}.
     */
    public static void register(LiteralArgumentBuilder<FabricClientCommandSource> flashback) {
        var keyframe = ClientCommandManager.literal("keyframe");

        var add = ClientCommandManager.literal("add");
        for (String type : new String[]{"camera", "fov", "freeze", "timelapse", "time"}) {
            var typeNode = ClientCommandManager.literal(type);
            typeNode.then(ClientCommandManager.argument("value", StringArgumentType.greedyString())
                .executes(ctx -> addKeyframe(ctx, type, ctx.getArgument("value", String.class))));
            typeNode.executes(ctx -> addKeyframe(ctx, type, null));
            add.then(typeNode);
        }

        keyframe.then(add);
        keyframe.then(ClientCommandManager.literal("list").executes(MobileKeyframes::listKeyframes));
        keyframe.then(ClientCommandManager.literal("clear").executes(MobileKeyframes::clearKeyframes));
        flashback.then(keyframe);
    }

    private static int addKeyframe(CommandContext<FabricClientCommandSource> ctx, String type, String rawValue) {
        try {
            if (!Flashback.isInReplay()) {
                ctx.getSource().sendError(Component.literal("Not in a replay. Open a replay first."));
                return 0;
            }
            if (Flashback.isExporting()) {
                ctx.getSource().sendError(Component.literal("Export is running, keyframes cannot be edited right now."));
                return 0;
            }
            EditorState editorState = EditorStateManager.getCurrent();
            if (editorState == null) {
                ctx.getSource().sendError(Component.literal("No editor state loaded yet."));
                return 0;
            }
            ReplayServer replayServer = Flashback.getReplayServer();
            if (replayServer == null) {
                ctx.getSource().sendError(Component.literal("Replay server not ready."));
                return 0;
            }

            int tick = Math.max(0, Math.min(replayServer.getTotalReplayTicks(), replayServer.getReplayTick()));
            String value = rawValue == null ? null : rawValue.trim();

            Keyframe keyframe;
            switch (type) {
                case "camera" -> {
                    keyframe = CameraKeyframeType.INSTANCE.createDirect();
                    if (keyframe == null) {
                        ctx.getSource().sendError(Component.literal("No camera entity or player available."));
                        return 0;
                    }
                }
                case "fov" -> {
                    float fov = defaultFov(editorState);
                    if (value != null && !value.isEmpty()) {
                        try {
                            fov = Float.parseFloat(value);
                        } catch (NumberFormatException e) {
                            ctx.getSource().sendError(Component.literal("Usage: /flashback keyframe add fov [1-110]"));
                            return 0;
                        }
                    }
                    keyframe = new FOVKeyframe(Math.max(1f, Math.min(110f, fov)));
                }
                case "freeze" -> {
                    boolean frozen = false;
                    int delay = 0;
                    if (value != null && !value.isEmpty()) {
                        String[] parts = value.split("\\s+");
                        Boolean parsed = parseFrozen(parts[0]);
                        if (parsed == null) {
                            ctx.getSource().sendError(Component.literal("Usage: /flashback keyframe add freeze [true|false] [delay 0-10]"));
                            return 0;
                        }
                        frozen = parsed;
                        if (parts.length > 1) {
                            try {
                                delay = Integer.parseInt(parts[1]);
                            } catch (NumberFormatException e) {
                                ctx.getSource().sendError(Component.literal("Usage: /flashback keyframe add freeze [true|false] [delay 0-10]"));
                                return 0;
                            }
                        }
                    }
                    keyframe = new FreezeKeyframe(frozen, Math.max(0, Math.min(10, delay)));
                }
                case "timelapse" -> {
                    int ticks;
                    if (value == null || value.isEmpty()) {
                        ticks = Utils.stringToTime("1s");
                    } else {
                        ticks = Utils.stringToTime(value);
                    }
                    keyframe = new TimelapseKeyframe(ticks);
                }
                case "time" -> {
                    int time = defaultTimeOfDay();
                    if (time < 0) {
                        ctx.getSource().sendError(Component.literal("Level not loaded, cannot read current time."));
                        return 0;
                    }
                    if (value != null && !value.isEmpty()) {
                        try {
                            time = Integer.parseInt(value);
                        } catch (NumberFormatException e) {
                            ctx.getSource().sendError(Component.literal("Usage: /flashback keyframe add time [0-23999]"));
                            return 0;
                        }
                    }
                    keyframe = new TimeOfDayKeyframe(time);
                }
                default -> {
                    ctx.getSource().sendError(Component.literal("Unknown keyframe type: " + type));
                    return 0;
                }
            }

            // Mirror TimelineWindow.createNewKeyframe: first timelapse key on an
            // empty track is TimelapseKeyframe(0), not the popup default.
            if (keyframe instanceof TimelapseKeyframe && (value == null || value.isEmpty())) {
                keyframe = new TimelapseKeyframe(0);
            }

            long stamp = editorState.acquireWrite();
            try {
                EditorScene scene = editorState.getCurrentScene(stamp);
                int trackIndex = findTrack(scene, keyframe);
                if (trackIndex < 0) {
                    trackIndex = scene.keyframeTracks.size();
                    List<EditorSceneHistoryAction> undo = List.of(
                        new EditorSceneHistoryAction.RemoveTrack(keyframe.keyframeType(), trackIndex));
                    List<EditorSceneHistoryAction> redo = List.of(
                        new EditorSceneHistoryAction.AddTrack(keyframe.keyframeType(), trackIndex));
                    scene.push(new EditorSceneHistoryEntry(new ArrayList<>(undo), new ArrayList<>(redo),
                        "Created " + keyframe.keyframeType().name() + " track"));
                }
                scene.setKeyframe(trackIndex, tick, keyframe);
                editorState.markDirty();
            } finally {
                editorState.release(stamp);
            }

            String summary = describe(keyframe);
            ctx.getSource().sendFeedback(Component.literal(
                "Added " + type + " keyframe at tick " + tick + " (" + summary + ")"));
            if (keyframe instanceof CameraKeyframe) {
                Minecraft minecraft = Minecraft.getInstance();
                if (minecraft.player != null && minecraft.player != minecraft.getCameraEntity()) {
                    ctx.getSource().sendFeedback(Component.literal(
                        "Hint: camera keyframes apply to the spectated camera; run /spectate to control it."));
                }
            }
        } catch (Exception e) {
            Flashback.LOGGER.error("MobileKeyframes add failed", e);
            try {
                ctx.getSource().sendError(Component.literal("Failed to add keyframe: " + e.getMessage()));
            } catch (Exception ignored) {}
        }
        return 0;
    }

    private static int listKeyframes(CommandContext<FabricClientCommandSource> ctx) {
        try {
            if (!Flashback.isInReplay()) {
                ctx.getSource().sendError(Component.literal("Not in a replay. Open a replay first."));
                return 0;
            }
            if (Flashback.isExporting()) {
                ctx.getSource().sendError(Component.literal("Export is running, keyframes cannot be listed right now."));
                return 0;
            }
            EditorState editorState = EditorStateManager.getCurrent();
            if (editorState == null) {
                ctx.getSource().sendError(Component.literal("No editor state loaded yet."));
                return 0;
            }

            long stamp = editorState.acquireRead();
            try {
                EditorScene scene = editorState.getCurrentScene(stamp);
                int total = 0;
                for (KeyframeTrack track : scene.keyframeTracks) {
                    total += track.keyframesByTick.size();
                }
                if (total == 0) {
                    ctx.getSource().sendFeedback(Component.literal("No keyframes in current scene."));
                    return 0;
                }
                ctx.getSource().sendFeedback(Component.literal(
                    "Keyframes in current scene (" + total + "):"));
                TreeMap<Integer, List<String>> byTick = new TreeMap<>();
                for (KeyframeTrack track : scene.keyframeTracks) {
                    for (Map.Entry<Integer, Keyframe> entry : track.keyframesByTick.entrySet()) {
                        String label = track.keyframeType.id() + " (" + describe(entry.getValue()) + ")";
                        byTick.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(label);
                    }
                }
                for (Map.Entry<Integer, List<String>> entry : byTick.entrySet()) {
                    for (String label : entry.getValue()) {
                        ctx.getSource().sendFeedback(Component.literal("  tick " + entry.getKey() + ": " + label));
                    }
                }
            } finally {
                editorState.release(stamp);
            }
        } catch (Exception e) {
            Flashback.LOGGER.error("MobileKeyframes list failed", e);
            try {
                ctx.getSource().sendError(Component.literal("Failed to list keyframes: " + e.getMessage()));
            } catch (Exception ignored) {}
        }
        return 0;
    }

    private static int clearKeyframes(CommandContext<FabricClientCommandSource> ctx) {
        try {
            if (!Flashback.isInReplay()) {
                ctx.getSource().sendError(Component.literal("Not in a replay. Open a replay first."));
                return 0;
            }
            if (Flashback.isExporting()) {
                ctx.getSource().sendError(Component.literal("Export is running, keyframes cannot be cleared right now."));
                return 0;
            }
            EditorState editorState = EditorStateManager.getCurrent();
            if (editorState == null) {
                ctx.getSource().sendError(Component.literal("No editor state loaded yet."));
                return 0;
            }

            int removed;
            String sceneName;
            long stamp = editorState.acquireWrite();
            try {
                EditorScene scene = editorState.getCurrentScene(stamp);
                sceneName = scene.name;
                List<EditorSceneHistoryAction> undo = new ArrayList<>();
                List<EditorSceneHistoryAction> redo = new ArrayList<>();
                removed = 0;
                for (int i = 0; i < scene.keyframeTracks.size(); i++) {
                    KeyframeTrack track = scene.keyframeTracks.get(i);
                    for (Map.Entry<Integer, Keyframe> entry : track.keyframesByTick.entrySet()) {
                        undo.add(new EditorSceneHistoryAction.SetKeyframe(
                            track.keyframeType, i, entry.getKey(), entry.getValue().copy()));
                        redo.add(new EditorSceneHistoryAction.RemoveKeyframe(
                            track.keyframeType, i, entry.getKey()));
                        removed += 1;
                    }
                }
                if (removed > 0) {
                    scene.push(new EditorSceneHistoryEntry(undo, redo, "Cleared keyframes in current scene"));
                    editorState.markDirty();
                }
            } finally {
                editorState.release(stamp);
            }

            if (removed == 0) {
                ctx.getSource().sendFeedback(Component.literal("No keyframes to clear in current scene."));
            } else {
                ctx.getSource().sendFeedback(Component.literal(
                    "Cleared " + removed + " keyframe(s) from current scene '" + sceneName + "'. Other scenes untouched."));
            }
        } catch (Exception e) {
            Flashback.LOGGER.error("MobileKeyframes clear failed", e);
            try {
                ctx.getSource().sendError(Component.literal("Failed to clear keyframes: " + e.getMessage()));
            } catch (Exception ignored) {}
        }
        return 0;
    }

    private static int findTrack(EditorScene scene, Keyframe keyframe) {
        for (int i = 0; i < scene.keyframeTracks.size(); i++) {
            if (scene.keyframeTracks.get(i).keyframeType == keyframe.keyframeType()) {
                return i;
            }
        }
        return -1;
    }

    private static float defaultFov(EditorState editorState) {
        if (editorState.replayVisuals.overrideFov) {
            return editorState.replayVisuals.overrideFovAmount;
        }
        return (float) Minecraft.getInstance().options.fov().get();
    }

    private static int defaultTimeOfDay() {
        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState != null && editorState.replayVisuals.overrideTimeOfDay >= 0) {
            return (int) editorState.replayVisuals.overrideTimeOfDay;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return -1;
        }
        return (int) (minecraft.level.getDayTime() % 24000);
    }

    private static Boolean parseFrozen(String token) {
        return switch (token.toLowerCase()) {
            case "true", "1", "frozen", "freeze" -> true;
            case "false", "0", "unfrozen", "unfreeze" -> false;
            default -> null;
        };
    }

    private static String describe(Keyframe keyframe) {
        return switch (keyframe) {
            case CameraKeyframe camera -> String.format("pos=%.1f,%.1f,%.1f yaw=%.1f pitch=%.1f",
                camera.position.x, camera.position.y, camera.position.z, camera.yaw, camera.pitch);
            case FOVKeyframe fov -> "fov=" + fov.fov;
            case FreezeKeyframe freeze -> {
                KeyframeChangeFreeze change = (KeyframeChangeFreeze) freeze.createChange();
                yield "frozen=" + change.frozen() + " delay=" + change.frozenDelay();
            }
            case TimelapseKeyframe timelapse ->
                "time=" + Utils.timeInTicksToString(timelapse.ticks) + " (" + timelapse.ticks + "t)";
            case TimeOfDayKeyframe time -> "time=" + time.time;
            default -> keyframe.keyframeType().id();
        };
    }
}
