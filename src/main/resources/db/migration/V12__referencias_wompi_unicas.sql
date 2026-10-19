-- ============================================================================
-- V12 — Evitar referencias duplicadas en pagos Wompi
-- ============================================================================

-- Evita que otra instancia de la aplicación inserte una referencia entre la
-- revisión de duplicados y la creación del índice.
LOCK TABLE transactions IN SHARE ROW EXCLUSIVE MODE;

-- No se corrigen automáticamente duplicados históricos: cambiar una referencia
-- podría asociar un pago ya realizado con el usuario equivocado. Se detiene la
-- migración con ejemplos para que esos pagos se reconcilien antes del despliegue.
DO $$
DECLARE
    referencias_duplicadas TEXT;
BEGIN
    SELECT string_agg(format('%s (%s filas)', referencia, cantidad), ', ')
    INTO referencias_duplicadas
    FROM (
        SELECT referencia, COUNT(*) AS cantidad
        FROM transactions
        WHERE tipo = 'deposito'
          AND metodo = 'wompi'
          AND referencia IS NOT NULL
        GROUP BY referencia
        HAVING COUNT(*) > 1
        ORDER BY COUNT(*) DESC, referencia
        LIMIT 10
    ) duplicados;

    IF referencias_duplicadas IS NOT NULL THEN
        RAISE EXCEPTION 'V12 no puede crear la unicidad Wompi: hay referencias duplicadas: %. Revisar las transacciones y conciliarlas con Wompi antes de volver a desplegar.',
            referencias_duplicadas;
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS idx_transactions_wompi_reference_unique
    ON transactions (referencia)
    WHERE tipo = 'deposito'
      AND metodo = 'wompi'
      AND referencia IS NOT NULL;
