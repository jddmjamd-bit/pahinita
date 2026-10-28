-- V20: permite crear y usar el canal de voz de Discord desde la sala privada (antes de iniciar la partida)
-- y conservar ese mismo canal cuando la partida comienza.

ALTER TABLE voice_channels
    ALTER COLUMN match_id DROP NOT NULL;

ALTER TABLE voice_channels
    ADD COLUMN IF NOT EXISTS sala_id TEXT;

-- Vincula canales históricos con el sala_id de su partida si existe y no genera duplicados.
UPDATE voice_channels vc
SET sala_id = m.sala_id
FROM matches m
WHERE vc.match_id = m.id
  AND vc.sala_id IS NULL
  AND m.sala_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM voice_channels existente
      WHERE existente.sala_id = m.sala_id
  );

CREATE UNIQUE INDEX IF NOT EXISTS uq_voice_channels_sala_id
    ON voice_channels (sala_id)
    WHERE sala_id IS NOT NULL;

ALTER TABLE voice_channels
    DROP CONSTRAINT IF EXISTS chk_voice_channels_origen,
    ADD CONSTRAINT chk_voice_channels_origen
    CHECK (match_id IS NOT NULL OR (sala_id IS NOT NULL AND sala_id <> ''));
