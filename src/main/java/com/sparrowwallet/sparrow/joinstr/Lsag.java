package com.sparrowwallet.sparrow.joinstr;

import com.sparrowwallet.drongo.crypto.ECKey;

import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.util.BigIntegers;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * Linkable Spontaneous Anonymous Group signatures over secp256k1.
 *
 * Used by the coinjoin recovery phase: when a pool loses a peer during input registration, the
 * peers that did register re-register their outputs under a signature proving membership of the
 * ring of Phase 2 keys, without saying which member they are. The key image links two signatures
 * by the same key, so nobody can claim two outputs.
 *
 * This is a port of the reference implementation's lrs.py and has to agree with it byte for byte,
 * including the scalar encoding on the wire.
 */
public final class Lsag {

    private static final BigInteger CURVE_ORDER = ECKey.CURVE.getN();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** A signature and the key image that links it to its signer. */
    public static final class Signature {
        private final String y0;
        private final List<String> s;
        private final List<String> c;

        public Signature(String y0, List<String> s, List<String> c) {
            this.y0 = y0;
            this.s = List.copyOf(s);
            this.c = List.copyOf(c);
        }

        public String getY0() {
            return y0;
        }

        public List<String> getS() {
            return s;
        }

        public List<String> getC() {
            return c;
        }

        /** The key image is the Y0 point; two signatures by one key share it. */
        public String keyImage() {
            return y0;
        }
    }

    private Lsag() {
    }

    /**
     * The compressed lowercase encoding of a key image, or null if it is not a point.
     *
     * Hex decoding ignores case, so one key image has many spellings that all verify. Signatures
     * are linked by comparing the key image, so only the canonical spelling is accepted; otherwise
     * one signer supplies unlimited distinct looking images for a single key.
     */
    public static String canonicalKeyImage(String y0Hex) {
        try {
            return encode(decodePoint(y0Hex));
        } catch (Exception e) {
            return null;
        }
    }

    /** Deterministically map bytes onto a curve point, trying successive nonces. */
    public static ECPoint hashToPoint(byte[] data) {
        for (long nonce = 0; nonce < Integer.MAX_VALUE; nonce++) {
            byte[] candidate = sha256(concat(data, ByteBuffer.allocate(4).putInt((int) nonce).array()));
            byte[] pointBytes = concat(new byte[] {0x02}, candidate);
            try {
                ECPoint point = ECKey.CURVE.getCurve().decodePoint(pointBytes);
                if (point.isValid()) {
                    return point.normalize();
                }
            } catch (Exception e) {
                // not on the curve, try the next nonce
            }
        }
        throw new IllegalStateException("no curve point found for the given data");
    }

    /**
     * The challenge hash, reduced to a scalar.
     *
     * Items are fed in as the reference implementation feeds them: a point as its compressed
     * encoding, a scalar as its decimal digits, a string as its hex bytes when it decodes as hex
     * and as its utf8 bytes when it does not, and raw bytes as themselves.
     */
    public static BigInteger hashPoints(List<Object> items) {
        MessageDigest digest = newSha256();
        for (Object item : items) {
            if (item instanceof ECPoint point) {
                digest.update(encodeBytes(point));
            } else if (item instanceof BigInteger scalar) {
                digest.update(scalar.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } else if (item instanceof byte[] bytes) {
                digest.update(bytes);
            } else if (item instanceof String text) {
                byte[] hex = tryDecodeHex(text);
                digest.update(hex != null ? hex : text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } else {
                digest.update(String.valueOf(item).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return new BigInteger(1, digest.digest()).mod(CURVE_ORDER);
    }

    /** Sign as ring member {@code identity}, whose public key must be {@code publicKeys.get(identity)}. */
    public static Signature sign(byte[] message, List<String> publicKeys, String privateKeyHex, int identity) {
        int n = publicKeys.size();
        if (n == 0 || identity < 0 || identity >= n) {
            throw new IllegalArgumentException("signer is not a member of the ring");
        }

        List<ECPoint> ring = new ArrayList<>(n);
        for (String publicKey : publicKeys) {
            ring.add(decodePoint(publicKey));
        }

        BigInteger privateKey = new BigInteger(1, hexToBytes(privateKeyHex));
        ECPoint hpSigner = hashToPoint(encodeBytes(ring.get(identity)));
        ECPoint y0Point = hpSigner.multiply(privateKey).normalize();
        String y0 = encode(y0Point);

        BigInteger[] c = new BigInteger[n];
        BigInteger[] s = new BigInteger[n];
        for (int i = 0; i < n; i++) {
            c[i] = BigInteger.ZERO;
            s[i] = BigInteger.ZERO;
        }

        BigInteger u = randomScalar();
        BigInteger ringDigest = ringDigest(ring);

        ECPoint lPrime = ECKey.CURVE.getG().multiply(u).normalize();
        ECPoint rPrime = hpSigner.multiply(u).normalize();

        int idx = (identity + 1) % n;
        c[idx] = challenge(ringDigest, y0, message, lPrime, rPrime);

        while (idx != identity) {
            s[idx] = randomScalar();

            ECPoint sg = ECKey.CURVE.getG().multiply(s[idx]);
            ECPoint cp = ring.get(idx).multiply(c[idx]);
            lPrime = sg.add(cp).normalize();

            ECPoint hpI = hashToPoint(encodeBytes(ring.get(idx)));
            ECPoint shp = hpI.multiply(s[idx]);
            ECPoint cY0 = y0Point.multiply(c[idx]);
            rPrime = shp.add(cY0).normalize();

            idx = (idx + 1) % n;
            c[idx] = challenge(ringDigest, y0, message, lPrime, rPrime);
        }

        s[identity] = u.subtract(c[identity].multiply(privateKey)).mod(CURVE_ORDER);

        return new Signature(y0, toHexList(s), toHexList(c));
    }

    /** Whether this signature was made by some member of the ring. */
    public static boolean verify(byte[] message, Signature signature, List<String> publicKeys) {
        int n = publicKeys.size();
        if (signature == null || n == 0 || signature.getS().size() != n || signature.getC().size() != n) {
            return false;
        }

        if (!signature.getY0().equals(canonicalKeyImage(signature.getY0()))) {
            return false;
        }

        List<ECPoint> ring = new ArrayList<>(n);
        ECPoint y0Point;
        BigInteger[] s = new BigInteger[n];
        BigInteger[] c = new BigInteger[n];
        try {
            for (String publicKey : publicKeys) {
                ring.add(decodePoint(publicKey));
            }
            y0Point = decodePoint(signature.getY0());
            for (int i = 0; i < n; i++) {
                s[i] = parseScalar(signature.getS().get(i));
                c[i] = parseScalar(signature.getC().get(i));
            }
        } catch (Exception e) {
            return false;
        }

        BigInteger ringDigest = ringDigest(ring);

        for (int i = 0; i < n; i++) {
            ECPoint sg = ECKey.CURVE.getG().multiply(s[i]);
            ECPoint cp = ring.get(i).multiply(c[i]);
            ECPoint lPrime = sg.add(cp).normalize();

            ECPoint hpI = hashToPoint(encodeBytes(ring.get(i)));
            ECPoint shp = hpI.multiply(s[i]);
            ECPoint cY0 = y0Point.multiply(c[i]);
            ECPoint rPrime = shp.add(cY0).normalize();

            BigInteger next = challenge(ringDigest, signature.getY0(), message, lPrime, rPrime);
            if (!next.equals(c[(i + 1) % n])) {
                return false;
            }
        }

        return true;
    }

    private static BigInteger ringDigest(List<ECPoint> ring) {
        List<Object> items = new ArrayList<>(ring.size());
        for (ECPoint point : ring) {
            items.add(encodeBytes(point));
        }
        return hashPoints(items);
    }

    private static BigInteger challenge(BigInteger ringDigest, String y0, byte[] message,
            ECPoint lPrime, ECPoint rPrime) {
        return hashPoints(List.of(ringDigest, y0, message, encodeBytes(lPrime), encodeBytes(rPrime)));
    }

    /** Scalars go on the wire the way python's hex() writes them: "0x" and no leading zeros. */
    static String toWireScalar(BigInteger value) {
        return "0x" + value.toString(16);
    }

    static BigInteger parseScalar(String value) {
        String text = value.trim();
        if (text.startsWith("0x") || text.startsWith("0X")) {
            text = text.substring(2);
        }
        return new BigInteger(text, 16);
    }

    private static List<String> toHexList(BigInteger[] values) {
        List<String> hex = new ArrayList<>(values.length);
        for (BigInteger value : values) {
            hex.add(toWireScalar(value));
        }
        return hex;
    }

    private static BigInteger randomScalar() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return new BigInteger(1, bytes).mod(CURVE_ORDER);
    }

    private static ECPoint decodePoint(String hex) {
        ECPoint point = ECKey.CURVE.getCurve().decodePoint(hexToBytes(hex));
        if (!point.isValid()) {
            throw new IllegalArgumentException("point is not on the curve");
        }
        return point.normalize();
    }

    private static byte[] encodeBytes(ECPoint point) {
        return point.normalize().getEncoded(true);
    }

    private static String encode(ECPoint point) {
        return bytesToHex(encodeBytes(point));
    }

    private static byte[] hexToBytes(String hex) {
        String text = hex.trim();
        if (text.length() % 2 != 0) {
            throw new IllegalArgumentException("odd length hex");
        }
        byte[] bytes = new byte[text.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    private static byte[] tryDecodeHex(String text) {
        try {
            return hexToBytes(text);
        } catch (Exception e) {
            return null;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] combined = new byte[first.length + second.length];
        System.arraycopy(first, 0, combined, 0, first.length);
        System.arraycopy(second, 0, combined, first.length, second.length);
        return combined;
    }

    private static byte[] sha256(byte[] data) {
        return newSha256().digest(data);
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    static byte[] scalarBytes(BigInteger value) {
        return BigIntegers.asUnsignedByteArray(32, value);
    }
}
