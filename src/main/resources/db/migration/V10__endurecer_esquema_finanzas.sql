-- ============================================================================
-- V10 — Endurecer esquema financiero: inmutabilidad de wallet_ledger,
--       constraints de saldo/tickets no negativos y control de cortes de leaderboard
-- ============================================================================

-- 1. Saneamiento defensivo antes de agregar constraints CHECK
UPDATE users SET saldo = 0 WHERE saldo IS NULL OR saldo < 0;
ALTER TABLE users ALTER COLUMN saldo SET DEFAULT 0;
ALTER TABLE users ALTER COLUMN saldo SET NOT NULL;

UPDATE user_tickets SET cantidad = 0 WHERE cantidad IS NULL OR cantidad < 0;
ALTER TABLE user_tickets ALTER COLUMN cantidad SET DEFAULT 0;
ALTER TABLE user_tickets ALTER COLUMN cantidad SET NOT NULL;

-- 2. Constraints CHECK para impedir saldos o tickets negativos a nivel de motor SQL
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'users_saldo_no_negativo'
    ) THEN
        ALTER TABLE users ADD CONSTRAINT users_saldo_no_negativo CHECK (saldo >= 0);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'user_tickets_cantidad_no_negativa'
    ) THEN
        ALTER TABLE user_tickets ADD CONSTRAINT user_tickets_cantidad_no_negativa CHECK (cantidad >= 0);
    END IF;
END $$;

-- 3. Inmutabilidad real en BD para wallet_ledger (D3): prohibir UPDATE y DELETE
CREATE OR REPLACE FUNCTION fn_wallet_ledger_inmutable()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'wallet_ledger es inmutable: no se permite % sobre registros de auditoria financiera', TG_OP;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_wallet_ledger_inmutable ON wallet_ledger;
CREATE TRIGGER trg_wallet_ledger_inmutable
BEFORE UPDATE OR DELETE ON wallet_ledger
FOR EACH ROW EXECUTE FUNCTION fn_wallet_ledger_inmutable();

-- 4. Control persistente de cierres de leaderboard (evita perder o duplicar premios si el servidor reinicia)
ALTER TABLE leaderboard_pools ADD COLUMN IF NOT EXISTS ultimo_corte_dia VARCHAR(20);
ALTER TABLE leaderboard_pools ADD COLUMN IF NOT EXISTS ultimo_corte_semana VARCHAR(20);
ALTER TABLE leaderboard_pools ADD COLUMN IF NOT EXISTS ultimo_corte_mes VARCHAR(20);
ALTER TABLE leaderboard_pools ADD COLUMN IF NOT EXISTS ultimo_corte_ano VARCHAR(20);
