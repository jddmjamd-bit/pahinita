-- Avisos persistentes para el centro de notificaciones y snapshots de cada corte de ranking.

CREATE TABLE user_notifications (
    id BIGSERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    categoria VARCHAR(24) NOT NULL CHECK (categoria IN
        ('chat', 'soporte', 'asistente', 'ranking', 'sorteos', 'partida', 'finanzas')),
    titulo VARCHAR(140) NOT NULL,
    mensaje VARCHAR(500) NOT NULL,
    ruta VARCHAR(180) NOT NULL,
    evento_clave VARCHAR(180),
    datos JSONB NOT NULL DEFAULT '{}'::jsonb,
    leida_en TIMESTAMPTZ,
    descartada_en TIMESTAMPTZ,
    creada_en TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_user_notifications_visible
    ON user_notifications (user_id, creada_en DESC)
    WHERE descartada_en IS NULL;

CREATE INDEX idx_user_notifications_no_leidas
    ON user_notifications (user_id, categoria, creada_en DESC)
    WHERE leida_en IS NULL AND descartada_en IS NULL;

CREATE INDEX idx_user_notifications_evento
    ON user_notifications (evento_clave)
    WHERE evento_clave IS NOT NULL AND descartada_en IS NULL;

CREATE UNIQUE INDEX uq_user_notifications_evento_usuario
    ON user_notifications (user_id, evento_clave)
    WHERE evento_clave IS NOT NULL;

CREATE TABLE leaderboard_cut_snapshots (
    corte_id BIGINT NOT NULL REFERENCES leaderboard_cuts(id) ON DELETE RESTRICT,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    username TEXT NOT NULL,
    posicion INTEGER NOT NULL CHECK (posicion > 0),
    victorias INTEGER NOT NULL CHECK (victorias >= 0),
    ganancias NUMERIC NOT NULL DEFAULT 0 CHECK (ganancias >= 0),
    premio NUMERIC NOT NULL DEFAULT 0 CHECK (premio >= 0),
    PRIMARY KEY (corte_id, user_id),
    UNIQUE (corte_id, posicion)
);

CREATE INDEX idx_leaderboard_cut_snapshots_ganadores
    ON leaderboard_cut_snapshots (corte_id, posicion)
    WHERE posicion <= 10;

CREATE TABLE leaderboard_live_positions (
    periodo VARCHAR(20) NOT NULL CHECK (periodo IN
        ('dia', 'semana', 'mes', 'ano', 'global', 'monto_torneos', 'ganado')),
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    posicion INTEGER NOT NULL CHECK (posicion > 0),
    posicion_previa INTEGER CHECK (posicion_previa > 0),
    actualizado_en TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (periodo, user_id)
);

CREATE INDEX idx_leaderboard_live_positions_user
    ON leaderboard_live_positions (user_id, periodo);

-- Completa el historial global de cortes ya pagados antes de instalar la migración.
INSERT INTO leaderboard_cut_snapshots
    (corte_id, user_id, username, posicion, victorias, ganancias, premio)
SELECT p.corte_id, p.user_id, p.username, p.posicion,
       COALESCE(p.victorias, 0), COALESCE(p.ganancias, 0), p.monto
FROM leaderboard_cut_payments p
WHERE p.tipo_pago = 'jugador' AND p.posicion IS NOT NULL
ON CONFLICT (corte_id, user_id) DO NOTHING;
