-- V21: persiste en active_matches la exención única de desconexión al abrir Discord
-- y el temporizador individual opcional de negociación (2:30 por jugador).

ALTER TABLE active_matches
    ADD COLUMN IF NOT EXISTS discord_exencion_p1 VARCHAR(16) NOT NULL DEFAULT 'disponible',
    ADD COLUMN IF NOT EXISTS discord_exencion_p2 VARCHAR(16) NOT NULL DEFAULT 'disponible',
    ADD COLUMN IF NOT EXISTS timer_activo BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS timer_activado_por INTEGER REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS timer_p1_vence_en TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS timer_p2_vence_en TIMESTAMPTZ;

ALTER TABLE active_matches
    DROP CONSTRAINT IF EXISTS chk_active_matches_discord_exencion_p1,
    ADD CONSTRAINT chk_active_matches_discord_exencion_p1
        CHECK (discord_exencion_p1 IN ('disponible', 'armada', 'activa', 'usada')),
    DROP CONSTRAINT IF EXISTS chk_active_matches_discord_exencion_p2,
    ADD CONSTRAINT chk_active_matches_discord_exencion_p2
        CHECK (discord_exencion_p2 IN ('disponible', 'armada', 'activa', 'usada'));

