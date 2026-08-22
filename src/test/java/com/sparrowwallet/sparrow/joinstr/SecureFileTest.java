package com.sparrowwallet.sparrow.joinstr;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The pool store holds the nostr private key of every pool, which is the key to that pool's
 * encrypted channel. It used to be written through a plain FileWriter, so it landed at the
 * process umask and was typically readable by any local user.
 */
public class SecureFileTest {

    private boolean posix(Path path) {
        return path.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    @Test
    public void aNewFileIsOwnerReadableOnly(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("pools.json");
        SecureFile.write(file.toString(), "{\"poolsList\":[]}");

        assumeTrue(posix(file), "no posix permissions on this filesystem");
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
    }

    @Test
    public void contentRoundTrips(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("pools.json");
        String content = "{\"poolsList\":[{\"privateKey\":\"deadbeef\"}]}";

        SecureFile.write(file.toString(), content);

        assertEquals(content, Files.readString(file));
    }

    /** A store written by an older version is already world readable, so tighten it on write. */
    @Test
    public void anExistingWorldReadableFileIsTightened(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("pools.json");
        Files.writeString(file, "old");
        assumeTrue(posix(file), "no posix permissions on this filesystem");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

        SecureFile.write(file.toString(), "new");

        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
        assertEquals("new", Files.readString(file));
    }

    @Test
    public void rewritingTruncates(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("pools.json");

        SecureFile.write(file.toString(), "a much longer original payload");
        SecureFile.write(file.toString(), "short");

        assertEquals("short", Files.readString(file));
    }

    @Test
    public void groupAndOtherNeverGetAccess(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("pools.json");
        SecureFile.write(file.toString(), "{}");

        assumeTrue(posix(file), "no posix permissions on this filesystem");
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
        for(PosixFilePermission denied : new PosixFilePermission[] {
                PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE}) {
            assertFalse(permissions.contains(denied), denied + " must not be granted");
        }
    }
}
