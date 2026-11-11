-- ============================================================================
-- V23 — Zona de pruebas aislada (copia temporal sobre BD de pruebas JUnit)
--       y preferencia de zona activa para cuentas administradoras
-- ============================================================================

ALTER TABLE users ADD COLUMN IF NOT EXISTS zona_admin VARCHAR(16) NOT NULL DEFAULT 'normal';

UPDATE users SET zona_admin = 'normal' WHERE zona_admin IS NULL OR zona_admin NOT IN ('normal', 'prueba');

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'users_zona_admin_valida'
    ) THEN
        ALTER TABLE users ADD CONSTRAINT users_zona_admin_valida
            CHECK (zona_admin IN ('normal', 'prueba'));
    END IF;
END $$;
