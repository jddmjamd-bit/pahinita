-- D3: Tabla wallet_ledger — auditoría inmutable de todo movimiento de dinero.
-- Solo INSERT; nunca UPDATE ni DELETE. Cada fila registra un movimiento
-- atómico del saldo de un usuario (o de admin_wallet para comisiones).

CREATE TABLE IF NOT EXISTS wallet_ledger (
    id              BIGSERIAL       PRIMARY KEY,
    user_id         INTEGER         NOT NULL,
    tipo            VARCHAR(20)     NOT NULL,  -- deposito, retiro, torneo, premio, comision, liquidacion
    monto           NUMERIC(15,2)   NOT NULL,  -- siempre positivo; la semántica la da el tipo
    saldo_anterior  NUMERIC(15,2)   NOT NULL,
    saldo_posterior NUMERIC(15,2)   NOT NULL,
    referencia      VARCHAR(200),              -- contexto libre: "Recarga Wompi", "Match #42", etc.
    fecha           TIMESTAMP       NOT NULL DEFAULT NOW()
);

-- Índice para consultas por usuario (historial de movimientos)
CREATE INDEX IF NOT EXISTS idx_wallet_ledger_user_id ON wallet_ledger (user_id);

-- Índice para consultas por tipo (reportes)
CREATE INDEX IF NOT EXISTS idx_wallet_ledger_tipo ON wallet_ledger (tipo);

-- Índice para consultas por fecha (reportes por rango)
CREATE INDEX IF NOT EXISTS idx_wallet_ledger_fecha ON wallet_ledger (fecha);
