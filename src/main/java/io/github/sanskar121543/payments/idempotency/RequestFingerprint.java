package io.github.sanskar121543.payments.idempotency;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class RequestFingerprint {

    private RequestFingerprint() {
    }

    /**
     * SHA-256 over the validated request, not the raw body, so whitespace and field order do not
     * change the hash.
     */
    public static String of(String method, String path, Object... fields) {
        MessageDigest sha256 = newDigest();
        update(sha256, method);
        update(sha256, path);
        for (Object field : fields) {
            update(sha256, String.valueOf(field));
        }
        return HexFormat.of().formatHex(sha256.digest());
    }

    // Length-prefixing every part keeps the encoding unambiguous: ("ab", "c") and ("a", "bc") differ.
    private static void update(MessageDigest digest, String part) {
        byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
