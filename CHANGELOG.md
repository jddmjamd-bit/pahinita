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

## 2026-10-08 — L3 Eliminar archivos sueltos (Claude Sonnet 5.5)
- Eliminados del proyecto (movidos a `scratch/L3_archivos_sueltos_eliminados/`, mismo criterio que L1): `Test.java`, `TestError.java` (raíz, no compilaban con Maven), `src/main/java/com/torneosflash/TestWs.java` (clase de prueba con `main`, sin referencias) y `admin-db.html` de la raíz.
- `admin-db.html` de la raíz NO era un duplicado inerte: `RutasDbAdmin` lo servía desde el directorio de trabajo y el `Dockerfile` lo copiaba (`COPY admin-db.html .`). Se conservó `public/admin-db.html` como fuente única (es idéntico salvo por `console.error(e)` extra en los `catch`).
- `Dockerfile`: ahora `COPY public/admin-db.html ./admin-db.html` (sin esto el build de Docker fallaba al borrar el archivo de la raíz).
- `RutasDbAdmin.java`: lista de rutas de búsqueda actualizada a `admin-db.html`, `public/admin-db.html`, `../public/admin-db.html`, `src/main/resources/admin-db.html` (cubre Docker y ejecución local/Procfile).
- `ARCHITECTURE.md`: actualizada la nota de `admin-db.html`.
- Pendiente de verificación manual: compilar (`mvn -q clean compile`), build Docker y abrir `/admin-db/<secret>`.
Archivos: `Dockerfile`, `src/main/java/com/torneosflash/servidor/RutasDbAdmin.java`, `ARCHITECTURE.md`, `Test.java`, `TestError.java`, `TestWs.java`, `admin-db.html`

## 2026-10-07 — 20 Capacitor (app móvil) (Gemini 3.1 Pro (High))
- Implementada integración inicial de Capacitor para envolver la app React en nativo (Android e iOS).
- Instalados plugins `@capacitor/core`, `@capacitor/splash-screen`, `@capacitor/push-notifications` y `@capacitor/camera`.
- Creado hook en React `useCapacitor.js` para ocultar el Splash Screen y solicitar permisos de cámara, micrófono y notificaciones push al abrir la app.
- Modificado `AndroidManifest.xml` para incluir explícitamente los permisos `CAMERA`, `RECORD_AUDIO` y `POST_NOTIFICATIONS`.
- Utilizado `@capacitor/assets` para generar automáticamente todos los íconos de la app y el splash screen para Android e iOS a partir de `logo.jpeg`.
Archivos: `frontend/package.json`, `frontend/src/hooks/useCapacitor.js`, `frontend/src/App.jsx`, `frontend/android/app/src/main/AndroidManifest.xml`

## 2026-10-06 — F6 Build + minificación (Gemini Pro High)
- Configurada optimización de construcción en `vite.config.js` para realizar "code splitting" automático.
- Se configuró Vite para separar los paquetes de `node_modules` en diferentes "chunks" durante la etapa de compilación (`manualChunks`), logrando una mejor carga y aprovechando el "tree shaking" y la minificación por defecto de Vite 8.
Archivos: `frontend/vite.config.js`

## 2026-10-06 — F4 Mejorar diseño (Gemini Pro High)
- Implementado sistema de diseño unificado en `index.css` con variables CSS para colores, bordes y espaciado (dark mode premium y UI/UX moderno).
- Creados estilos base globales para inputs, botones y formularios (glassmorphism y animaciones) en `App.css`.
- Reescribieron los archivos CSS de las vistas (`Auth.css`, `Lobby.css`, `Chat.css`, `Match.css`, `Finance.css`, `Leaderboard.css`, `Sorteos.css`, `Admin.css`) adoptando el nuevo sistema de diseño, sombras, fuentes (Inter, Outfit) y layout responsivo.
Archivos: `frontend/src/index.css`, `frontend/src/App.css`, `frontend/src/pages/*.css`

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
