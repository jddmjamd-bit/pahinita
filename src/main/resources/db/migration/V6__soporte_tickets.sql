-- ============================================================================
-- V6 — Soporte: "Hablar con el admin" (roadmap 24.5)
--
-- Sistema de tickets: el usuario escribe una consulta (pregunta, sugerencia, bug, problema de su cuenta u otro),
-- al admin le llega un correo y el admin responde desde el panel. La respuesta queda guardada aquí (el usuario la
-- ve en su panel de soporte) y además se le envía a su correo.
--
-- NUNCA editar este archivo una vez aplicado (Flyway guarda su checksum). Cambios = un V7__ nuevo.
-- ============================================================================

--    categoria: 'pregunta' | 'sugerencia' | 'bug' | 'cuenta' | 'otro'
--    estado:    'abierto' (sin respuesta) | 'resuelto' (el admin ya respondió)
--    respondido_por: admin que respondió (queda en NULL si ese usuario se borra)
CREATE TABLE IF NOT EXISTS support_tickets (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    categoria TEXT NOT NULL,
    asunto TEXT NOT NULL,
    mensaje TEXT NOT NULL,
    estado TEXT NOT NULL DEFAULT 'abierto',
    respuesta TEXT,
    respondido_por INTEGER REFERENCES users(id) ON DELETE SET NULL,
    fecha_creacion TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    fecha_respuesta TIMESTAMP,
    CONSTRAINT support_tickets_categoria_chk CHECK (categoria IN ('pregunta', 'sugerencia', 'bug', 'cuenta', 'otro')),
    CONSTRAINT support_tickets_estado_chk CHECK (estado IN ('abierto', 'resuelto'))
);

-- "Mis consultas" de un usuario (más recientes primero) y conteo de sus tickets abiertos
CREATE INDEX IF NOT EXISTS idx_support_tickets_usuario ON support_tickets (user_id, id DESC);

-- Bandeja del admin: por estado, más recientes primero
CREATE INDEX IF NOT EXISTS idx_support_tickets_estado ON support_tickets (estado, id DESC);
