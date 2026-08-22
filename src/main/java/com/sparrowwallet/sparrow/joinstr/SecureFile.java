package com.sparrowwallet.sparrow.joinstr;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Writes a file only its owner can read. The joinstr pool store holds the nostr private key of
 * every pool, which is the key to that pool's encrypted channel.
 */
public final class SecureFile {

    static final String OWNER_ONLY = "rw-------";

    private SecureFile() {
    }

    public static void write(String filePath, String content) throws IOException {
        Path path = Paths.get(filePath);

        if (!Files.exists(path)) {
            createRestricted(path);
        } else {
            restrict(path);
        }

        Files.writeString(path, content, StandardCharsets.UTF_8,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static void createRestricted(Path path) throws IOException {
        if (supportsPosix(path)) {
            Files.createFile(path,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(OWNER_ONLY)));
        } else {
            Files.createFile(path);
            restrictWithFileApi(path);
        }
    }

    private static void restrict(Path path) throws IOException {
        if (supportsPosix(path)) {
            Set<PosixFilePermission> permissions = PosixFilePermissions.fromString(OWNER_ONLY);
            if (!permissions.equals(Files.getPosixFilePermissions(path))) {
                Files.setPosixFilePermissions(path, permissions);
            }
        } else {
            restrictWithFileApi(path);
        }
    }

    /** Best effort on a filesystem without posix permissions, such as Windows. */
    private static void restrictWithFileApi(Path path) {
        File file = path.toFile();
        file.setReadable(false, false);
        file.setWritable(false, false);
        file.setExecutable(false, false);
        file.setReadable(true, true);
        file.setWritable(true, true);
    }

    private static boolean supportsPosix(Path path) {
        return path.getFileSystem().supportedFileAttributeViews().contains("posix");
    }
}
