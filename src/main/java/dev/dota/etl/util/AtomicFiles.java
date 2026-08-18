package dev.dota.etl.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

/** File helpers that preserve an existing output until its replacement is complete. */
public final class AtomicFiles {

    public static Path createTempSibling(Path target) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, "." + target.getFileName() + "-", ".tmp");
        try {
            Set<PosixFilePermission> permissions = Files.exists(target)
                ? Files.getPosixFilePermissions(target)
                : EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ);
            Files.setPosixFilePermissions(temp, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX file system.
        }
        return temp;
    }

    public static void replace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void writeString(Path target, String content) throws IOException {
        Path temp = createTempSibling(target);
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            replace(temp, target);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private AtomicFiles() {
    }
}
