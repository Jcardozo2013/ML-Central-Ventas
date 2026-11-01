package com.mlcentral.ventas;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Consultas de sólo lectura, sin cambiar rutas, autenticación ni formato. */
final class FirebaseReadQuery {
    private FirebaseReadQuery() {}

    static String suffix(int limit, String cursor) throws java.io.UnsupportedEncodingException {
        if (limit <= 0) return "";
        int bounded = Math.max(1, Math.min(limit, 250));
        String key = cursor == null ? "" : cursor.trim();
        // Los cursores persistidos provienen de push keys Firebase. Ante un
        // valor inválido, conservar la consulta heredada en vez de inyectarlo.
        if (!key.matches("[-0-9A-Z_a-z]{20}")) {
            return "&orderBy=%22%24key%22&limitToLast=" + bounded;
        }
        return "&orderBy=%22%24key%22&startAt="
                + URLEncoder.encode("\"" + key + "\"", StandardCharsets.UTF_8.name())
                + "&limitToFirst=" + (bounded + 1);
    }
}
