# Changelog

Cada tarea completada se registra aquí. El modelo agrega la entrada al inicio (debajo de la línea `---`).

Formato:
```
## [fecha] — [descripción corta]
- Cambio 1
- Cambio 2
Archivos: `archivo1.java`, `archivo2.js`
```

---

## 2026-10-06 — A6 Logs estructurados (SLF4J)
- Reemplazada la dependencia `slf4j-simple` por `logback-classic` en `pom.xml`.
- Creado archivo de configuración `src/main/resources/logback.xml`.
- Reemplazados todos los `System.out.println` y `System.err.println` por `logger.info` y `logger.error` en las clases Java.
- Agregada importación de SLF4J `Logger` en las clases modificadas.
Archivos: `pom.xml`, `src/main/resources/logback.xml`, y múltiples archivos `.java`.
