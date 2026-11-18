package com.rushi.walletledger.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 over a canonical form of a money-moving request.
 *
 * <p>Two requests with the same idempotency key must be the same operation to be a replay, so we
 * compare a hash of what the request actually asked for rather than trusting the key alone.
 * SHA-256 is used because a collision would let a different request be treated as a replay.
 */
public final class RequestHash {

    private static final HexFormat HEX = HexFormat.of();

    private RequestHash() {
    }

    public static String sha256(String canonicalRequest) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(digest.digest(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            // Every Java platform is required to provide SHA-256, so this cannot happen.
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
