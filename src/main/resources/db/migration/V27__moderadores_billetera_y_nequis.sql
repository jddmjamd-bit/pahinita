-- ============================================================================
-- V27 — Cuentas moderador, billetera independiente y números Nequi propios
-- ============================================================================

-- 1. Ampliar destino_tipo en referral_payouts para registrar pagos a moderadores
DO $$
DECLARE
    c_name TEXT;
BEGIN
    FOR c_name IN
        SELECT con.conname
        FROM pg_constraint con
        JOIN pg_class rel ON rel.oid = con.conrelid
        JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
        WHERE nsp.nspname = current_schema()
          AND rel.relname = 'referral_payouts'
          AND con.contype = 'c'
          AND pg_get_constraintdef(con.oid) ILIKE '%destino_tipo%'
    LOOP
        EXECUTE format('ALTER TABLE referral_payouts DROP CONSTRAINT %I', c_name);
    END LOOP;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint con
        JOIN pg_class rel ON rel.oid = con.conrelid
        JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
        WHERE nsp.nspname = current_schema()
          AND rel.relname = 'referral_payouts'
          AND con.conname = 'chk_referral_payouts_destino_tipo'
    ) THEN
        ALTER TABLE referral_payouts
            ADD CONSTRAINT chk_referral_payouts_destino_tipo
            CHECK (destino_tipo IN ('referidor', 'admin', 'moderador'));
    END IF;
END $$;

-- 2. Billetera independiente de cada moderador (comisión de partidas y referidos propios)
CREATE TABLE IF NOT EXISTS moderator_wallet (
    id BIGSERIAL PRIMARY KEY,
    moderator_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    match_id INTEGER REFERENCES matches(id) ON DELETE SET NULL,
    jugador_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
    categoria VARCHAR(24) NOT NULL CHECK (categoria IN ('ganancia', 'referidos')),
    monto NUMERIC(18,2) NOT NULL CHECK (monto >= 0),
    razon VARCHAR(40) NOT NULL DEFAULT 'comision_match',
    detalle VARCHAR(200) NOT NULL DEFAULT '',
    fecha TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_moderator_wallet_match_jugador_cat UNIQUE (match_id, jugador_id, categoria)
);

CREATE INDEX IF NOT EXISTS idx_moderator_wallet_mod_fecha
    ON moderator_wallet (moderator_id, fecha DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_moderator_wallet_jugador
    ON moderator_wallet (jugador_id);

-- 3. Números Nequi configurados por cada moderador para recargas de sus usuarios
CREATE TABLE IF NOT EXISTS moderator_nequis (
    id SERIAL PRIMARY KEY,
    moderator_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    numero VARCHAR(20) NOT NULL,
    titular VARCHAR(80) NOT NULL DEFAULT '',
    creado_en TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_moderator_nequis_mod_numero UNIQUE (moderator_id, numero)
);

CREATE INDEX IF NOT EXISTS idx_moderator_nequis_mod
    ON moderator_nequis (moderator_id, id ASC);
