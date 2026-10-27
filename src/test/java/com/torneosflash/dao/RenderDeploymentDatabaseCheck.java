package com.torneosflash.dao;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Se ejecuta desde el contenedor justo antes de iniciar el servicio, nunca contra DATABASE_URL. */
class RenderDeploymentDatabaseCheck {
    @Test
    void verificaMigracionesYConexionEnLaBaseDeDatosAislada() throws Exception {
        String testUrl = System.getenv("TEST_DATABASE_URL");
        assertNotNull(testUrl, "Configura TEST_DATABASE_URL con una base exclusiva para pruebas.");
        assertFalse(testUrl.isBlank(), "TEST_DATABASE_URL no puede estar vacía.");

        String sslMode = System.getenv().getOrDefault("DB_SSLMODE", "require");
        ConexionDB.EnlaceBD testDb = ConexionDB.EnlaceBD.desde(testUrl, sslMode);
        String productionUrl = System.getenv("DATABASE_URL");
        if (productionUrl != null && !productionUrl.isBlank()) {
            ConexionDB.EnlaceBD productionDb = ConexionDB.EnlaceBD.desde(productionUrl, sslMode);
            assertFalse(mismaBase(testDb, productionDb),
                    "TEST_DATABASE_URL no puede apuntar a la misma base que DATABASE_URL.");
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

    private static boolean mismaBase(ConexionDB.EnlaceBD a, ConexionDB.EnlaceBD b) {
        return a.hostProceso.equalsIgnoreCase(b.hostProceso)
                && a.puertoProceso.equals(b.puertoProceso)
                && a.nombreBase.equalsIgnoreCase(b.nombreBase);
    }
}
