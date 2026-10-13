-- ============================================================================
-- V4 — Aceptación de Términos de Servicio y Política de Privacidad (roadmap 1.6)
--
-- Cada fila es la constancia de que un usuario aceptó una versión concreta de un documento legal.
-- Se guarda una fila por documento y por versión (no se pisa la anterior), así queda el historial
-- completo y, cuando un documento cambie, se puede pedir una nueva aceptación sin perder la prueba
-- de la anterior (Ley 1581 de 2012: el responsable debe conservar prueba de la autorización).
--
-- documento: 'terminos' | 'privacidad'
-- version:   la misma que declara servicio/DocumentosLegales (y frontend/public/legal/contenido-legal.json)
--
-- NUNCA editar este archivo una vez aplicado (Flyway guarda su checksum). Cambios = un V5__ nuevo.
-- ============================================================================

CREATE TABLE IF NOT EXISTS legal_acceptances (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    documento TEXT NOT NULL,
    version TEXT NOT NULL,
    aceptado_en TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT legal_acceptances_documento_chk CHECK (documento IN ('terminos', 'privacidad')),
    CONSTRAINT legal_acceptances_unica UNIQUE (user_id, documento, version)
);

CREATE INDEX IF NOT EXISTS idx_legal_acceptances_usuario ON legal_acceptances (user_id, documento);
