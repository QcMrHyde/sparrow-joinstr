package com.sparrowwallet.sparrow.joinstr;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.*;

/**
 * aut-ct decrypts the proving key file before parsing it, so the layout has to match its
 * encryption.rs exactly: a little endian u64 length, the ciphertext, a 12 byte nonce and a 32
 * byte salt, with Argon2i deriving the key for AES-256-GCM-SIV.
 *
 * A wrong layout shows up as error -14 from the server rather than anything readable.
 */
public class AutctKeyFileTest {

    private static final String WIF = "cW3AL8HXBSTonEeCkFhLxrLXmEXtnZfPsZBNSSpiVR9zZnkRJJmy";

    private byte[] body() {
        byte[] salt = new byte[32];
        byte[] nonce = new byte[12];
        for(int i = 0; i < salt.length; i++) {
            salt[i] = (byte) i;
        }
        for(int i = 0; i < nonce.length; i++) {
            nonce[i] = (byte) (0x40 + i);
        }
        return AutctKeyFile.encrypt(WIF, "hunter2", salt, nonce);
    }

    @Test
    public void theLayoutIsLengthCiphertextNonceSalt() {
        byte[] body = body();

        long length = ByteBuffer.wrap(body, 0, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();

        assertEquals(WIF.length() + 16, length, "ciphertext is the wif plus a 16 byte tag");
        assertEquals(8 + length + 12 + 32, body.length, "trailing nonce and salt are missing");
    }

    @Test
    public void theNonceAndSaltAreCarriedVerbatim() {
        byte[] body = body();
        int ciphertextEnd = (int) (8 + ByteBuffer.wrap(body, 0, 8).order(ByteOrder.LITTLE_ENDIAN).getLong());

        for(int i = 0; i < 12; i++) {
            assertEquals((byte) (0x40 + i), body[ciphertextEnd + i], "nonce byte " + i);
        }
        for(int i = 0; i < 32; i++) {
            assertEquals((byte) i, body[ciphertextEnd + 12 + i], "salt byte " + i);
        }
    }

    /** The same key and nonce must give the same bytes, or the format is not deterministic. */
    @Test
    public void encryptionIsDeterministicForFixedSaltAndNonce() {
        assertArrayEquals(body(), body());
    }

    @Test
    public void adifferentPasswordGivesDifferentCiphertext() {
        byte[] salt = new byte[32];
        byte[] nonce = new byte[12];

        assertFalse(java.util.Arrays.equals(
                AutctKeyFile.encrypt(WIF, "one", salt, nonce),
                AutctKeyFile.encrypt(WIF, "two", salt, nonce)));
    }

    @Test
    public void theFileIsWrittenOwnerReadableOnly() throws Exception {
        Path file = AutctKeyFile.write(WIF, "hunter2");
        try {
            assertTrue(Files.exists(file));
            if(file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                assertEquals(PosixFilePermissions.fromString("rw-------"),
                        Files.getPosixFilePermissions(file), "a proving key was left readable");
            }
            assertTrue(Files.size(file) > 40);
        } finally {
            AutctKeyFile.delete(file);
            assertFalse(Files.exists(file), "the proving key file was left behind");
        }
    }

    @Test
    public void eachPasswordIsFresh() {
        assertNotEquals(AutctKeyFile.randomPassword(), AutctKeyFile.randomPassword());
        assertEquals(32, AutctKeyFile.randomPassword().length());
    }
}
