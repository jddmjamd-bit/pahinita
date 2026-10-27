-- Saldo disponible del pozo de sorteos. gen_sorteos conserva las estadísticas históricas;
-- este saldo representa el dinero que todavía puede reservarse para premios.
CREATE TABLE IF NOT EXISTS raffle_pool (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    monto NUMERIC(18, 2) NOT NULL DEFAULT 0 CHECK (monto >= 0),
    actualizado_en TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO raffle_pool (id, monto)
SELECT 1,
       GREATEST(
           COALESCE((SELECT SUM(gen_sorteos) FROM users), 0)
           - COALESCE((SELECT SUM(precio) FROM raffles WHERE estado IN ('activo', 'completado')), 0),
           0
       )
ON CONFLICT (id) DO NOTHING;

CREATE TABLE IF NOT EXISTS raffle_pool_movements (
    id BIGSERIAL PRIMARY KEY,
    tipo TEXT NOT NULL CHECK (tipo IN ('ajuste_inicial', 'acumulacion', 'reserva', 'devolucion_cancelacion', 'devolucion_admin')),
    variacion NUMERIC(18, 2) NOT NULL CHECK (variacion <> 0),
    referencia TEXT NOT NULL,
    fecha TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO raffle_pool_movements (tipo, variacion, referencia)
SELECT 'ajuste_inicial', monto, 'Saldo inicial calculado desde las comisiones acumuladas'
FROM raffle_pool
WHERE id = 1 AND monto > 0
  AND NOT EXISTS (SELECT 1 FROM raffle_pool_movements);
