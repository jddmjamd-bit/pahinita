-- ============================================================================
-- V26 — Sistema de referidos (Prioridad 6.5 del roadmap)
-- ============================================================================

-- 1. Columnas de referidos en users
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS codigo_referido VARCHAR(24),
    ADD COLUMN IF NOT EXISTS referido_por_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS referido_porcentaje_compartido NUMERIC(5,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS referido_mensaje_corto VARCHAR(240) NOT NULL DEFAULT '';

-- Poblar codigo_referido único para usuarios existentes
UPDATE users
SET codigo_referido = 'TF' || UPPER(SUBSTRING(MD5(id::text || '-' || COALESCE(username, '')), 1, 6)) || id::text
WHERE codigo_referido IS NULL OR BTRIM(codigo_referido) = '';

ALTER TABLE users
    ALTER COLUMN codigo_referido SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_users_codigo_referido_upper
    ON users (UPPER(BTRIM(codigo_referido)));

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_users_referido_porcentaje_rango'
    ) THEN
        ALTER TABLE users
            ADD CONSTRAINT chk_users_referido_porcentaje_rango
            CHECK (referido_porcentaje_compartido >= 0 AND referido_porcentaje_compartido <= 100);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_users_no_autoreferido'
    ) THEN
        ALTER TABLE users
            ADD CONSTRAINT chk_users_no_autoreferido
            CHECK (referido_por_id IS NULL OR referido_por_id <> id);
    END IF;
END $$;

-- Trigger defensivo para asegurar codigo_referido en inserciones directas (ej. semillas/tests)
CREATE OR REPLACE FUNCTION fn_users_asignar_codigo_referido()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.codigo_referido IS NULL OR BTRIM(NEW.codigo_referido) = '' THEN
        NEW.codigo_referido := 'TF' || UPPER(SUBSTRING(MD5(
            COALESCE(NEW.username, '') || '-' || CLOCK_TIMESTAMP()::text || '-' || RANDOM()::text
        ), 1, 10));
    ELSE
        NEW.codigo_referido := UPPER(BTRIM(NEW.codigo_referido));
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_users_asignar_codigo_referido ON users;
CREATE TRIGGER trg_users_asignar_codigo_referido
BEFORE INSERT ON users
FOR EACH ROW EXECUTE FUNCTION fn_users_asignar_codigo_referido();

-- 2. Tabla de relaciones de referidos y acumulados
CREATE TABLE IF NOT EXISTS referrals (
    id SERIAL PRIMARY KEY,
    referrer_user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    referred_user_id INTEGER NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    codigo_usado VARCHAR(24) NOT NULL,
    porcentaje_compartido NUMERIC(5,2) NOT NULL DEFAULT 0
        CHECK (porcentaje_compartido >= 0 AND porcentaje_compartido <= 100),
    usa_porcentaje_personalizado BOOLEAN NOT NULL DEFAULT FALSE,
    partidas_jugadas INTEGER NOT NULL DEFAULT 0 CHECK (partidas_jugadas >= 0),
    total_comision_referido NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (total_comision_referido >= 0),
    total_ganado_referidor NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (total_ganado_referidor >= 0),
    total_devuelto_referido NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (total_devuelto_referido >= 0),
    creado_en TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_referrals_distintos CHECK (referrer_user_id <> referred_user_id)
);

CREATE INDEX IF NOT EXISTS idx_referrals_referrer
    ON referrals (referrer_user_id, creado_en DESC);

-- 3. Auditoría idempotente por partida y jugador de la comisión de referidos
CREATE TABLE IF NOT EXISTS referral_payouts (
    id BIGSERIAL PRIMARY KEY,
    match_id INTEGER NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
    jugador_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    destino_tipo VARCHAR(20) NOT NULL CHECK (destino_tipo IN ('referidor', 'admin')),
    referrer_user_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
    monto_base NUMERIC(18,2) NOT NULL CHECK (monto_base >= 0),
    porcentaje_compartido NUMERIC(5,2) NOT NULL DEFAULT 0
        CHECK (porcentaje_compartido >= 0 AND porcentaje_compartido <= 100),
    monto_referidor NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (monto_referidor >= 0),
    monto_devuelto_referido NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (monto_devuelto_referido >= 0),
    monto_admin NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (monto_admin >= 0),
    creado_en TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_referral_payouts_match_jugador UNIQUE (match_id, jugador_id)
);

CREATE INDEX IF NOT EXISTS idx_referral_payouts_referrer
    ON referral_payouts (referrer_user_id, creado_en DESC);

CREATE INDEX IF NOT EXISTS idx_referral_payouts_jugador
    ON referral_payouts (jugador_id, creado_en DESC);

