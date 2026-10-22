package com.torneosflash.dao;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Se ejecuta desde el contenedor justo antes de iniciar el servicio, nunca contra DATABASE_URL. */
class RenderDeploymentDatabaseCheck {
    private static final Pattern NOMBRE_DE_PRUEBAS = Pattern.compile("(^|[_-])(test|pruebas?|qa)([_-]|$)");

    @Test
    void verificaMigracionesYConexionEnLaBaseDeDatosAislada() throws Exception {
        String testUrl = System.getenv("TEST_DATABASE_URL");
        assertNotNull(testUrl, "Configura TEST_DATABASE_URL con una base exclusiva para pruebas.");
        assertFalse(testUrl.isBlank(), "TEST_DATABASE_URL no puede estar vacía.");

        String sslMode = System.getenv().getOrDefault("DB_SSLMODE", "require");
        ConexionDB.EnlaceBD testDb = ConexionDB.EnlaceBD.desde(testUrl, sslMode);
        String nombrePrueba = testDb.nombreBase.toLowerCase(Locale.ROOT);
        assertTrue(NOMBRE_DE_PRUEBAS.matcher(nombrePrueba).find(),
                "El nombre de TEST_DATABASE_URL debe incluir un segmento test, pruebas o qa.");

        String productionUrl = System.getenv("DATABASE_URL");
        if (productionUrl != null && !productionUrl.isBlank()) {
            ConexionDB.EnlaceBD productionDb = ConexionDB.EnlaceBD.desde(productionUrl, sslMode);
            assertFalse(testDb.nombreBase.equalsIgnoreCase(productionDb.nombreBase),
                    "TEST_DATABASE_URL no puede apuntar al mismo nombre de base de datos que DATABASE_URL.");
        }

        HikariConfig pool = new HikariConfig();
        pool.setPoolName("torneos-pruebas-deploy");
        pool.setJdbcUrl(testDb.jdbcUrl);
        if (testDb.usuario != null) pool.setUsername(testDb.usuario);
        if (testDb.password != null) pool.setPassword(testDb.password);
        pool.setMaximumPoolSize(2);
        pool.setMinimumIdle(0);
        pool.setConnectionTimeout(10_000);

        try (HikariDataSource dataSource = new HikariDataSource(pool)) {
            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(true)
                    .baselineVersion("0")
                    .baselineDescription("Esquema previo a Flyway (BD de pruebas)")
                    .validateOnMigrate(true)
                    .cleanDisabled(true)
                    .load();

            flyway.migrate();
            flyway.validate();

            try (Connection connection = dataSource.getConnection()) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE")) {
                    try (ResultSet rows = statement.executeQuery()) {
                        assertTrue(rows.next() && rows.getInt(1) >= 17,
                                "La base de pruebas debe tener aplicadas las migraciones actuales.");
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT to_regclass('public.users') IS NOT NULL")) {
                    try (ResultSet rows = statement.executeQuery()) {
                        assertTrue(rows.next() && rows.getBoolean(1),
                                "La migración de esquema debe crear public.users.");
                    }
                }
            }
        }
    }
}
