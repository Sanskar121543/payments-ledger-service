package io.github.sanskar121543.payments.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ApiKeys {

    private final Map<String, byte[]> hashesByClient;

    public ApiKeys(@Value("${payments.security.api-keys}") String config) {
        this.hashesByClient = parse(config);
        if (hashesByClient.isEmpty()) {
            throw new IllegalStateException(
                    "payments.security.api-keys must contain at least one clientId:sha256hex entry");
        }
    }

    public Optional<String> clientFor(String presentedKey) {
        if (presentedKey == null || presentedKey.isBlank()) {
            return Optional.empty();
        }
        byte[] presentedHash = sha256(presentedKey);
        String match = null;
        // Fixed-length hashes + isEqual keep the comparison constant-time, and we never stop early.
        for (Map.Entry<String, byte[]> entry : hashesByClient.entrySet()) {
            if (MessageDigest.isEqual(presentedHash, entry.getValue())) {
                match = entry.getKey();
            }
        }
        return Optional.ofNullable(match);
    }

    static Map<String, byte[]> parse(String config) {
        Map<String, byte[]> result = new LinkedHashMap<>();
        if (config == null) {
            return result;
        }
        for (String entry : config.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon <= 0) {
                throw new IllegalStateException("API key entries must look like clientId:sha256hex");
            }
            byte[] hash = HexFormat.of().parseHex(trimmed.substring(colon + 1));
            if (hash.length != 32) {
                throw new IllegalStateException(
                        "API key hash for client " + trimmed.substring(0, colon) + " is not SHA-256");
            }
            result.put(trimmed.substring(0, colon), hash);
        }
        return result;
    }

    static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
