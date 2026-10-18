-- ============================================================================
-- V11 — Blindaje de correlación Wompi (external_id único) y bloqueo TRUNCATE en wallet_ledger
-- ============================================================================

-- 1. Columna external_id única en transactions para impedir que un mismo transaction.id
--    de Wompi pueda asociarse o acreditar más de una referencia.
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS external_id TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS idx_transactions_external_id_unique
    ON transactions(external_id)
    WHERE external_id IS NOT NULL;

-- 2. Bloquear también TRUNCATE sobre wallet_ledger (complementa el trigger BEFORE UPDATE OR DELETE de V10)
DROP TRIGGER IF EXISTS trg_wallet_ledger_no_truncate ON wallet_ledger;
CREATE TRIGGER trg_wallet_ledger_no_truncate
    BEFORE TRUNCATE ON wallet_ledger
    EXECUTE FUNCTION fn_wallet_ledger_inmutable();
