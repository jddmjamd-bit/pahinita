-- ============================================================================
-- V9 — Persistir estado de partidas activas, colas y búsquedas en BD (A2)
-- ============================================================================
-- Permite recuperar las búsquedas en curso (match_queue) y las salas/partidas
-- activas (active_matches) tras un reinicio o redespliegue del servidor.
-- Incluyen columna id SERIAL PRIMARY KEY para compatibilidad con /admin-db (ORDER BY id).

CREATE TABLE IF NOT EXISTS match_queue (
    id          SERIAL PRIMARY KEY,
    user_id     INTEGER NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    username    TEXT    NOT NULL,
    creado_en   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expira_en   TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_match_queue_creado ON match_queue(creado_en);

CREATE TABLE IF NOT EXISTS active_matches (
    id                  SERIAL PRIMARY KEY,
    sala_id             TEXT    NOT NULL UNIQUE,
    player1_id          INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    player1_username    TEXT    NOT NULL,
    player2_id          INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    player2_username    TEXT    NOT NULL,
    iniciado            BOOLEAN NOT NULL DEFAULT FALSE,
    monto               NUMERIC NOT NULL DEFAULT 0,
    match_db_id         INTEGER NOT NULL DEFAULT 0,
    player_tag1         TEXT    NOT NULL DEFAULT '',
    player_tag2         TEXT    NOT NULL DEFAULT '',
    match_start_time    TEXT    NOT NULL DEFAULT '',
    last_modo           TEXT    NOT NULL DEFAULT '',
    last_dinero         TEXT    NOT NULL DEFAULT '',
    votos_json          TEXT    NOT NULL DEFAULT '{}',
    confirmaciones_json TEXT    NOT NULL DEFAULT '{}',
    poll_count          INTEGER NOT NULL DEFAULT 0,
    creado_en           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actualizado_en      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_active_matches_players ON active_matches(player1_id, player2_id);
