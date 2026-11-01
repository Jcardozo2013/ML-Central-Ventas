package com.mlcentral.ventas;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** GET únicamente. La huella se confirma en el consumidor después de aplicar. */
final class FirebaseSnapshotReader {
    static final class Response {
        final int code;
        final String body;
        final String etag;
        final boolean unchanged;
        Response(int code, String body, String etag, boolean unchanged) {
            this.code = code; this.body = body; this.etag = etag; this.unchanged = unchanged;
        }
    }

    static Response read(String url, String confirmedEtag) throws java.io.IOException {
        if (confirmedEtag != null && !confirmedEtag.isEmpty()) {
            Response probe = get(url + (url.contains("?") ? "&" : "?") + "print=silent", true);
            if (probe.code == 401 || probe.code == 403) return probe;
            if (probe.code == 204 && !probe.etag.isEmpty() && probe.etag.equals(confirmedEtag))
                return new Response(204, "", probe.etag, true);
            // Falta de soporte, huella distinta o respuesta incierta: leer datos.
        }
        Response full = get(url, true);
        // Un proxy puede rechazar el encabezado. Lectura heredada, sin caché.
        if (full.code == 400) return get(url, false);
        return full;
    }

    private static Response get(String url, boolean requestEtag) throws java.io.IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(7000); connection.setReadTimeout(7000);
            connection.setRequestProperty("Accept", "application/json");
            if (requestEtag) connection.setRequestProperty("X-Firebase-ETag", "true");
            int code = connection.getResponseCode();
            String etag = connection.getHeaderField("ETag");
            if (etag == null || !requestEtag) etag = "";
            if (code < 200 || code >= 300 || code == 204)
                return new Response(code, "", etag, false);
            try (InputStream in = connection.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                return new Response(code, new String(out.toByteArray(), StandardCharsets.UTF_8), etag, false);
            }
        } finally { connection.disconnect(); }
    }
}
