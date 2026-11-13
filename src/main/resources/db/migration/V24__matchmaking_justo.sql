-- Prioridad 15: Matchmaking justo con puntaje interno multivariado, oleadas progresivas y confirmación ante rivales superiores.

CREATE TABLE IF NOT EXISTS matchmaking_profiles (
    user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    clash_trophies INTEGER NULL,
    clash_best_trophies INTEGER NULL,
    clash_pol_league INTEGER NULL,
    clash_pol_trophies INTEGER NULL,
    clash_pol_best_league INTEGER NULL,
    clash_pol_best_trophies INTEGER NULL,
    clash_pol_rank INTEGER NULL,
    clash_pol_best_rank INTEGER NULL,
    clash_actualizado_en TIMESTAMP NULL,
    liga_clave VARCHAR(40) NULL,
    liga_nivel INTEGER NULL,
    liga_total_niveles INTEGER NULL,
    puntaje_interno DOUBLE PRECISION NOT NULL DEFAULT 500.0,
    tiene_win_rate BOOLEAN NOT NULL DEFAULT FALSE,
    tiene_clash BOOLEAN NOT NULL DEFAULT FALSE,
    tiene_liga BOOLEAN NOT NULL DEFAULT FALSE,
    actualizado_en TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE match_queue
    ADD COLUMN IF NOT EXISTS puntaje_buscador DOUBLE PRECISION NOT NULL DEFAULT 500.0,
    ADD COLUMN IF NOT EXISTS max_grupo_liberado INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS duracion_total_seg INTEGER NOT NULL DEFAULT 600,
    ADD COLUMN IF NOT EXISTS margenes_actualizados_en TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE TABLE IF NOT EXISTS matchmaking_search_notified (
    buscador_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    receptor_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    grupo_rango INTEGER NOT NULL DEFAULT 0,
    es_receptor_mejor BOOLEAN NOT NULL DEFAULT FALSE,
    etiqueta_nivel VARCHAR(160) NOT NULL DEFAULT '',
    notificado_en TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (buscador_id, receptor_id)
);

CREATE INDEX IF NOT EXISTS idx_mm_search_notified_receptor
    ON matchmaking_search_notified (receptor_id);

CREATE TABLE IF NOT EXISTS matchmaking_challenges (
    id SERIAL PRIMARY KEY,
    buscador_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    retador_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    buscador_username VARCHAR(80) NOT NULL,
    retador_username VARCHAR(80) NOT NULL,
    grupo_rango INTEGER NOT NULL DEFAULT 1,
    diferencia_puntaje DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    etiqueta_para_buscador VARCHAR(180) NOT NULL DEFAULT '',
    etiqueta_para_retador VARCHAR(180) NOT NULL DEFAULT '',
    estado VARCHAR(20) NOT NULL DEFAULT 'pendiente',
    creado_en TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expira_en TIMESTAMP NOT NULL,
    respondido_en TIMESTAMP NULL,
    CONSTRAINT uq_mm_challenge_pair UNIQUE (buscador_id, retador_id),
    CONSTRAINT chk_mm_challenge_estado CHECK (estado IN ('pendiente', 'aceptado', 'rechazado', 'cancelado', 'expirado'))
);

CREATE INDEX IF NOT EXISTS idx_mm_challenges_buscador_estado
    ON matchmaking_challenges (buscador_id, estado);
