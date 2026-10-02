package com.moulberry.flashback.exporting;

import com.moulberry.flashback.MobileCompat;
import com.moulberry.flashback.utils.NamedDaemonThreadFactory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.nfd.NFDFilterItem;
import org.lwjgl.util.nfd.NativeFileDialog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class AsyncFileDialogs {

    private static final Logger LOGGER = LoggerFactory.getLogger("flashback");
    private static final boolean ON_OSX = Util.getPlatform() == Util.OS.OSX;
    private static CompletableFuture<String> currentSaveOrOpenFileDialog = null;
    private static final ExecutorService dialogThread = Executors.newSingleThreadExecutor(new NamedDaemonThreadFactory("FlashbackFileDialogs"));
    private static final AtomicBoolean initializedNfd = new AtomicBoolean(false);

    public static boolean hasDialog() {
        return currentSaveOrOpenFileDialog != null;
    }

    public static CompletableFuture<String> saveFileDialog(String defaultPath, String defaultName, String filterDescription, String... filters) {
        if (hasDialog()) return CompletableFuture.completedFuture(null);
        if (MobileCompat.isAndroid() || !MobileCompat.isNativeFileDialogSupported()) {
            String fallback = mobileFallbackSavePath(defaultPath, defaultName, filters != null && filters.length > 0 ? filters[0] : null);
            LOGGER.info("NFD save dialog unavailable on Android, using fallback path {}", fallback);
            toastOnAndroid("Flashback", "File picker unavailable, saving to " + fallback);
            return CompletableFuture.completedFuture(fallback);
        }

        currentSaveOrOpenFileDialog = new CompletableFuture<>();
        CompletableFuture<String> future = currentSaveOrOpenFileDialog;

        Runnable runnable = () -> {
            if (MobileCompat.isAndroid()) {
                currentSaveOrOpenFileDialog.complete(mobileFallbackSavePath(defaultPath, defaultName,
                    filters != null && filters.length > 0 ? filters[0] : null));
                currentSaveOrOpenFileDialog = null;
                return;
            }
            try {
                if (initializedNfd.compareAndSet(false, true)) {
                    NativeFileDialog.NFD_Init();
                }
            } catch (LinkageError | Exception e) {
                LOGGER.error("NFD init failed, using fallback save path", e);
                currentSaveOrOpenFileDialog.complete(mobileFallbackSavePath(defaultPath, defaultName,
                    filters != null && filters.length > 0 ? filters[0] : null));
                currentSaveOrOpenFileDialog = null;
                return;
            }

            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer out = stack.callocPointer(1);

                StringBuilder filterBuilder = new StringBuilder();

                if (filters != null) {
                    for (String filter : filters) {
                        if (!filterBuilder.isEmpty()) filterBuilder.append(",");
                        filterBuilder.append(filter(filter));
                    }
                }

                NFDFilterItem.Buffer filtersBuffer = NFDFilterItem.malloc(1);
                filtersBuffer.get(0)
                        .name(stack.UTF8(filter(filterDescription)))
                        .spec(stack.UTF8(filterBuilder.toString()));

                int result = NativeFileDialog.NFD_SaveDialog(out, filtersBuffer, filter(defaultPath), filter(defaultName));

                if (result != NativeFileDialog.NFD_OKAY) {
                    currentSaveOrOpenFileDialog.complete(null);
                    currentSaveOrOpenFileDialog = null;
                } else {
                    currentSaveOrOpenFileDialog.complete(out.getStringUTF8(0));
                    currentSaveOrOpenFileDialog = null;
                    NativeFileDialog.NFD_FreePath(out.get(0));
                }
            } catch (LinkageError e) {
                LOGGER.error("NFD natives missing, using fallback save path", e);
                currentSaveOrOpenFileDialog.complete(mobileFallbackSavePath(defaultPath, defaultName,
                    filters != null && filters.length > 0 ? filters[0] : null));
                currentSaveOrOpenFileDialog = null;
            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                if (currentSaveOrOpenFileDialog != null) {
                    currentSaveOrOpenFileDialog.complete(null);
                    currentSaveOrOpenFileDialog = null;
                }
            }
        };

        if (ON_OSX) {
            // MacOS needs dialogs to be run from the main thread
            Minecraft.getInstance().submit(runnable);
        } else {
            dialogThread.submit(runnable);
        }

        return future;
    }

    public static CompletableFuture<String> openFileDialog(String defaultPath, String filterDescription, String... filters) {
        if (hasDialog()) return CompletableFuture.completedFuture(null);
        if (MobileCompat.isAndroid() || !MobileCompat.isNativeFileDialogSupported()) {
            LOGGER.info("NFD open dialog unavailable on Android, returning null");
            toastOnAndroid("Flashback", "File picker unavailable on Android");
            return CompletableFuture.completedFuture(null);
        }

        currentSaveOrOpenFileDialog = new CompletableFuture<>();
        CompletableFuture<String> future = currentSaveOrOpenFileDialog;

        Runnable runnable = () -> {
            if (MobileCompat.isAndroid()) {
                LOGGER.info("NFD open dialog unavailable on Android, returning null");
                currentSaveOrOpenFileDialog.complete(null);
                currentSaveOrOpenFileDialog = null;
                return;
            }
            try {
                if (initializedNfd.compareAndSet(false, true)) {
                    NativeFileDialog.NFD_Init();
                }
            } catch (LinkageError | Exception e) {
                LOGGER.error("NFD init failed", e);
                currentSaveOrOpenFileDialog.complete(null);
                currentSaveOrOpenFileDialog = null;
                return;
            }

            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer out = stack.callocPointer(1);

                StringBuilder filterBuilder = new StringBuilder();

                if (filters != null) {
                    for (String filter : filters) {
                        if (!filterBuilder.isEmpty()) filterBuilder.append(",");
                        filterBuilder.append(filter(filter));
                    }
                }

                NFDFilterItem.Buffer filtersBuffer = NFDFilterItem.malloc(1);
                filtersBuffer.get(0)
                             .name(stack.UTF8(filter(filterDescription)))
                             .spec(stack.UTF8(filterBuilder.toString()));

                int result = NativeFileDialog.NFD_OpenDialog(out, filtersBuffer, filter(defaultPath));

                if (result != NativeFileDialog.NFD_OKAY) {
                    currentSaveOrOpenFileDialog.complete(null);
                    currentSaveOrOpenFileDialog = null;
                } else {
                    currentSaveOrOpenFileDialog.complete(out.getStringUTF8(0));
                    currentSaveOrOpenFileDialog = null;
                    NativeFileDialog.NFD_FreePath(out.get(0));
                }
            } catch (LinkageError e) {
                LOGGER.error("NFD natives missing", e);
                currentSaveOrOpenFileDialog.complete(null);
                currentSaveOrOpenFileDialog = null;
            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                if (currentSaveOrOpenFileDialog != null) {
                    currentSaveOrOpenFileDialog.complete(null);
                    currentSaveOrOpenFileDialog = null;
                }
            }
        };

        if (ON_OSX) {
            // MacOS needs dialogs to be run from the main thread
            Minecraft.getInstance().submit(runnable);
        } else {
            dialogThread.submit(runnable);
        }

        return future;
    }

    public static CompletableFuture<String> openFolderDialog(String defaultPath) {
        if (hasDialog()) return CompletableFuture.completedFuture(null);
        if (MobileCompat.isAndroid() || !MobileCompat.isNativeFileDialogSupported()) {
            Path dir = MobileCompat.defaultMobileExportDir();
            LOGGER.info("NFD folder dialog unavailable on Android, using {}", dir);
            return CompletableFuture.completedFuture(dir.toString());
        }

        currentSaveOrOpenFileDialog = new CompletableFuture<>();
        CompletableFuture<String> future = currentSaveOrOpenFileDialog;

        boolean initializedNfd = AsyncFileDialogs.initializedNfd.getAndSet(true);

        Runnable runnable = () -> {
            if (MobileCompat.isAndroid()) {
                Path dir = MobileCompat.defaultMobileExportDir();
                currentSaveOrOpenFileDialog.complete(dir.toString());
                currentSaveOrOpenFileDialog = null;
                return;
            }
            if (!initializedNfd) {
                try {
                    NativeFileDialog.NFD_Init();
                } catch (LinkageError | Exception e) {
                    LOGGER.error("NFD init failed, using gameDir/flashback_exports", e);
                    Path dir = MobileCompat.defaultMobileExportDir();
                    currentSaveOrOpenFileDialog.complete(dir.toString());
                    currentSaveOrOpenFileDialog = null;
                    return;
                }
            }

            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer out = stack.callocPointer(1);

                int result = NativeFileDialog.NFD_PickFolder(out, filter(defaultPath));

                if (result != NativeFileDialog.NFD_OKAY) {
                    currentSaveOrOpenFileDialog.complete(null);
                    currentSaveOrOpenFileDialog = null;
                } else {
                    currentSaveOrOpenFileDialog.complete(out.getStringUTF8(0));
                    currentSaveOrOpenFileDialog = null;
                    NativeFileDialog.NFD_FreePath(out.get(0));
                }
            } catch (LinkageError e) {
                LOGGER.error("NFD natives missing, using gameDir/flashback_exports", e);
                Path dir = MobileCompat.defaultMobileExportDir();
                currentSaveOrOpenFileDialog.complete(dir.toString());
                currentSaveOrOpenFileDialog = null;
            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                if (currentSaveOrOpenFileDialog != null) {
                    currentSaveOrOpenFileDialog.complete(null);
                    currentSaveOrOpenFileDialog = null;
                }
            }
        };

        if (ON_OSX) {
            // MacOS needs dialogs to be run from the main thread
            Minecraft.getInstance().submit(runnable);
        } else {
            dialogThread.submit(runnable);
        }

        return future;
    }

    public static String filter(CharSequence in) {
        if (in == null) {
            return "";
        }
        return filterLT20(in.toString()
                .replace("'", "")
                .replace("\"", "")
                .replace("$", "")
                .replace("`", ""));
    }

    private static void toastOnAndroid(String title, String message) {
        if (!MobileCompat.isAndroid()) {
            return;
        }
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null) {
                return;
            }
            Component titleComponent = Component.literal(title);
            Component messageComponent = Component.literal(message);
            minecraft.submit(() -> {
                try {
                    SystemToast.add(minecraft.getToastManager(),
                        new SystemToast.SystemToastId(), titleComponent, messageComponent);
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    private static String mobileFallbackSavePath(String defaultPath, String defaultName, String extension) {
        try {
            Path base;
            if (defaultPath != null && !defaultPath.isEmpty()) {
                base = Path.of(defaultPath);
            } else {
                base = MobileCompat.defaultMobileExportDir();
            }
            java.nio.file.Files.createDirectories(base);
            String name = (defaultName == null || defaultName.isEmpty()) ? "flashback_export" : defaultName;
            if (extension != null && !extension.isEmpty() && !name.endsWith("." + extension)) {
                name = name + "." + extension;
            }
            Path candidate = base.resolve(name);
            int i = 1;
            while (java.nio.file.Files.exists(candidate)) {
                String stem = name;
                String ext = "";
                int dot = name.lastIndexOf('.');
                if (dot >= 0) {
                    stem = name.substring(0, dot);
                    ext = name.substring(dot);
                }
                candidate = base.resolve(stem + "_" + i + ext);
                i++;
            }
            return candidate.toString();
        } catch (Exception e) {
            return MobileCompat.defaultMobileExportDir().resolve("flashback_export.zip").toString();
        }
    }

    public static String filterLT20(CharSequence in) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < in.length(); i++) {
            char c = in.charAt(i);
            if (c >= 32 || c == '\n') builder.append(c);
        }
        return builder.toString();
    }

}
