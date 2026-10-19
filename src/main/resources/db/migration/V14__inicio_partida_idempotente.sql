-- V14: vincula cada sala al registro financiero de su partida y evita crear/cobrar el mismo inicio dos veces.

ALTER TABLE matches ADD COLUMN IF NOT EXISTS sala_id TEXT;
ALTER TABLE active_matches ADD COLUMN IF NOT EXISTS inicio_revision BOOLEAN NOT NULL DEFAULT FALSE;

CREATE UNIQUE INDEX IF NOT EXISTS uq_matches_sala_id
    ON matches (sala_id)
    WHERE sala_id IS NOT NULL;

-- Conserva el vínculo de las partidas que ya tenían match_db_id persistido en la sala.
UPDATE matches m
SET sala_id = a.sala_id
FROM active_matches a
WHERE a.iniciado = TRUE
  AND a.match_db_id = m.id
  AND m.sala_id IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM matches existente
      WHERE existente.sala_id = a.sala_id
  );
