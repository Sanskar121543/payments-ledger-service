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
     *
     * TODO(practice): hash the method, the path and every field, and return lowercase hex. The encoding must be
     * unambiguous: ("ab", "c") and ("a", "bc") must give different hashes.
     */
    public static String of(String method, String path, Object... fields) {
        throw new UnsupportedOperationException("practice: write RequestFingerprint.of");
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
