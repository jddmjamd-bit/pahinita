-- ============================================================================
-- V7 — Evidencias de pago (roadmap 7: "Chat evidencias de pago")
--
-- Cuando el admin paga un retiro por fuera (Nequi, Llaves...) sube la imagen del comprobante y queda publicada en el
-- canal «🧾 Pagos» del chat. Cada retiro tiene como máximo UNA evidencia (UNIQUE en transaction_id).
--
-- La imagen se guarda aquí como data URI base64 (igual que las imágenes del canal de anuncios); NO usa media_files
-- (esa tabla es solo para videos). El historial del canal «pagos» se lee de esta tabla, no de `messages`.
--
-- NUNCA editar este archivo una vez aplicado (Flyway guarda su checksum). Cambios = un V8__ nuevo.
-- ============================================================================

--    transaction_id: el retiro (transactions.tipo = 'retiro') al que corresponde el comprobante
--    user_id:        a quien se le pagó (copiado de la transacción, nunca del cliente)
--    admin_id:       admin que subió la imagen (queda en NULL si ese usuario se borra)
--    monto/metodo:   copiados de la transacción al publicar, así el mensaje no depende de lo que escriba el admin
--    imagen:         data URI base64 (png/jpeg/gif/webp) ya validada por el servidor
CREATE TABLE IF NOT EXISTS payment_evidences (
    id SERIAL PRIMARY KEY,
    transaction_id INTEGER NOT NULL UNIQUE REFERENCES transactions(id) ON DELETE CASCADE,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    admin_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
    monto NUMERIC NOT NULL,
    metodo TEXT,
    nota TEXT,
    imagen TEXT NOT NULL,
    fecha TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Historial del canal (más recientes primero)
CREATE INDEX IF NOT EXISTS idx_payment_evidences_fecha ON payment_evidences (id DESC);
