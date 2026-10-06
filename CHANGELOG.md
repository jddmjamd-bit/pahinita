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

## 2026-10-06 — F1 Migrar a React + Vite
- Configurado `react-router-dom` en `main.jsx` con `BrowserRouter`.
- Reemplazado `App.jsx` para definir las rutas base del frontend (`/auth`, `/lobby`, `/admin`).
- Creados los componentes iniciales (`Auth.jsx`, `Lobby.jsx`, `Admin.jsx`) en el directorio `src/pages` para migración gradual.
Archivos: `frontend/src/main.jsx`, `frontend/src/App.jsx`, `frontend/src/pages/*.jsx`

## 2026-10-06 — A6 Logs estructurados (SLF4J)
- Reemplazada la dependencia `slf4j-simple` por `logback-classic` en `pom.xml`.
- Creado archivo de configuración `src/main/resources/logback.xml`.
- Reemplazados todos los `System.out.println` y `System.err.println` por `logger.info` y `logger.error` en las clases Java.
- Agregada importación de SLF4J `Logger` en las clases modificadas.
Archivos: `pom.xml`, `src/main/resources/logback.xml`, y múltiples archivos `.java`.
