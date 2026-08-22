package com.sparrowwallet.sparrow.joinstr;

import org.bouncycastle.math.ec.ECPoint;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross implementation vectors, produced by running the reference implementation's own lrs.py
 * with a deterministic random source. A ring signature is only useful here if both clients agree
 * on it exactly, so these pin the wire encoding and every intermediate hash, not just that
 * signing and verifying agree with themselves.
 */
public class LsagTest {

    private static final List<String> PUBS = List.of("02d53c88a0a81f9c3a80638fa81e477625d49aca04568139072c87a36e47ecb96a", "02530247b68e147562021f66c0ffdc08b483104902ca6ccdc514e67c7b5530072c", "0329842957645000d91e22fbb369f953edd1f1afc6cf788cf314ea53c390720cec", "02758d708f7d9ba2e4c8636b8570f80b4d4045e9dba95529106cf4bce13f245c2c");
    private static final List<String> PRIVS = List.of("00000000000000000000000000000000000000000000000000000000000f4243", "00000000000000000000000000000000000000000000000000000000000f4244", "00000000000000000000000000000000000000000000000000000000000f4245", "00000000000000000000000000000000000000000000000000000000000f4246");
    private static final String MESSAGE_HEX = "8dbe01bf6007295f60883218d8ba0181fc797976c1e0f04159a86e41df153852";
    private static final int SIGNER = 2;

    private static final String REF_Y0 = "038866906b4a661234e479319636779381416a18c5025746790ae8c76b5fbf3fec";
    private static final List<String> REF_S = List.of("0x6a414d970401bdede7ab9c36ac6065d5a7d188ae3914a5684791bd288f0337cf", "0x3f7c6d0c984f549755bb2cb6ffcb93c597b7144f556855ecb67b562300816f90", "0x7054e8bbf0c0df229653d097d8802c40d5a3b90027fe135098f0874fb3697014", "0x26c0fd48e152604affe6c1d695496cba197bc0e138c6b0dff9a8b2b14a48a0af");
    private static final List<String> REF_C = List.of("0xd6aa60d47db42ab6b8073b422d7dd51ecd11558396234e015e26b39f6819deed", "0x351f35419258c647c8ded1c7a936b5db1b7260ad9cd4e692212b25163030c17", "0x9a987717797bdd132f7dd1f0452312d56ad849c75ddcb9d033ae24fafc603dce", "0xe730a9d3b9e0e63effb59edbd338329302a49f267d7a9d52e2c1d2faab6a49d8");

    private byte[] message() {
        return bytes(MESSAGE_HEX);
    }

    private static byte[] bytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for(int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for(byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** The whole point: a signature made by the reference implementation must verify here. */
    @Test
    public void verifiesASignatureMadeByTheReferenceImplementation() {
        Lsag.Signature reference = new Lsag.Signature(REF_Y0, REF_S, REF_C);

        assertTrue(Lsag.verify(message(), reference, PUBS));
    }

    @Test
    public void rejectsTheReferenceSignatureAgainstADifferentMessage() {
        Lsag.Signature reference = new Lsag.Signature(REF_Y0, REF_S, REF_C);

        assertFalse(Lsag.verify(bytes("8dbe01bf6007295f60883218d8ba0181fc797976c1e0f04159a86e41df153852".replace("00", "11")), reference, PUBS));
    }

    @Test
    public void rejectsTheReferenceSignatureAgainstADifferentRing() {
        Lsag.Signature reference = new Lsag.Signature(REF_Y0, REF_S, REF_C);
        List<String> otherRing = new ArrayList<>(PUBS);
        otherRing.set(0, PUBS.get(1));

        assertFalse(Lsag.verify(message(), reference, otherRing));
    }

    @Test
    public void rejectsATamperedScalar() {
        List<String> tampered = new ArrayList<>(REF_S);
        tampered.set(0, "0x01");

        assertFalse(Lsag.verify(message(), new Lsag.Signature(REF_Y0, tampered, REF_C), PUBS));
    }

    @Test
    public void hashToPointMatchesTheReferenceImplementation() {
        ECPoint point = Lsag.hashToPoint(bytes("02d53c88a0a81f9c3a80638fa81e477625d49aca04568139072c87a36e47ecb96a"));

        assertEquals("02c796716162e413d0f726f82f714cc2230bd9f1b6cce8e76320ed574fb1cd0aca", hex(point.getEncoded(true)));
    }

    /** A digest whose first candidate is off the curve, so the nonce loop has to advance. */
    @Test
    public void hashToPointMatchesTheReferenceWhenNoncesAreNeeded() {
        ECPoint point = Lsag.hashToPoint(bytes("0000000000000000000000000000000000000000000000000000000000000000"));

        assertEquals("026db65fd59fd356f6729140571b5bcd6bb3b83492a16e1bf0a3884442fc3c8a0e", hex(point.getEncoded(true)));
    }

    @Test
    public void hashPointsOverTheRingMatchesTheReferenceImplementation() {
        List<Object> items = new ArrayList<>();
        for(String pub : PUBS) {
            items.add(bytes(pub));
        }

        assertEquals(Lsag.parseScalar("0xa476e03164ee1225d69c2c39b31a3c2abafcdbccab25111be106f1cd4e661941"), Lsag.hashPoints(items));
    }

    /**
     * The mixed case matters most: the reference feeds the ring digest in as an integer, which it
     * hashes as its decimal digits, and the key image as a hex string, which it hashes as bytes.
     */
    @Test
    public void hashPointsOverMixedTypesMatchesTheReferenceImplementation() {
        List<Object> items = new ArrayList<>();
        List<Object> ring = new ArrayList<>();
        for(String pub : PUBS) {
            ring.add(bytes(pub));
        }
        items.add(Lsag.hashPoints(ring));
        items.add(REF_Y0);
        items.add(message());
        items.add(bytes(PUBS.get(1)));

        assertEquals(Lsag.parseScalar("0xef29075acae8a11cd93c9243d29efd8653a95c6ca4e54d2b81bd49ac7f41ad45"), Lsag.hashPoints(items));
    }

    @Test
    public void aSignatureMadeHereVerifiesHere() {
        Lsag.Signature signature = Lsag.sign(message(), PUBS, PRIVS.get(SIGNER), SIGNER);

        assertTrue(Lsag.verify(message(), signature, PUBS));
        assertEquals(PUBS.size(), signature.getS().size());
        assertEquals(PUBS.size(), signature.getC().size());
    }

    /** Two signatures by one key share a key image, which is what stops a double registration. */
    @Test
    public void twoSignaturesByOneKeyShareAKeyImage() {
        Lsag.Signature first = Lsag.sign(message(), PUBS, PRIVS.get(SIGNER), SIGNER);
        Lsag.Signature second = Lsag.sign(bytes("8dbe01bf6007295f60883218d8ba0181fc797976c1e0f04159a86e41df153852"), PUBS, PRIVS.get(SIGNER), SIGNER);

        assertEquals(first.keyImage(), second.keyImage());
        assertEquals(REF_Y0, first.keyImage());
    }

    @Test
    public void differentKeysGiveDifferentKeyImages() {
        Lsag.Signature first = Lsag.sign(message(), PUBS, PRIVS.get(0), 0);
        Lsag.Signature second = Lsag.sign(message(), PUBS, PRIVS.get(1), 1);

        assertNotEquals(first.keyImage(), second.keyImage());
    }

    /** Signing as a member you do not hold the key for must not produce a valid signature. */
    @Test
    public void signingAsTheWrongRingMemberDoesNotVerify() {
        Lsag.Signature signature = Lsag.sign(message(), PUBS, PRIVS.get(0), 1);

        assertFalse(Lsag.verify(message(), signature, PUBS));
    }

    @Test
    public void onlyTheCanonicalKeyImageSpellingIsAccepted() {
        assertEquals(REF_Y0, Lsag.canonicalKeyImage(REF_Y0));
        assertEquals(REF_Y0, Lsag.canonicalKeyImage(REF_Y0.toUpperCase()));
        assertNull(Lsag.canonicalKeyImage("not hex"));
        assertNull(Lsag.canonicalKeyImage(""));

        // an uppercase image decodes to the same point, so accepting it would let one signer
        // present unlimited distinct looking images for a single key
        assertFalse(Lsag.verify(message(),
                new Lsag.Signature(REF_Y0.toUpperCase(), REF_S, REF_C), PUBS));
    }

    @Test
    public void scalarsUseTheReferenceWireEncoding() {
        // python's hex(): lowercase, "0x" prefixed, no leading zeros
        assertEquals("0x0", Lsag.toWireScalar(BigInteger.ZERO));
        assertEquals("0xff", Lsag.toWireScalar(BigInteger.valueOf(255)));
        assertEquals(BigInteger.valueOf(255), Lsag.parseScalar("0xff"));
        assertEquals(BigInteger.valueOf(255), Lsag.parseScalar("ff"));

        for(String scalar : REF_S) {
            assertTrue(scalar.startsWith("0x"), scalar);
            assertEquals(scalar, Lsag.toWireScalar(Lsag.parseScalar(scalar)));
        }
    }

    @Test
    public void aMalformedSignatureIsRejectedRatherThanThrowing() {
        assertFalse(Lsag.verify(message(), null, PUBS));
        assertFalse(Lsag.verify(message(), new Lsag.Signature(REF_Y0, List.of("0x1"), REF_C), PUBS));
        assertFalse(Lsag.verify(message(), new Lsag.Signature("00", REF_S, REF_C), PUBS));
        assertFalse(Lsag.verify(message(), new Lsag.Signature(REF_Y0, REF_S, REF_C), List.of()));
    }
}
