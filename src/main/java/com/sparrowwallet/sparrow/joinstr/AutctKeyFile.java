package com.sparrowwallet.sparrow.joinstr;

import org.bouncycastle.crypto.digests.Blake2bDigest;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.modes.GCMSIVBlockCipher;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.bouncycastle.crypto.params.KeyParameter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Writes a proving key in the format aut-ct expects.
 *
 * aut-ct decrypts the key file before parsing it, so a plaintext WIF is refused. The format is
 * from aut-ct's own encryption.rs: Argon2i derives the key, AES-256-GCM-SIV encrypts the WIF, and
 * the pieces are laid out as a little endian u64 length, the ciphertext, a 12 byte nonce and a
 * 32 byte salt.
 */
public final class AutctKeyFile {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static final int SALT_LENGTH = 32;
    private static final int NONCE_LENGTH = 12;
    private static final int KEY_LENGTH = 32;
    private static final int ITERATIONS = 3;
    private static final int MEMORY_KB = 4096;
    private static final int PARALLELISM = 1;

    private AutctKeyFile() {
    }

    public static String randomPassword() {
        byte[] password = new byte[16];
        SECURE_RANDOM.nextBytes(password);
        return HexFormat.of().formatHex(password);
    }

    /** The encrypted file body for a WIF. */
    static byte[] encrypt(String wif, String password, byte[] salt, byte[] nonce) {
        Argon2BytesGenerator argon2 = new Argon2BytesGenerator();
        argon2.init(new Argon2Parameters.Builder(Argon2Parameters.ARGON2_i)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(ITERATIONS)
                .withMemoryAsKB(MEMORY_KB)
                .withParallelism(PARALLELISM)
                .withSalt(salt)
                .build());

        byte[] key = new byte[KEY_LENGTH];
        argon2.generateBytes(password.getBytes(StandardCharsets.UTF_8), key);

        GCMSIVBlockCipher cipher = new GCMSIVBlockCipher(AESEngine.newInstance());
        cipher.init(true, new AEADParameters(new KeyParameter(key), 128, nonce));

        byte[] plaintext = wif.getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext = new byte[cipher.getOutputSize(plaintext.length)];
        int written = cipher.processBytes(plaintext, 0, plaintext.length, ciphertext, 0);
        try {
            written += cipher.doFinal(ciphertext, written);
        } catch (Exception e) {
            throw new IllegalStateException("could not encrypt the proving key", e);
        }

        ByteBuffer body = ByteBuffer.allocate(8 + written + NONCE_LENGTH + SALT_LENGTH)
                .order(ByteOrder.LITTLE_ENDIAN);
        body.putLong(written);
        body.put(ciphertext, 0, written);
        body.put(nonce);
        body.put(salt);
        return body.array();
    }

    /** Write the proving key to a temporary file only this user can read. */
    public static Path write(String wif, String password) throws IOException {
        byte[] salt = new byte[SALT_LENGTH];
        byte[] nonce = new byte[NONCE_LENGTH];
        SECURE_RANDOM.nextBytes(salt);
        SECURE_RANDOM.nextBytes(nonce);

        Path file;
        try {
            file = Files.createTempFile("joinstr-proving", ".enc",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
            file = Files.createTempFile("joinstr-proving", ".enc");
        }

        Files.write(file, encrypt(wif, password, salt, nonce));
        return file;
    }

    public static void delete(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // a proving key left in the temp directory is worth a note but not a failure
            java.util.logging.Logger.getLogger(AutctKeyFile.class.getName())
                    .warning("Could not delete a temporary proving key file");
        }
    }
}
