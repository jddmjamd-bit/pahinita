-- Nuevas cuentas esperan la comprobación manual; cuentas existentes conservan acceso a retiros.
ALTER TABLE users
    ADD COLUMN player_tag_verificado BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN player_verification_token_ciphertext TEXT,
    ADD COLUMN player_tag_verificado_en TIMESTAMPTZ,
    ADD COLUMN player_tag_verificado_por INTEGER REFERENCES users(id) ON DELETE SET NULL;
