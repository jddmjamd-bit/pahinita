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

## 2026-10-06 — F3 Estado centralizado (Gemini Pro High)
- Instalados `zustand` y `socket.io-client` en el frontend.
- Creado store global en `useAppStore.js` para manejar el estado del usuario, la instancia de socket y el estado de las partidas.
- Refactorizados todos los componentes (Auth, Lobby, Match, Chat, Finance, Leaderboard, Sorteos, Admin) para reemplazar `localStorage` por el store de Zustand.
Archivos: `frontend/package.json`, `frontend/src/store/useAppStore.js`, `frontend/src/pages/*.jsx`

## 2026-10-06 — F2 Completar migración de módulos a React (Gemini Pro High)
- Migrados completamente los módulos restantes a componentes funcionales de React (`Leaderboard`, `Finance`, `Sorteos`, `Chat`, `Match`, `Admin`).
- Refactorizado `Lobby.jsx` para actuar como el Layout principal (Sidebar) con react-router-dom `<Outlet>`.
- Refactorizado `App.jsx` para utilizar rutas anidadas bajo el layout principal.
- Añadidos archivos de estilos `.css` individuales para cada uno de los componentes migrados, respetando el diseño original (glassmorphism/dark theme).
Archivos: `frontend/src/pages/*.jsx`, `frontend/src/pages/*.css`, `frontend/src/App.jsx`
## 2026-10-06 — F2 Dividir app.js en módulos (Gemini Pro High)
- Dividido el monolito `public/app.js` en múltiples archivos de vanilla JS (auth, lobby, match, chat, finance, leaderboard, admin, sorteos) dentro de `public/js/`.
- Creados los componentes React estructurales (Match, Chat, Finance, Leaderboard, Sorteos) en `frontend/src/pages/`.
- Migrado completamente el código de autenticación a React en `Auth.jsx` y creado su archivo de estilos `Auth.css`.
- Actualizado `App.jsx` con las nuevas rutas para los componentes añadidos.
Archivos: `public/js/*.js`, `frontend/src/App.jsx`, `frontend/src/pages/*.jsx`, `frontend/src/pages/*.css`

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
