package com.saanscheck.ingestion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;

/**
 * The feed identifies stations by name only (VERIFY), so we derive a stable id: a URL-safe slug,
 * e.g. "Anand Vihar, Delhi - DPCC" -> "anand-vihar-delhi-dpcc". Long names are truncated with a
 * short hash suffix so ids stay unique and fit VARCHAR(64).
 */
public final class StationIds {

    private static final int MAX = 64;

    private StationIds() {
    }

    public static String slug(String name) {
        String ascii = Normalizer.normalize(name.trim(), Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        String slug = ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (slug.isEmpty()) {
            slug = "station";
        }
        if (slug.length() <= MAX) {
            return slug;
        }
        return slug.substring(0, MAX - 9) + "-" + shortHash(name);
    }

    private static String shortHash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
