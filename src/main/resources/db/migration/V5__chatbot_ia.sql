-- ============================================================================
-- V5 — Chatbot IA (roadmap parte 20)
--
-- El chatbot NO se entrena con un documento: la IA le hace preguntas al admin sobre la página y las
-- respuestas del admin quedan como base de conocimiento. Cuando un usuario pregunta algo que la base
-- no cubre, la duda queda pendiente aquí, se avisa al admin por correo y su respuesta pasa a la base.
--
-- NUNCA editar este archivo una vez aplicado (Flyway guarda su checksum). Cambios = un V6__ nuevo.
-- ============================================================================

-- 1. Base de conocimiento y dudas pendientes
--    origen: 'ia'      -> la IA le preguntó al admin (modo entrenamiento)
--            'usuario' -> un usuario preguntó algo que la IA no supo responder
--            'admin'   -> el admin la agregó por su cuenta
--    estado: 'pendiente' (sin respuesta) | 'respondida' (ya es conocimiento del bot) | 'descartada'
--    veces: cuántas veces se hizo la misma duda mientras estaba pendiente
CREATE TABLE IF NOT EXISTS chatbot_conocimiento (
    id SERIAL PRIMARY KEY,
    pregunta TEXT NOT NULL,
    pregunta_normalizada TEXT NOT NULL,
    respuesta TEXT,
    origen TEXT NOT NULL,
    estado TEXT NOT NULL DEFAULT 'pendiente',
    veces INTEGER NOT NULL DEFAULT 1,
    fecha_creacion TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    fecha_respuesta TIMESTAMP,
    CONSTRAINT chatbot_conocimiento_origen_chk CHECK (origen IN ('ia', 'usuario', 'admin')),
    CONSTRAINT chatbot_conocimiento_estado_chk CHECK (estado IN ('pendiente', 'respondida', 'descartada'))
);
CREATE INDEX IF NOT EXISTS idx_chatbot_conocimiento_estado ON chatbot_conocimiento (estado, id);
CREATE INDEX IF NOT EXISTS idx_chatbot_conocimiento_norm ON chatbot_conocimiento (pregunta_normalizada);

-- 2. Qué usuarios hicieron cada duda pendiente (para avisarles a todos cuando el admin responda)
CREATE TABLE IF NOT EXISTS chatbot_duda_usuarios (
    duda_id INTEGER NOT NULL REFERENCES chatbot_conocimiento(id) ON DELETE CASCADE,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    PRIMARY KEY (duda_id, user_id)
);

-- 3. Historial de la conversación de cada usuario con el bot
CREATE TABLE IF NOT EXISTS chatbot_mensajes (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    rol TEXT NOT NULL,
    texto TEXT NOT NULL,
    fecha TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chatbot_mensajes_rol_chk CHECK (rol IN ('usuario', 'bot'))
);
CREATE INDEX IF NOT EXISTS idx_chatbot_mensajes_usuario ON chatbot_mensajes (user_id, id);

-- 4. Tono elegido por cada usuario (si no hay fila se usa el tono por defecto del admin)
CREATE TABLE IF NOT EXISTS chatbot_preferencias (
    user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    tono TEXT NOT NULL,
    CONSTRAINT chatbot_preferencias_tono_chk CHECK (tono IN ('formal', 'informal', 'adaptativo'))
);

-- 5. Ajustes globales del bot (por ahora solo el tono por defecto que fija el admin)
CREATE TABLE IF NOT EXISTS chatbot_config (
    clave TEXT PRIMARY KEY,
    valor TEXT NOT NULL
);

INSERT INTO chatbot_config (clave, valor) VALUES ('tono_defecto', 'adaptativo')
ON CONFLICT (clave) DO NOTHING;
