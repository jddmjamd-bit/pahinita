-- V13: persiste cada corte y sus pagos para poder reintentarlos sin perder ni duplicar premios.

CREATE TABLE leaderboard_cuts (
    id BIGSERIAL PRIMARY KEY,
    periodo VARCHAR(10) NOT NULL CHECK (periodo IN ('dia', 'semana', 'mes', 'ano')),
    clave_corte VARCHAR(20) NOT NULL,
    pozo NUMERIC NOT NULL DEFAULT 0 CHECK (pozo >= 0),
    estado VARCHAR(15) NOT NULL DEFAULT 'pendiente'
        CHECK (estado IN ('pendiente', 'completado')),
    creado_en TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completado_en TIMESTAMPTZ,
    UNIQUE (periodo, clave_corte)
);

CREATE TABLE leaderboard_cut_payments (
    id BIGSERIAL PRIMARY KEY,
    corte_id BIGINT NOT NULL REFERENCES leaderboard_cuts(id) ON DELETE RESTRICT,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    tipo_pago VARCHAR(10) NOT NULL CHECK (tipo_pago IN ('jugador', 'admin')),
    username TEXT NOT NULL,
    victorias INTEGER,
    ganancias NUMERIC,
    posicion INTEGER,
    monto NUMERIC NOT NULL CHECK (monto > 0),
    estado VARCHAR(12) NOT NULL DEFAULT 'pendiente'
        CHECK (estado IN ('pendiente', 'pagado')),
    intentos INTEGER NOT NULL DEFAULT 0,
    ultimo_error TEXT,
    creado_en TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    pagado_en TIMESTAMPTZ,
    UNIQUE (corte_id, user_id, tipo_pago)
);

CREATE INDEX idx_leaderboard_cut_payments_pendientes
    ON leaderboard_cut_payments (corte_id, id)
    WHERE estado = 'pendiente';

ALTER TABLE leaderboard_history
    ADD COLUMN corte_pago_id BIGINT REFERENCES leaderboard_cut_payments(id) ON DELETE RESTRICT;

CREATE UNIQUE INDEX uq_leaderboard_history_corte_pago
    ON leaderboard_history (corte_pago_id)
    WHERE corte_pago_id IS NOT NULL;
