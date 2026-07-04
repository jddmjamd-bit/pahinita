package com.torneosflash.dao;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.*;

/**
 * CONCEPTO POO: Encapsulamiento - Singleton pattern
 * Gestiona la conexión a PostgreSQL (misma BD de Render).
 * Usa HikariCP como pool de conexiones.
 */
public class ConexionDB {

    // --- Singleton (ENCAPSULAMIENTO) ---
    private static ConexionDB instancia;
    private HikariDataSource dataSource;

    // --- Constructor privado (ENCAPSULAMIENTO) ---
    private ConexionDB(String databaseUrl) {
        try {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(convertirUrl(databaseUrl));
            config.setMaximumPoolSize(20);
            config.setMinimumIdle(2);
            config.setIdleTimeout(30000);
            config.setConnectionTimeout(5000);
            config.addDataSourceProperty("ssl", "true");
            config.addDataSourceProperty("sslfactory", "org.postgresql.ssl.NonValidatingFactory");
            config.addDataSourceProperty("sslmode", "require");
            this.dataSource = new HikariDataSource(config);
            System.out.println("✅ ¡Conexión exitosa a PostgreSQL!");
        } catch (Exception e) {
            System.err.println("❌ Error conectando a PostgreSQL: " + e.getMessage());
        }
    }

    /**
     * Convierte URL de formato postgres:// a jdbc:postgresql://
     */
    private String convertirUrl(String url) {
        if (url.startsWith("postgres://") || url.startsWith("postgresql://")) {
            // postgres://user:pass@host/db → jdbc:postgresql://host/db?user=...&password=...
            String sinProtocolo = url.replaceFirst("postgres(ql)?://", "");
            String[] partes = sinProtocolo.split("@");
            String[] credenciales = partes[0].split(":");
            String user = credenciales[0];
            String pass = credenciales.length > 1 ? credenciales[1] : "";
            String hostDb = partes[1];
            return "jdbc:postgresql://" + hostDb + "?user=" + user + "&password=" + pass + "&sslmode=require";
        }
        if (url.startsWith("jdbc:")) return url;
        return "jdbc:postgresql://" + url;
    }

    // --- Singleton getter ---
    public static synchronized ConexionDB getInstancia(String databaseUrl) {
        if (instancia == null) {
            instancia = new ConexionDB(databaseUrl);
        }
        return instancia;
    }

    public static ConexionDB getInstancia() {
        return instancia;
    }

    // --- Obtener conexión del pool ---
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /**
     * Inicializa todas las tablas (equivalente a initDB() en db.js)
     */
    public void inicializarTablas() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            System.out.println("🔄 Verificando tablas en PostgreSQL...");

            // 1. Usuarios
            stmt.execute("CREATE TABLE IF NOT EXISTS users (" +
                    "id SERIAL PRIMARY KEY, username TEXT UNIQUE, email TEXT UNIQUE, password TEXT, " +
                    "player_tag TEXT, telefono TEXT, saldo NUMERIC DEFAULT 0, tipo_suscripcion TEXT DEFAULT 'free', " +
                    "estado TEXT DEFAULT 'normal', sala_actual TEXT DEFAULT NULL, paso_juego INTEGER DEFAULT 0, " +
                    "ganancia_generada NUMERIC DEFAULT 0, faltas INTEGER DEFAULT 0, " +
                    "total_victorias INTEGER DEFAULT 0, victorias_normales INTEGER DEFAULT 0, victorias_disputa INTEGER DEFAULT 0, " +
                    "total_derrotas INTEGER DEFAULT 0, derrotas_normales INTEGER DEFAULT 0, derrotas_disputa INTEGER DEFAULT 0, " +
                    "total_partidas INTEGER DEFAULT 0, salidas_chat INTEGER DEFAULT 0, salidas_desconexion INTEGER DEFAULT 0, " +
                    "salidas_x INTEGER DEFAULT 0, salidas_canal INTEGER DEFAULT 0)");
            ejecutarSilencioso(stmt, "ALTER TABLE users ADD COLUMN IF NOT EXISTS player_tag TEXT");
            ejecutarSilencioso(stmt, "ALTER TABLE users ADD COLUMN IF NOT EXISTS telefono TEXT");
            ejecutarSilencioso(stmt, "ALTER TABLE users ADD COLUMN IF NOT EXISTS total_apostado NUMERIC DEFAULT 0");
            ejecutarSilencioso(stmt, "ALTER TABLE users ADD COLUMN IF NOT EXISTS total_ganado NUMERIC DEFAULT 0");
            ejecutarSilencioso(stmt, "ALTER TABLE users ADD COLUMN IF NOT EXISTS victorias_dia INTEGER DEFAULT 0");
            ejecutarSilencioso(stmt, "ALTER TABLE users ADD COLUMN IF NOT EXISTS victorias_semana INTEGER DEFAULT 0");
            ejecutarSilencioso(stmt, "ALTER TABLE users ADD COLUMN IF NOT EXISTS victorias_mes INTEGER DEFAULT 0");
            ejecutarSilencioso(stmt, "ALTER TABLE users ADD COLUMN IF NOT EXISTS victorias_ano INTEGER DEFAULT 0");
            System.out.println("   ✓ Tabla users");

            // 2. Mensajes
            stmt.execute("CREATE TABLE IF NOT EXISTS messages (" +
                    "id SERIAL PRIMARY KEY, canal TEXT DEFAULT 'general', usuario TEXT, texto TEXT, " +
                    "tipo TEXT DEFAULT 'texto', fecha TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            System.out.println("   ✓ Tabla messages");

            // 3. Partidas
            stmt.execute("CREATE TABLE IF NOT EXISTS matches (" +
                    "id SERIAL PRIMARY KEY, jugador1 TEXT, jugador2 TEXT, modo TEXT, " +
                    "apuesta NUMERIC, ganador TEXT DEFAULT NULL, estado TEXT DEFAULT 'en_curso', " +
                    "fecha TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            System.out.println("   ✓ Tabla matches");

            // 4. Transacciones
            stmt.execute("CREATE TABLE IF NOT EXISTS transactions (" +
                    "id SERIAL PRIMARY KEY, usuario_id INTEGER, usuario_nombre TEXT, tipo TEXT, " +
                    "metodo TEXT, monto NUMERIC, referencia TEXT, estado TEXT DEFAULT 'pendiente', " +
                    "fecha TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            System.out.println("   ✓ Tabla transactions");

            // 5. Bóveda Admin
            stmt.execute("CREATE TABLE IF NOT EXISTS admin_wallet (" +
                    "id SERIAL PRIMARY KEY, monto NUMERIC, razon TEXT, detalle TEXT, " +
                    "fecha TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            System.out.println("   ✓ Tabla admin_wallet");

            // 6. Tokens FCM
            stmt.execute("CREATE TABLE IF NOT EXISTS user_tokens (" +
                    "id SERIAL PRIMARY KEY, user_id INTEGER REFERENCES users(id), " +
                    "fcm_token TEXT, UNIQUE(user_id, fcm_token))");
            System.out.println("   ✓ Tabla user_tokens");

            // 7. Tickets
            stmt.execute("CREATE TABLE IF NOT EXISTS user_tickets (" +
                    "id SERIAL PRIMARY KEY, user_id INTEGER REFERENCES users(id), " +
                    "cantidad INTEGER DEFAULT 0, acumulado INTEGER DEFAULT 0, UNIQUE(user_id))");
            ejecutarSilencioso(stmt, "ALTER TABLE user_tickets ADD COLUMN IF NOT EXISTS acumulado INTEGER DEFAULT 0");
            System.out.println("   ✓ Tabla user_tickets");

            // 8. Sorteos
            stmt.execute("CREATE TABLE IF NOT EXISTS raffles (" +
                    "id SERIAL PRIMARY KEY, nombre TEXT NOT NULL, categoria TEXT NOT NULL, " +
                    "precio INTEGER NOT NULL, tickets_necesarios INTEGER NOT NULL, " +
                    "tickets_actuales INTEGER DEFAULT 0, fecha_limite TIMESTAMP, " +
                    "estado TEXT DEFAULT 'activo', ganador_id INTEGER, ganador_nombre TEXT, " +
                    "fecha_creacion TIMESTAMP DEFAULT CURRENT_TIMESTAMP, fecha_completado TIMESTAMP)");
            ejecutarSilencioso(stmt, "ALTER TABLE raffles ADD COLUMN IF NOT EXISTS fecha_completado TIMESTAMP");
            System.out.println("   ✓ Tabla raffles");

            // 9. Participaciones
            stmt.execute("CREATE TABLE IF NOT EXISTS raffle_entries (" +
                    "id SERIAL PRIMARY KEY, raffle_id INTEGER REFERENCES raffles(id) ON DELETE CASCADE, " +
                    "user_id INTEGER REFERENCES users(id), tickets_asignados INTEGER DEFAULT 0, " +
                    "fecha TIMESTAMP DEFAULT CURRENT_TIMESTAMP, UNIQUE(raffle_id, user_id))");
            System.out.println("   ✓ Tabla raffle_entries");

            // 10. Leaderboard history
            ejecutarSilencioso(stmt, "CREATE TABLE IF NOT EXISTS leaderboard_history (" +
                    "id SERIAL PRIMARY KEY, user_id INTEGER, username TEXT, periodo TEXT, " +
                    "victorias INTEGER DEFAULT 0, ganancias NUMERIC DEFAULT 0, posicion INTEGER, " +
                    "premio NUMERIC DEFAULT 0, fecha_inicio TEXT, fecha_fin TEXT, " +
                    "fecha TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            System.out.println("   ✓ Tabla leaderboard_history");

            System.out.println("👍 Todas las tablas verificadas en PostgreSQL.");
        } catch (Exception e) {
            System.err.println("❌ Error inicializando tablas: " + e.getMessage());
        }
    }

    private void ejecutarSilencioso(Statement stmt, String sql) {
        try { stmt.execute(sql); } catch (Exception ignored) {}
    }

    public void cerrar() {
        if (dataSource != null) dataSource.close();
    }
}
