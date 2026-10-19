-- Enforce the same case/format-insensitive identity rules as account registration.
-- Resolve any existing duplicate accounts manually before applying this migration;
-- no account is selected or modified automatically.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM users
        WHERE username IS NOT NULL AND BTRIM(username) <> ''
        GROUP BY LOWER(BTRIM(username)) HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'V17: hay nombres de usuario duplicados después de normalizar; resuélvelos antes de migrar';
    END IF;

    IF EXISTS (
        SELECT 1 FROM users
        WHERE email IS NOT NULL AND BTRIM(email) <> ''
        GROUP BY LOWER(BTRIM(email)) HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'V17: hay correos duplicados después de normalizar; resuélvelos antes de migrar';
    END IF;

    IF EXISTS (
        SELECT 1 FROM users
        WHERE player_tag IS NOT NULL AND BTRIM(player_tag) <> ''
          AND REGEXP_REPLACE(BTRIM(player_tag), '^#+', '') <> ''
        GROUP BY UPPER(REGEXP_REPLACE(BTRIM(player_tag), '^#+', '')) HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'V17: hay Player Tags duplicados después de normalizar; resuélvelos antes de migrar';
    END IF;

    IF EXISTS (
        SELECT 1 FROM users
        WHERE telefono IS NOT NULL
          AND REGEXP_REPLACE(BTRIM(telefono), '[^0-9]', '', 'g') <> ''
        GROUP BY CASE
            WHEN REGEXP_REPLACE(BTRIM(telefono), '[^0-9]', '', 'g') ~ '^003[0-9]{9}$'
                THEN '57' || SUBSTRING(REGEXP_REPLACE(BTRIM(telefono), '[^0-9]', '', 'g') FROM 3)
            WHEN REGEXP_REPLACE(BTRIM(telefono), '[^0-9]', '', 'g') ~ '^00[0-9]{7,15}$'
                THEN SUBSTRING(REGEXP_REPLACE(BTRIM(telefono), '[^0-9]', '', 'g') FROM 3)
            WHEN REGEXP_REPLACE(BTRIM(telefono), '[^0-9]', '', 'g') ~ '^3[0-9]{9}$'
                THEN '57' || REGEXP_REPLACE(BTRIM(telefono), '[^0-9]', '', 'g')
            ELSE REGEXP_REPLACE(BTRIM(telefono), '[^0-9]', '', 'g')
        END
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'V17: hay celulares duplicados después de normalizar; resuélvelos antes de migrar';
    END IF;
END $$;

CREATE UNIQUE INDEX users_username_normalizado_uidx
    ON users (LOWER(BTRIM(username)))
    WHERE username IS NOT NULL AND BTRIM(username) <> '';

CREATE UNIQUE INDEX users_email_normalizado_uidx
    ON users (LOWER(BTRIM(email)))
    WHERE email IS NOT NULL AND BTRIM(email) <> '';

CREATE UNIQUE INDEX users_player_tag_normalizado_uidx
    ON users (UPPER(REGEXP_REPLACE(BTRIM(player_tag), '^#+', '')))
    WHERE player_tag IS NOT NULL
      AND BTRIM(player_tag) <> ''
      AND REGEXP_REPLACE(BTRIM(player_tag), '^#+', '') <> '';

CREATE UNIQUE INDEX users_telefono_normalizado_uidx
    ON users ((CASE
        WHEN REGEXP_REPLACE(BTRIM(telefono), '[^0-9]', '', 'g') ~ '^003[0-9]{9}$'
            THEN '57' || SUBSTRING(REGEXP_REPLACE(BTRIM(telefono), '[^0-9]', '', 'g') FROM 3)
        WHEN REGEXP_REPLACE(BTRIM(telefono), '[^0-9]', '', 'g') ~ '^00[0-9]{7,15}$'
            THEN SUBSTRING(REGEXP_REPLACE(BTRIM(telefono), '[^0-9]', '', 'g') FROM 3)
        WHEN REGEXP_REPLACE(BTRIM(telefono), '[^0-9]', '', 'g') ~ '^3[0-9]{9}$'
            THEN '57' || REGEXP_REPLACE(BTRIM(telefono), '[^0-9]', '', 'g')
        ELSE REGEXP_REPLACE(BTRIM(telefono), '[^0-9]', '', 'g')
    END))
    WHERE telefono IS NOT NULL
      AND REGEXP_REPLACE(BTRIM(telefono), '[^0-9]', '', 'g') <> '';
