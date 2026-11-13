-- V25: Emparejamiento automático con confirmación previa del menor rango y visibilidad anticipada por oleadas
ALTER TABLE matchmaking_challenges
    ADD COLUMN IF NOT EXISTS es_automatico BOOLEAN NOT NULL DEFAULT FALSE;
