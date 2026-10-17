-- ============================================================================
-- V8 — Chat de voz con Discord (roadmap 84)
--
-- El chat de voz es OPCIONAL: solo lo usa quien vincule su cuenta de Discord. Quien no la vincule juega igual,
-- sin voz. Cuando empieza una partida, el servidor crea un canal de voz privado en el servidor de Discord de la
-- plataforma (solo lo ven y entran los dos jugadores) y lo borra cuando la partida termina.
--
--  discord_links:  qué cuenta de Discord es de cada usuario. Se llena con el flujo OAuth2 (el usuario autoriza en
--                  Discord, nunca escribe su id a mano). UNIQUE(discord_id): una cuenta de Discord no puede estar
--                  vinculada a dos usuarios (evita multicuentas). NO se guarda ningún token de Discord.
--  voice_channels: el canal de voz temporal de cada partida. Vive en la BD (no en memoria) para que, si el servidor
--                  se reinicia, la limpieza siga sabiendo qué canales hay que borrar. Un solo canal por partida.
--
-- NUNCA editar este archivo una vez aplicado (Flyway guarda su checksum). Cambios = un V9__ nuevo.
-- ============================================================================

--    discord_id:       id numérico de Discord (snowflake), como texto
--    discord_username: nombre de Discord, solo para mostrárselo al propio usuario
CREATE TABLE IF NOT EXISTS discord_links (
    user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    discord_id TEXT NOT NULL UNIQUE,
    discord_username TEXT,
    fecha_vinculo TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

--    match_id:           partida a la que pertenece (matches.id); UNIQUE → un canal por partida
--    user1_id/user2_id:  los dos jugadores (solo ellos pueden pedir acceso al canal)
--    discord_channel_id: id del canal en Discord
--    estado:             activo = el canal existe en Discord | cerrado = ya se borró
CREATE TABLE IF NOT EXISTS voice_channels (
    id SERIAL PRIMARY KEY,
    match_id INTEGER NOT NULL UNIQUE REFERENCES matches(id) ON DELETE CASCADE,
    user1_id INTEGER NOT NULL,
    user2_id INTEGER NOT NULL,
    discord_channel_id TEXT NOT NULL,
    estado TEXT NOT NULL DEFAULT 'activo' CHECK (estado IN ('activo', 'cerrado')),
    fecha_creacion TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    fecha_cierre TIMESTAMP
);

-- La limpieza periódica solo mira los canales activos
CREATE INDEX IF NOT EXISTS idx_voice_channels_estado ON voice_channels (estado);
