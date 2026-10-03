package com.moulberry.flashback;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

public final class MobileCompat {
    private MobileCompat() {}

    public static boolean isAndroid() {
        String pojavPath = System.getProperty("pojav.path.minecraft");
        if (pojavPath != null && !pojavPath.isEmpty()) {
            return true;
        }
        String osVersion = System.getProperty("os.version", "");
        if (osVersion.startsWith("Android")) {
            return true;
        }
        String osName = System.getProperty("os.name", "");
        if (osName.equalsIgnoreCase("Linux")) {
            String vendor = androidOsBuildManufacturer();
            if (vendor != null) {
                return true;
            }
        }
        try {
            Class.forName("android.os.Build");
            return true;
        } catch (ClassNotFoundException ignored) {
        }
        return Files.exists(Path.of("/system/build.prop"));
    }

    private static String androidOsBuildManufacturer() {
        try {
            Class<?> build = Class.forName("android.os.Build");
            Object manufacturer = build.getField("MANUFACTURER").get(null);
            if (manufacturer != null) {
                return manufacturer.toString();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static boolean isPojavBasedLauncher() {
        if (System.getProperty("pojav.path.minecraft") != null) {
            return true;
        }
        if (System.getProperty("net.minecraft.clientmodname", "").equalsIgnoreCase("PojavLauncher")) {
            return true;
        }
        return isAndroid();
    }

    public static boolean isNativeFileDialogSupported() {
        if (isAndroid()) {
            return false;
        }
        // Other platforms: assume supported and rely on the per-call LinkageError
        // fallbacks in AsyncFileDialogs (e.g. ARM Linux desktops without NFD ports).
        return true;
    }

    public static boolean isImGuiNativeSupported() {
        if (!isAndroid()) {
            return true;
        }
        return androidImGuiAbi() != null;
    }

    public static final String IMGUI_SONAME = "libimgui-moulberry90-java64.so";

    public static String androidImGuiAbi() {
        String arch = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT);
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            return "arm64-v8a";
        }
        if (arch.contains("x86_64") || arch.contains("amd64")) {
            return "x86_64";
        }
        return null;
    }

    private static volatile boolean androidImGuiNativesStaged = false;

    /**
     * Internal-storage bin dir for extracted natives. External gameDir storage is
     * often mounted noexec, which makes dlopen fail — java.io.tmpdir points at the
     * app's internal cache on launchers (Pojav sets TMPDIR there), which allows
     * executable mappings. Falls back to gameDir only if tmpdir is unusable.
     */
    private static java.nio.file.Path internalBinDir(String abi) {
        String[] candidates = new String[]{
            System.getProperty("java.io.tmpdir"),
            System.getenv("TMPDIR")
        };
        for (String base : candidates) {
            if (base == null || base.isEmpty()) {
                continue;
            }
            try {
                java.nio.file.Path dir = java.nio.file.Path.of(base, "flashback-imgui", abi);
                java.nio.file.Files.createDirectories(dir);
                java.nio.file.Path probe = dir.resolve(".w");
                try {
                    java.nio.file.Files.write(probe, new byte[]{0});
                    java.nio.file.Files.deleteIfExists(probe);
                    return dir;
                } catch (Exception ignored) {}
            } catch (Exception ignored) {}
        }
        try {
            return net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir()
                .resolve("flashback").resolve(".bin").resolve(abi);
        } catch (Exception e) {
            return null;
        }
    }

    public static synchronized boolean setupAndroidImGuiNatives() {
        if (!isAndroid()) {
            return false;
        }
        String abi = androidImGuiAbi();
        if (abi == null) {
            return false;
        }
        if (androidImGuiNativesStaged && System.getProperty("imgui.library.path") != null) {
            System.setProperty("imgui.library.name", "imgui-moulberry90-java64");
            return true;
        }
        try {
            java.net.URL location = MobileCompat.class.getProtectionDomain().getCodeSource().getLocation();
            java.io.File jarFile = new java.io.File(location.toURI());
            if (!jarFile.isFile()) {
                return false;
            }
            String[] prefixes = {
                "assets/flashback/imgui-android/" + abi + "/",
                "android-natives/" + abi + "/"
            };
            java.nio.file.Path binDir = internalBinDir(abi);
            if (binDir == null) {
                return false;
            }
            java.nio.file.Files.createDirectories(binDir);
            java.nio.file.Path soOut = binDir.resolve(IMGUI_SONAME);
            boolean found = false;
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jarFile)) {
                java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    java.util.zip.ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory()) {
                        continue;
                    }
                    String name = entry.getName();
                    boolean match = false;
                    for (String prefix : prefixes) {
                        if (name.equals(prefix + IMGUI_SONAME)) {
                            match = true;
                            break;
                        }
                    }
                    if (!match) {
                        continue;
                    }
                    try (java.io.InputStream in = zip.getInputStream(entry)) {
                        java.nio.file.Files.copy(in, soOut, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    found = true;
                    break;
                }
            }
            if (!found || !java.nio.file.Files.exists(soOut) || java.nio.file.Files.size(soOut) == 0) {
                return false;
            }
            soOut.toFile().setExecutable(true, true);
            // Aliases: ImGui's loader honors imgui.library.name, but other mods may set it
            // to plain "imgui-java", and the path branch loads the name verbatim (no affixes).
            // Hardlinks cost no extra space; fall back to copies if linking fails.
            String[] aliases = new String[]{"imgui-moulberry90-java64", "imgui-java", "libimgui-java.so"};
            for (String alias : aliases) {
                try {
                    java.nio.file.Path link = binDir.resolve(alias);
                    java.nio.file.Files.deleteIfExists(link);
                    try {
                        java.nio.file.Files.createLink(link, soOut);
                    } catch (Exception linkFailed) {
                        java.nio.file.Files.copy(soOut, link, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (Exception ignored) {}
            }
            System.setProperty("imgui.library.path", binDir.toString());
            System.setProperty("imgui.library.name", "imgui-moulberry90-java64");
            androidImGuiNativesStaged = true;
            Flashback.LOGGER.info("Flashback mobile: staged ImGui native {} ({} bytes) at {}",
                IMGUI_SONAME, java.nio.file.Files.size(soOut), soOut);
            return true;
        } catch (Exception e) {
            Flashback.LOGGER.warn("Flashback mobile: bundled ImGui native extract failed, editor UI will be disabled", e);
            return false;
        }
    }

    public static boolean isFFmpegNativeSupported() {
        if (isAndroid()) {
            return false;
        }
        return true;
    }

    public static Path defaultMobileExportDir() {
        Path gameDir = FabricLoader.getInstance().getGameDir();
        Path dir = gameDir.resolve("flashback_exports");
        try {
            Files.createDirectories(dir);
        } catch (Exception ignored) {}
        return dir;
    }

    public static Path newUniqueMobileExportDir() throws java.io.IOException {
        Path dir = defaultMobileExportDir();
        String base = "mobile_" + java.time.LocalDateTime.now().withNano(0).toString().replace(':', '-');
        Path output = dir.resolve(base);
        int counter = 0;
        while (Files.exists(output)) {
            counter += 1;
            output = dir.resolve(base + "_" + counter);
        }
        Files.createDirectories(output);
        return output;
    }

    public static Path exportTempDir() {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("replay_export_temp");
        try {
            Files.createDirectories(dir);
        } catch (Exception ignored) {}
        return dir;
    }

    public static String newPidToken() {
        try {
            return Long.toString(ProcessHandle.current().pid());
        } catch (LinkageError | UnsupportedOperationException | SecurityException e) {
            return "mobile-" + java.util.UUID.randomUUID();
        }
    }

    public static boolean isPidAlive(String pidStr) {
        if (pidStr == null || pidStr.isEmpty()) {
            return false;
        }
        try {
            long pid = Long.parseLong(pidStr.trim());
            return ProcessHandle.of(pid).isPresent();
        } catch (NumberFormatException e) {
            return false;
        } catch (LinkageError | UnsupportedOperationException | SecurityException e) {
            return false;
        }
    }

    public static String findFfmpegExecutable() {
        String bundled = extractBundledFfmpeg();
        if (bundled != null) {
            return bundled;
        }
        try {
            String fromEnv = System.getenv("POJAV_FFMPEG_PATH");
            if (fromEnv != null && !fromEnv.isEmpty()) {
                java.io.File f = new java.io.File(fromEnv);
                if (f.isFile() && f.canExecute()) {
                    return f.getAbsolutePath();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String cachedBundledFfmpeg = null;
    private static boolean cachedBundledFfmpegChecked = false;

    public static synchronized String extractBundledFfmpeg() {
        if (cachedBundledFfmpegChecked) {
            return cachedBundledFfmpeg;
        }
        cachedBundledFfmpegChecked = true;
        try {
            String arch = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT);
            String abi;
            if (arch.contains("aarch64") || arch.contains("arm64")) {
                abi = "arm64-v8a";
            } else if (arch.contains("x86_64") || arch.contains("amd64")) {
                abi = "x86_64";
            } else {
                return null;
            }
            java.net.URL location = MobileCompat.class.getProtectionDomain().getCodeSource().getLocation();
            java.io.File jarFile = new java.io.File(location.toURI());
            if (!jarFile.isFile()) {
                return null;
            }
            java.nio.file.Path binDir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir()
                .resolve("flashback").resolve(".bin").resolve(abi);
            java.nio.file.Files.createDirectories(binDir);
            java.nio.file.Path ffmpegOut = binDir.resolve("ffmpeg");
            boolean fresh = false;
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jarFile)) {
                java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    java.util.zip.ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!name.startsWith("lib/" + abi + "/") || entry.isDirectory()) {
                        continue;
                    }
                    String base = name.substring(("lib/" + abi + "/").length());
                    if (base.contains("/") || !(base.equals("ffmpeg") || base.endsWith(".so"))) {
                        continue;
                    }
                    java.nio.file.Path out = binDir.resolve(base);
                    try (java.io.InputStream in = zip.getInputStream(entry)) {
                        java.nio.file.Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    fresh = true;
                }
            }
            if (!fresh || !java.nio.file.Files.exists(ffmpegOut)) {
                return null;
            }
            ffmpegOut.toFile().setExecutable(true, true);
            if (!ffmpegOut.toFile().canExecute()) {
                return null;
            }
            cachedBundledFfmpeg = ffmpegOut.toString();
            return cachedBundledFfmpeg;
        } catch (Exception e) {
            Flashback.LOGGER.warn("Flashback mobile: bundled ffmpeg extract failed", e);
            return null;
        }
    }

    /**
     * Drains a process stream to EOF (so the child can never block on a full pipe)
     * while retaining only the last {@code cap} bytes for diagnostics. ffmpeg merges
     * progress output into the stream continuously, so an unbounded read would hold
     * megabytes for long exports.
     */
    private static byte[] drainRetainingTail(java.io.InputStream in, int cap) throws java.io.IOException {
        java.util.ArrayDeque<byte[]> chunks = new java.util.ArrayDeque<>();
        int total = 0;
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            byte[] copy = java.util.Arrays.copyOf(buf, n);
            chunks.addLast(copy);
            total += n;
            while (total > cap && !chunks.isEmpty()) {
                total -= chunks.removeFirst().length;
            }
        }
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, out, pos, chunk.length);
            pos += chunk.length;
        }
        return out;
    }

    private static volatile String lastEncodeLog = "";

    /**
     * Encodes sorted *.png frames in {@code dir} to {@code mp4} by decoding them with
     * NativeImage (STB, always present) and piping raw RGBA to ffmpeg's rawvideo demuxer.
     * Avoids image decoders entirely: bundled/plugin ffmpeg builds may lack a PNG decoder
     * (observed: "Decoder (codec png) not found"). rawvideo + mpeg4 are core components
     * present in every build. Returns ffmpeg's exit code.
     */
    private static int encodeRawRgba(String ffmpeg, java.io.File binDir, java.nio.file.Path dir,
            java.nio.file.Path mp4, String fps, String[] codecArgs) {
        try {
            java.util.List<java.nio.file.Path> frames = new java.util.ArrayList<>();
            try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.list(dir)) {
                stream.filter(p -> {
                    String n = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                    return n.endsWith(".png");
                }).sorted().forEach(frames::add);
            }
            if (frames.isEmpty()) {
                lastEncodeLog = "no PNG frames in " + dir;
                return -1;
            }
            com.mojang.blaze3d.platform.NativeImage first =
                com.mojang.blaze3d.platform.NativeImage.read(java.nio.file.Files.newInputStream(frames.get(0)));
            int w = first.getWidth();
            int h = first.getHeight();
            first.close();
            java.util.List<String> cmd = new java.util.ArrayList<>();
            cmd.add(ffmpeg);
            cmd.addAll(java.util.Arrays.asList("-y", "-f", "rawvideo", "-pix_fmt", "rgba",
                "-s", w + "x" + h, "-framerate", fps, "-i", "-",
                "-c:v", codecArgs[0]));
            for (int i = 1; i < codecArgs.length; i++) {
                cmd.add(codecArgs[i]);
            }
            cmd.addAll(java.util.Arrays.asList("-pix_fmt", "yuv420p", mp4.toString()));
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
            if (binDir != null) {
                try {
                    String existingLd = pb.environment().get("LD_LIBRARY_PATH");
                    String libPath = binDir.getAbsolutePath()
                        + (existingLd != null && !existingLd.isEmpty() ? ":" + existingLd : "");
                    pb.environment().put("LD_LIBRARY_PATH", libPath);
                } catch (Exception ignored) {}
            }
            final Process process = pb.start();
            final Throwable[] feedError = new Throwable[1];
            Thread feeder = new Thread(() -> {
                try (java.io.OutputStream os = process.getOutputStream()) {
                    byte[] frame = new byte[w * h * 4];
                    for (java.nio.file.Path png : frames) {
                        com.mojang.blaze3d.platform.NativeImage img = null;
                        try {
                            img = com.mojang.blaze3d.platform.NativeImage.read(java.nio.file.Files.newInputStream(png));
                            int[] abgr = img.getPixelsABGR();
                            int count = Math.min(abgr.length, w * h);
                            for (int i = 0; i < count; i++) {
                                int p = abgr[i];
                                int o = i * 4;
                                frame[o] = (byte) (p & 0xFF);
                                frame[o + 1] = (byte) ((p >> 8) & 0xFF);
                                frame[o + 2] = (byte) ((p >> 16) & 0xFF);
                                frame[o + 3] = (byte) ((p >> 24) & 0xFF);
                            }
                            for (int o = count * 4; o < frame.length; o++) {
                                frame[o] = 0;
                            }
                            os.write(frame);
                        } finally {
                            if (img != null) {
                                img.close();
                            }
                        }
                    }
                } catch (Throwable t) {
                    feedError[0] = t;
                }
            }, "flashback-mobile-ffmpeg-feed");
            feeder.setDaemon(true);
            feeder.start();
            byte[] tail = drainRetainingTail(process.getInputStream(), 32 * 1024);
            feeder.join();
            int exit = process.waitFor();
            lastEncodeLog = new String(tail, java.nio.charset.StandardCharsets.UTF_8);
            if (feedError[0] != null) {
                Flashback.LOGGER.warn("Flashback mobile: ffmpeg feed issue", feedError[0]);
            }
            return exit;
        } catch (Exception e) {
            lastEncodeLog = String.valueOf(e.getMessage());
            Flashback.LOGGER.warn("Flashback mobile: raw encode setup failed", e);
            return -1;
        }
    }

    public static void assembleMp4InBackground(com.moulberry.flashback.exporting.ExportSettings settings) {
        Thread thread = new Thread(() -> {
            String mp4Message;
            postMobileChat("Flashback mobile: PNG export done, assembling MP4 in background...");
            try {
                String ffmpeg = findFfmpegExecutable();
                if (ffmpeg == null) {
                    mp4Message = "Flashback mobile: MP4 skipped (no bundled or plugin ffmpeg found). PNG frames kept at " + settings.output();
                    Flashback.LOGGER.warn(mp4Message);
                } else {
                    java.nio.file.Path dir = settings.output();
                    if (dir == null || dir.getFileName() == null) {
                        mp4Message = "Flashback mobile: MP4 skipped (invalid PNG output dir), PNG frames kept at " + settings.output();
                        Flashback.LOGGER.warn(mp4Message);
                    } else {
                    java.nio.file.Path mp4 = dir.resolveSibling(dir.getFileName().toString() + ".mp4");
                    String fps = String.valueOf(settings.framerate());
                    String[][] codecs = new String[][]{
                        {"libx264", "-crf", "18", "-preset", "veryfast"},
                        {"mpeg4", "-q:v", "2"}
                    };
                    int exit = -1;
                    String log = "";
                    java.io.File binDir = null;
                    try {
                        binDir = new java.io.File(ffmpeg).getParentFile();
                    } catch (Exception ignored) {}
                    for (String[] codec : codecs) {
                        Flashback.LOGGER.info("Flashback mobile: assembling MP4 {} via {}", mp4, codec[0]);
                        try {
                            java.nio.file.Files.deleteIfExists(mp4);
                        } catch (Exception ignored) {}
                        exit = encodeRawRgba(ffmpeg, binDir, dir, mp4, fps, codec);
                        log = lastEncodeLog;
                        if (exit == 0 && java.nio.file.Files.exists(mp4)) {
                            break;
                        }
                        Flashback.LOGGER.warn("Flashback mobile: MP4 attempt with {} failed (exit {})", codec[0], exit);
                    }
                    if (exit == 0 && java.nio.file.Files.exists(mp4)) {
                        mp4Message = "Flashback mobile: MP4 ready at " + mp4;
                        try {
                            if (Flashback.getConfig() != null && Flashback.getConfig().internalExport != null
                                && Flashback.getConfig().internalExport.deletePngsAfterMobileMp4) {
                                try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.list(dir)) {
                                    for (java.nio.file.Path frame : (Iterable<java.nio.file.Path>) stream::iterator) {
                                        String name = frame.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                                        if (name.endsWith(".png")) {
                                            java.nio.file.Files.deleteIfExists(frame);
                                        }
                                    }
                                }
                                mp4Message = "Flashback mobile: MP4 ready at " + mp4 + " (PNG frames deleted)";
                            }
                        } catch (Exception ignored) {}
                    } else {
                        mp4Message = "Flashback mobile: MP4 failed (exit " + exit + ", PNG frames kept at " + dir + "). ffmpeg log: " + log;
                    }
                    Flashback.LOGGER.info(mp4Message);
                    }
                }
            } catch (Exception e) {
                mp4Message = "Flashback mobile: MP4 failed (" + e.getMessage() + "), PNG frames kept at " + settings.output();
                Flashback.LOGGER.error(mp4Message, e);
            }
            String msg = mp4Message;
            postMobileChat(msg);
        }, "flashback-mobile-mp4");
        thread.setDaemon(true);
        thread.start();
    }

    private static void postMobileChat(String msg) {
        try {
            net.minecraft.client.Minecraft.getInstance().submit(
                () -> net.minecraft.client.Minecraft.getInstance().gui.getChat().addMessage(net.minecraft.network.chat.Component.literal(msg)));
        } catch (Exception ignored) {}
    }

    public static void applyMobileConfigDefaults(com.moulberry.flashback.configuration.FlashbackConfigV1 config) {
        if (config == null || config.internalExport == null) {
            return;
        }
        try {
            if (config.internalExport.resolution == null || config.internalExport.resolution.length < 2) {
                config.internalExport.resolution = new int[]{1920, 1080};
            } else {
                config.internalExport.resolution[0] = Math.min(config.internalExport.resolution[0], 1920);
                config.internalExport.resolution[1] = Math.min(config.internalExport.resolution[1], 1080);
                if (config.internalExport.resolution[0] < 320) config.internalExport.resolution[0] = 1920;
                if (config.internalExport.resolution[1] < 240) config.internalExport.resolution[1] = 1080;
            }
            if (config.internalExport.framerate == null || config.internalExport.framerate.length < 1) {
                config.internalExport.framerate = new float[]{60};
            } else {
                float fps = config.internalExport.framerate[0];
                if (!Float.isFinite(fps) || fps < 1) {
                    fps = 60;
                } else if (fps > 60) {
                    fps = 60;
                }
                config.internalExport.framerate[0] = fps;
            }
            config.internalExport.ssaa = false;
            config.internalExport.recordAudio = false;
            // Note: 0.39.x has no ExportProjection/projection field on internalExport;
            // the /flashback export_mobile handler always uses a plain perspective PNG sequence.
            if (config.internalExport.defaultExportPath == null || config.internalExport.defaultExportPath.isBlank()) {
                config.internalExport.defaultExportPath = defaultMobileExportDir().toString();
            }
            // Android only: record the hotbar/XP/food state by default so mobile viewers and
            // exports see the recorder's HUD state. The PC default in FlashbackConfigV1 stays false.
            if (isAndroid() && config.recording != null) {
                config.recording.recordHotbar = true;
            }
        } catch (Exception ignored) {}
    }
}
