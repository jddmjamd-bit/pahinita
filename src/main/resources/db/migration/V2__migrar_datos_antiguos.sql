-- ============================================================================
-- V2 — Migración de datos antiguos (A5)
--
-- Antes estas dos correcciones se ejecutaban en CADA arranque desde ConexionDB
-- (UPDATE de gen_* y migrarAdminWalletAntiguo). Ahora corren una sola vez.
-- En una BD nueva no hacen nada (no hay filas viejas que convertir).
-- ============================================================================

-- 1. Desglose de ganancia por usuario.
-- La antigua `ganancia_generada` era el 50% de la comisión total del usuario; ahora se guarda
-- por categoría (gen_*) y `ganancia_generada` pasa a ser solo la categoría "ganancia" (25%).
-- Solo toca usuarios que aún no tienen desglose (gen_sorteos = 0) y sí tienen ganancia antigua.
UPDATE users SET
    gen_sorteos       = ganancia_generada * 0.20,
    gen_misiones      = ganancia_generada * 0.10,
    gen_logros        = ganancia_generada * 0.05,
    gen_leaderboard   = ganancia_generada * 0.15,
    gen_devolucion    = ganancia_generada * 0.15,
    gen_referidos     = ganancia_generada * 0.10,
    ganancia_generada = ganancia_generada * 0.25
WHERE gen_sorteos = 0 AND ganancia_generada > 0;

-- 2. admin_wallet: las filas antiguas (sin categoría) guardaban la comisión completa de una partida.
-- Se parten en las 7 categorías con los mismos porcentajes que ComisionService y se borra la fila original.
-- (ganancia = 25% = lo que sobra, así las 7 filas suman exactamente el monto original.)
INSERT INTO admin_wallet (monto, razon, detalle, categoria, fecha)
SELECT w.monto * c.factor, w.razon, w.detalle, c.categoria, w.fecha
FROM admin_wallet w
CROSS JOIN (VALUES
    ('sorteos',     0.20),
    ('misiones',    0.10),
    ('logros',      0.05),
    ('leaderboard', 0.15),
    ('devolucion',  0.15),
    ('referidos',   0.10),
    ('ganancia',    0.25)
) AS c(categoria, factor)
WHERE w.categoria IS NULL;

DELETE FROM admin_wallet WHERE categoria IS NULL;
