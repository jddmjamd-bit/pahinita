-- ============================================================================
-- V1 — Esquema base (A5)
--
-- Reemplaza a ConexionDB.inicializarTablas(). Contiene EXACTAMENTE el esquema que ese
-- método creaba, para que una BD nueva quede igual que la de producción.
--
-- Es idempotente a propósito (CREATE TABLE IF NOT EXISTS / ADD COLUMN IF NOT EXISTS):
--  * BD vacía  -> crea todo.
--  * BD de Render que ya tenía las tablas -> Flyway la "baselinea" en la versión 0 y este
--    script corre sin cambiar nada (solo completa lo que faltara).
--
-- NUNCA editar este archivo una vez aplicado: Flyway guarda su checksum y el arranque
-- fallaría. Los cambios de esquema van en un archivo nuevo (V3__..., V4__...).
-- ============================================================================

-- 1. Usuarios
CREATE TABLE IF NOT EXISTS users (
    id SERIAL PRIMARY KEY,
    username TEXT UNIQUE,
    email TEXT UNIQUE,
    password TEXT,
    player_tag TEXT,
    telefono TEXT,
    saldo NUMERIC DEFAULT 0,
    tipo_suscripcion TEXT DEFAULT 'free',
    estado TEXT DEFAULT 'normal',
    sala_actual TEXT DEFAULT NULL,
    paso_juego INTEGER DEFAULT 0,
    ganancia_generada NUMERIC DEFAULT 0,
    faltas INTEGER DEFAULT 0,
    total_victorias INTEGER DEFAULT 0,
    victorias_normales INTEGER DEFAULT 0,
    victorias_disputa INTEGER DEFAULT 0,
    total_derrotas INTEGER DEFAULT 0,
    derrotas_normales INTEGER DEFAULT 0,
    derrotas_disputa INTEGER DEFAULT 0,
    total_partidas INTEGER DEFAULT 0,
    salidas_chat INTEGER DEFAULT 0,
    salidas_desconexion INTEGER DEFAULT 0,
    salidas_x INTEGER DEFAULT 0,
    salidas_canal INTEGER DEFAULT 0,
    total_monto_torneos NUMERIC DEFAULT 0,
    total_ganado NUMERIC DEFAULT 0,
    victorias_dia INTEGER DEFAULT 0,
    victorias_semana INTEGER DEFAULT 0,
    victorias_mes INTEGER DEFAULT 0,
    victorias_ano INTEGER DEFAULT 0,
    gen_sorteos NUMERIC DEFAULT 0,
    gen_misiones NUMERIC DEFAULT 0,
    gen_logros NUMERIC DEFAULT 0,
    gen_leaderboard NUMERIC DEFAULT 0,
    gen_devolucion NUMERIC DEFAULT 0,
    gen_referidos NUMERIC DEFAULT 0
);

-- 2. Mensajes (chat)
CREATE TABLE IF NOT EXISTS messages (
    id SERIAL PRIMARY KEY,
    canal TEXT DEFAULT 'general',
    usuario TEXT,
    texto TEXT,
    tipo TEXT DEFAULT 'texto',
    fecha TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 3. Partidas
CREATE TABLE IF NOT EXISTS matches (
    id SERIAL PRIMARY KEY,
    jugador1 TEXT,
    jugador2 TEXT,
    modo TEXT,
    monto NUMERIC,
    ganador TEXT DEFAULT NULL,
    estado TEXT DEFAULT 'en_curso',
    fecha TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 4. Transacciones (depósitos y retiros)
CREATE TABLE IF NOT EXISTS transactions (
    id SERIAL PRIMARY KEY,
    usuario_id INTEGER,
    usuario_nombre TEXT,
    tipo TEXT,
    metodo TEXT,
    monto NUMERIC,
    referencia TEXT,
    estado TEXT DEFAULT 'pendiente',
    fecha TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 5. Bóveda del admin (comisiones por categoría)
CREATE TABLE IF NOT EXISTS admin_wallet (
    id SERIAL PRIMARY KEY,
    monto NUMERIC,
    razon TEXT,
    detalle TEXT,
    categoria TEXT,
    fecha TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 6. Tokens FCM (push)
CREATE TABLE IF NOT EXISTS user_tokens (
    id SERIAL PRIMARY KEY,
    user_id INTEGER REFERENCES users(id),
    fcm_token TEXT,
    UNIQUE(user_id, fcm_token)
);

-- 7. Tickets de sorteo
CREATE TABLE IF NOT EXISTS user_tickets (
    id SERIAL PRIMARY KEY,
    user_id INTEGER REFERENCES users(id),
    cantidad INTEGER DEFAULT 0,
    acumulado INTEGER DEFAULT 0,
    UNIQUE(user_id)
);

-- 8. Sorteos
CREATE TABLE IF NOT EXISTS raffles (
    id SERIAL PRIMARY KEY,
    nombre TEXT NOT NULL,
    categoria TEXT NOT NULL,
    precio INTEGER NOT NULL,
    tickets_necesarios INTEGER NOT NULL,
    tickets_actuales INTEGER DEFAULT 0,
    fecha_limite TIMESTAMP,
    estado TEXT DEFAULT 'activo',
    ganador_id INTEGER REFERENCES users(id),
    ganador_nombre TEXT,
    fecha_creacion TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    fecha_completado TIMESTAMP
);

-- 8.1 Votos de la encuesta de categorías de sorteo
CREATE TABLE IF NOT EXISTS raffle_votes (
    user_id INTEGER PRIMARY KEY REFERENCES users(id),
    categoria TEXT NOT NULL
);

-- 9. Participaciones en sorteos
CREATE TABLE IF NOT EXISTS raffle_entries (
    id SERIAL PRIMARY KEY,
    raffle_id INTEGER REFERENCES raffles(id) ON DELETE CASCADE,
    user_id INTEGER REFERENCES users(id),
    tickets_asignados INTEGER DEFAULT 0,
    fecha TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(raffle_id, user_id)
);

-- 10. Historial del leaderboard
CREATE TABLE IF NOT EXISTS leaderboard_history (
    id SERIAL PRIMARY KEY,
    user_id INTEGER,
    username TEXT,
    periodo TEXT,
    victorias INTEGER DEFAULT 0,
    ganancias NUMERIC DEFAULT 0,
    posicion INTEGER,
    premio NUMERIC DEFAULT 0,
    fecha_inicio TEXT,
    fecha_fin TEXT,
    fecha TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 11. Archivos multimedia (videos subidos, guardados como BYTEA)
CREATE TABLE IF NOT EXISTS media_files (
    id SERIAL PRIMARY KEY,
    filename TEXT,
    content_type TEXT,
    data BYTEA,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 12. Pozos del leaderboard (acumulan la comisión de cada periodo). Siempre existe la fila id = 1.
CREATE TABLE IF NOT EXISTS leaderboard_pools (
    id SERIAL PRIMARY KEY,
    dia NUMERIC DEFAULT 0,
    semana NUMERIC DEFAULT 0,
    mes NUMERIC DEFAULT 0,
    ano NUMERIC DEFAULT 0
);

INSERT INTO leaderboard_pools (id, dia, semana, mes, ano)
SELECT 1, 0, 0, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM leaderboard_pools WHERE id = 1);

-- ============================================================================
-- Compatibilidad con BDs creadas por versiones anteriores del código
-- (solo hacen algo si la BD es vieja; en una BD nueva no tocan nada)
-- ============================================================================

-- F5 (renombrar "apuestas"): antes había que correr los ALTER a mano. Si la BD aún tiene los
-- nombres viejos se renombran; si ya tiene los nuevos (o ambos) no se toca nada.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = current_schema() AND table_name = 'matches' AND column_name = 'apuesta')
       AND NOT EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = current_schema() AND table_name = 'matches' AND column_name = 'monto') THEN
        ALTER TABLE matches RENAME COLUMN apuesta TO monto;
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = current_schema() AND table_name = 'users' AND column_name = 'total_apostado')
       AND NOT EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = current_schema() AND table_name = 'users' AND column_name = 'total_monto_torneos') THEN
        ALTER TABLE users RENAME COLUMN total_apostado TO total_monto_torneos;
    END IF;
END $$;

-- Columnas que se fueron añadiendo con el tiempo (los mismos ALTER que corría inicializarTablas()).
ALTER TABLE users ADD COLUMN IF NOT EXISTS player_tag TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS telefono TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS total_monto_torneos NUMERIC DEFAULT 0;
ALTER TABLE users ADD COLUMN IF NOT EXISTS total_ganado NUMERIC DEFAULT 0;
ALTER TABLE users ADD COLUMN IF NOT EXISTS victorias_dia INTEGER DEFAULT 0;
ALTER TABLE users ADD COLUMN IF NOT EXISTS victorias_semana INTEGER DEFAULT 0;
ALTER TABLE users ADD COLUMN IF NOT EXISTS victorias_mes INTEGER DEFAULT 0;
ALTER TABLE users ADD COLUMN IF NOT EXISTS victorias_ano INTEGER DEFAULT 0;
ALTER TABLE users ADD COLUMN IF NOT EXISTS gen_sorteos NUMERIC DEFAULT 0;
ALTER TABLE users ADD COLUMN IF NOT EXISTS gen_misiones NUMERIC DEFAULT 0;
ALTER TABLE users ADD COLUMN IF NOT EXISTS gen_logros NUMERIC DEFAULT 0;
ALTER TABLE users ADD COLUMN IF NOT EXISTS gen_leaderboard NUMERIC DEFAULT 0;
ALTER TABLE users ADD COLUMN IF NOT EXISTS gen_devolucion NUMERIC DEFAULT 0;
ALTER TABLE users ADD COLUMN IF NOT EXISTS gen_referidos NUMERIC DEFAULT 0;

ALTER TABLE matches ADD COLUMN IF NOT EXISTS monto NUMERIC;
ALTER TABLE admin_wallet ADD COLUMN IF NOT EXISTS categoria TEXT;
ALTER TABLE user_tickets ADD COLUMN IF NOT EXISTS acumulado INTEGER DEFAULT 0;
ALTER TABLE raffles ADD COLUMN IF NOT EXISTS fecha_completado TIMESTAMP;
