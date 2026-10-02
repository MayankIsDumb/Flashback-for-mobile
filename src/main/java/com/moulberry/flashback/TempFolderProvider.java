package com.moulberry.flashback;

import net.fabricmc.loader.api.FabricLoader;
import org.apache.commons.io.FileUtils;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class TempFolderProvider {

    public enum TempFolderType {
        SERVER("server"),
        RECORDING("recording");

        private final String id;

        TempFolderType(String id) {
            this.id = id;
        }
    }

    private static Path getSharedTempFolder() {
        return Flashback.getDataDirectory().resolve("temp");
    }

    public static Path getTypedTempFolder(TempFolderType type) {
        return getSharedTempFolder().resolve(type.id);
    }

    public static void tryDeleteStaleFolders(TempFolderType type) {
        Path tempFolder;
        try {
            tempFolder = getSharedTempFolder();
        } catch (Exception e) {
            Flashback.LOGGER.error("Failed to resolve temp folder", e);
            return;
        }

        try {
            if (!Files.exists(tempFolder)) {
                return;
            }
        } catch (SecurityException e) {
            Flashback.LOGGER.error("No permission to access temp folder", e);
            return;
        }

        Path typedTempFolder;
        try {
            typedTempFolder = tempFolder.resolve(type.id);
            if (!Files.exists(typedTempFolder)) {
                return;
            }
        } catch (SecurityException e) {
            Flashback.LOGGER.error("No permission to access typed temp folder", e);
            return;
        }

        Set<Path> toDelete = new HashSet<>();

        try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(typedTempFolder)) {
            for (Path path : directoryStream) {
                boolean isDirectory;
                try {
                    isDirectory = Files.isDirectory(path);
                } catch (SecurityException e) {
                    Flashback.LOGGER.error("No permission to inspect temp folder {}", path, e);
                    continue;
                }
                if (!isDirectory) {
                    continue;
                }

                Path lockFile = path.resolve("flashback_pid");

                String pidStr = "?";
                boolean isStillInUse = false;
                try {
                    pidStr = Files.readString(lockFile);
                    isStillInUse = MobileCompat.isPidAlive(pidStr);
                } catch (Exception | LinkageError ignored) {}

                if (!isStillInUse) {
                    toDelete.add(path);
                } else {
                    Flashback.LOGGER.error("Cannot delete stale temp folder {}, pid {} is still in use", tempFolder.relativize(path), pidStr);
                }
            }
        } catch (IOException | SecurityException e) {
            Flashback.LOGGER.error("Failed to find stale temp folders to delete", e);
        } catch (Exception | LinkageError e) {
            Flashback.LOGGER.error("Failed to find stale temp folders to delete", e);
        }

        for (Path path : toDelete) {
            Flashback.LOGGER.info("Deleting stale temp folder {}", tempFolder.relativize(path));
            try {
                FileUtils.deleteDirectory(path.toFile());
            } catch (Exception e) {
                Flashback.LOGGER.error("Failed to delete stale temp folder", e);
            }
        }

        deleteDirectoryIfEmpty(typedTempFolder);
        deleteDirectoryIfEmpty(tempFolder);
    }

    public static Path createTemp(TempFolderType type, UUID uuid) {
        Path path = getTempPath(type, uuid);

        if (!Files.exists(path)) {
            // Create directories
            try {
                Files.createDirectories(path);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            // Write pid file (UUID token fallback on old Android without ProcessHandle)
            String pidToken = MobileCompat.newPidToken();
            try {
                Files.writeString(path.resolve("flashback_pid"), pidToken);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        return path;
    }

    private static Path getTempPath(TempFolderType type, UUID uuid) {
        return getSharedTempFolder()
                .resolve(type.id)
                .resolve(uuid.toString());
    }

    public static void deleteTemp(TempFolderType type, UUID uuid) {
        Path path = getTempPath(type, uuid);

        // Delete directory
        try {
            FileUtils.deleteDirectory(path.toFile());
        } catch (Exception e) {
            Flashback.LOGGER.error("Failed to delete temp directory", e);
        }

        deleteDirectoryIfEmpty(path.getParent());
        deleteDirectoryIfEmpty(path.getParent().getParent());
    }

    public static void deleteDirectoryIfEmpty(Path path) {
        if (!Files.exists(path)) {
            return;
        }

        try {
            boolean empty;
            try (var stream = Files.newDirectoryStream(path)) {
                empty = !stream.iterator().hasNext();
            }
            if (empty) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {}
    }

}
