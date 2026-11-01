package com.mlcentral.ventas;

/** Limita solicitudes automáticas mientras Windows prepara el tablero. */
final class SnapshotRequestPolicy {
    private SnapshotRequestPolicy() {}

    static boolean shouldRequest(boolean force, long now, long last, boolean stale) {
        if (force || last <= 0L || now < last) return true;
        // Dar tiempo al GET del tablero (~30 s), sin retrasar comandos ni el
        // botón manual. La condición anterior anulaba el límite si era viejo.
        long minimum = stale ? 30_000L : 300_000L;
        return now - last >= minimum;
    }
}
