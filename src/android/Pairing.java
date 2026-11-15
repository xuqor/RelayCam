package dev.relaycam.android;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Locale;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.SecretKeySpec;

final class Pairing {
    static final int MAGIC = 0x52434D33;
    static final int PUBLIC_SIZE = 64;
    static final int APPROVAL_SECONDS = 40;
    private final KeyPair keys;

    static byte[] proof(byte[] trust, byte[] session, String role) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(trust, "HmacSHA256"));
        mac.update(role.getBytes(StandardCharsets.US_ASCII));
        return mac.doFinal(session);
    }

    Pairing() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        keys = generator.generateKeyPair();
    }

    byte[] publicKey() {
        ECPoint point = ((ECPublicKey) keys.getPublic()).getW();
        byte[] result = new byte[PUBLIC_SIZE];
        writeCoordinate(point.getAffineX(), result, 0);
        writeCoordinate(point.getAffineY(), result, 32);
        return result;
    }

    private static void writeCoordinate(BigInteger coordinate, byte[] result, int offset) {
        byte[] value = coordinate.toByteArray();
        int copy = Math.min(32, value.length);
        System.arraycopy(value, value.length - copy, result, offset + 32 - copy, copy);
    }

    static byte[] commit(byte[] publicKey) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(publicKey);
    }

    Result finish(byte[] clientPublic, byte[] serverPublic) throws Exception {
        ECPoint point = new ECPoint(new BigInteger(1, Arrays.copyOfRange(clientPublic, 0, 32)),
            new BigInteger(1, Arrays.copyOfRange(clientPublic, 32, 64)));
        ECPublicKeySpec peer = new ECPublicKeySpec(point, ((ECPublicKey) keys.getPublic()).getParams());
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(keys.getPrivate());
        agreement.doPhase(KeyFactory.getInstance("EC").generatePublic(peer), true);
        byte[] secret = agreement.generateSecret();
        try {
            byte[] display = derive("RelayCam2 display", secret, serverPublic, clientPublic);
            long value = ByteBuffer.wrap(display).getInt() & 0xffffffffL;
            return new Result(String.format(Locale.ROOT, "%06d", value % 1_000_000),
                new SecretKeySpec(derive("RelayCam2 video", secret, serverPublic, clientPublic), "AES"),
                derive("RelayCam3 trust", secret, serverPublic, clientPublic));
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    private static byte[] derive(String label, byte[] secret, byte[] serverPublic, byte[] clientPublic)
            throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        hash.update(label.getBytes(StandardCharsets.US_ASCII));
        hash.update(secret);
        hash.update(serverPublic);
        return hash.digest(clientPublic);
    }

    static final class Result {
        final String code;
        final SecretKeySpec videoKey;

        final byte[] trust;

        Result(String code, SecretKeySpec videoKey, byte[] trust) {
            this.code = code;
            this.videoKey = videoKey;
            this.trust = trust;
        }
    }
}
