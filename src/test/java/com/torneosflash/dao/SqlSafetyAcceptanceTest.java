package com.torneosflash.dao;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlSafetyAcceptanceTest {
    private static final Pattern STATEMENT_DIRECTO = Pattern.compile("\\.createStatement\\s*\\(");

    @Test
    void consultasDeProduccionNoCreanStatementSqlSinParametros() throws Exception {
        Path raiz = Path.of("src", "main", "java");
        ArrayList<String> hallazgos = new ArrayList<>();
        try (var archivos = Files.walk(raiz)) {
            archivos.filter(p -> p.toString().endsWith(".java")).forEach(archivo -> {
                try {
                    String fuente = Files.readString(archivo, StandardCharsets.UTF_8);
                    if (STATEMENT_DIRECTO.matcher(fuente).find()) hallazgos.add(archivo.toString());
                } catch (Exception e) {
                    throw new IllegalStateException("No se pudo revisar " + archivo, e);
                }
            });
        }
        assertTrue(hallazgos.isEmpty(), "Usa PreparedStatement para SQL con parámetros. Hallazgos: " + hallazgos);
    }
}
