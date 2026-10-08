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

## 2026-10-08 — S5 Sanitizar XSS en chat (Claude Sonnet 5.5)
- Backend: nuevo `servicio/ChatSanitizer.java`. `SocketHandler` valida `mensaje_chat` y `mensaje_privado` antes de guardar/reenviar: canal solo `general`/`anuncios`/`clash` (los clientes ya no pueden escribir en `clash_logs` ni en salas ajenas), tipo `texto`/`imagen`/`video`, quita etiquetas HTML y caracteres de control, tope de 500 caracteres (texto) y 50 (usuario), y reenvía solo los campos permitidos. Imagen = data URI `png/jpeg/gif/webp` (SVG rechazado) o `/api/media/{id}`; video = `/api/media/{id}`; multimedia solo en `anuncios`. Mensajes inválidos se descartan con `logger.warn`.
- Decisión: el servidor NO escapa a entidades HTML (`&lt;`) porque los frontends pintan con `textContent`/JSX y se verían entidades dobles; en su lugar elimina las etiquetas y cada frontend escapa al pintar. (Dif. con el texto del roadmap "escapar `<script>` antes de guardar": el resultado de seguridad es el mismo.)
- Registro (`RutasAuth`): username de 3–30 caracteres sin `< > " ' & `` ` `` ni caracteres de control (se muestra en chat, rankings y paneles admin). Solo afecta registros nuevos.
- `RutasMedia`: `GET /api/media/{id}` responde con `X-Content-Type-Options: nosniff`.
- Frontend legacy (`public/app.js`, el que carga `index.html`): `agregarBurbuja` y `convertirLinks` reescritos con `createElement`/`textContent` (links solo http(s) con `rel="noopener noreferrer"`); `abrirMediaModal` sin `innerHTML` y con `src` validado por `resolverMedia()`; nuevo helper `esc()` aplicado a las plantillas `innerHTML` que muestran datos de usuario: toasts (`alguien_buscando`, `nuevo_sorteo`, `sorteo_ganador`), nombre en la barra del admin, nombre de Clash en el registro, ranking, transacciones admin (incluye `referencia`, texto libre del usuario), disputas, estadísticas admin y sorteos.
- `public/js/chat.js` (duplicado sin cargar por `index.html`): mismos cambios que `app.js` para `agregarBurbuja`/`convertirLinks`.
- React (`frontend/src/pages/Chat.jsx`): ya escapaba por defecto; se agrega `resolverMedia()` para que `src` y `window.open` de imagen/video solo acepten `/api/media/{id}` o data URI raster (evita `javascript:` y SVG).
- No incluido (otras tareas): `usuario` del mensaje sigue viniendo del cliente (S2); restricción de `anuncios` a admin en servidor (S3); `SELECT *` que envía hashes al rival en `partida_encontrada` (S6). `public/js/{admin,leaderboard,sorteos}.js` (duplicados sin cargar) conservan `innerHTML` sin `esc()`.
- Pendiente de verificación manual: (1) `mvn -q clean compile`; (2) `cd frontend && npm run build` (y `npx cap sync` si vas a generar la app móvil, porque Android/iOS llevan copia del bundle); (3) `git grep -nE "innerHTML|dangerouslySetInnerHTML" -- public/app.js frontend/src` y revisar que cada coincidencia sea contenido fijo o pase por `esc()`; (4) probar en dos navegadores: enviar `<img src=x onerror=alert(1)>` y `<script>alert(1)</script>` en general/clash/chat privado (debe verse como texto o quedar vacío, sin alert), enviar un link `https://...` (clickeable), subir imagen y video como admin en Anuncios (se ven y abren en el visor), y registrar un usuario `<b>x</b>` (debe rechazarse).
Archivos: `ChatSanitizer.java`, `SocketHandler.java`, `RutasAuth.java`, `RutasMedia.java`, `public/app.js`, `public/js/chat.js`, `frontend/src/pages/Chat.jsx`, `ARCHITECTURE.md`

## 2026-10-08 — F5 Renombrar "apuestas" (Claude Sonnet 5.5)
- Criterio: "apuesta" como cantidad de dinero → **monto**; "apostar" como acción → **realizar torneo**; "apostado" (total acumulado) → **monto en torneos**. Sin migración: solo cambio de nombres (pedido explícito del dueño; no hay usuarios reales).
- BD (`ConexionDB.inicializarTablas`): `matches.apuesta` → `matches.monto`; `users.total_apostado` → `users.total_monto_torneos`.
- Backend: `WalletService.apostar()` → `realizarTorneo()` (mensajes y logs actualizados: "TORNEO", "El monto debe ser positivo"); parámetro `apuesta` → `monto` en `liquidar()`; `SocketHandler` (`ActiveMatch.monto`, `monto1/monto2`, `maxMonto`, `torneoResult`, INSERT en `matches`); `RutasAdmin` (lee `matches.monto`); `RutasLeaderboard` (periodo `apostado` → `monto_torneos`, columna `total_monto_torneos`).
- Contratos JSON/socket cambiados: `maxApuesta` → `maxMonto` (eventos `partida_encontrada` y `restaurar_partida`); `GET /api/leaderboard/apostado` → `/api/leaderboard/monto_torneos`; disputas devuelven `monto` en vez de `apuesta`.
- Frontend React: `Match.jsx` (etiquetas "Monto", `monto`, `maxMontoPermitido`, `handleMontoChange`), `Admin.jsx` (disputas), `Leaderboard.jsx` (pestaña "Monto en torneos", clase `lb-money-torneos`; además las pestañas ahora usan etiquetas legibles en vez de la clave cruda), `Leaderboard.css`, `useAppStore.js` (`matchState.monto`).
- Frontend legacy: `public/index.html`, `public/app.js`, `public/js/{match,admin,leaderboard,chat}.js`, `public/style.css` (id `confirm-monto`, pestaña/etiquetas, clase `lb-money-torneos`).
- No renombrados (identificadores en inglés `bet`): `maxBetAllowed`, `inputBetAmount`, ids `input-bet-amount` / `max-bet-info` en el frontend legacy.
- Docs: `ARCHITECTURE.md` (tablas `users`/`matches`, flujo de dinero y diagrama de partida).
- Pendiente de verificación manual: (1) ANTES de desplegar, en la BD existente ejecutar `ALTER TABLE matches RENAME COLUMN apuesta TO monto; ALTER TABLE users RENAME COLUMN total_apostado TO total_monto_torneos;` (o recrear las tablas); (2) `mvn -q clean compile`; (3) `cd frontend && npm run build`; (4) `git grep -inE "apuest|apostad|apostar"` para confirmar que no quedan referencias; (5) probar flujo completo: buscar partida → confirmar monto → liquidar → ranking "Monto en torneos" → disputa en panel admin.
Archivos: `ConexionDB.java`, `WalletService.java`, `SocketHandler.java`, `RutasAdmin.java`, `RutasLeaderboard.java`, `Match.jsx`, `Admin.jsx`, `Leaderboard.jsx`, `Leaderboard.css`, `useAppStore.js`, `public/index.html`, `public/app.js`, `public/js/*.js`, `public/style.css`, `ARCHITECTURE.md`

## 2026-10-08 — L2 `.gitignore` (Claude Sonnet 5.5)
- Creado `.gitignore` en la raíz (no existía; solo `frontend/` tenía el suyo). Ignora: `target/`, `*.class`, `*.jar` (excepto `.mvn/wrapper/*.jar`), `.env` / `.env.*` (excepto `.env.example`), `dependency-reduced-pom.xml`, `mvn-wrapper.zip`, logs, `node_modules/`, `frontend/dist/`, archivos de IDE (`.idea/`, `*.iml`) y del SO (`.DS_Store`, `Thumbs.db`).
- Verificado que ignorar estos archivos no rompe el build: el `Dockerfile` compila con su propio Maven (`mvn clean package`) y el `Procfile` genera el JAR en el deploy.
- Pendiente de verificación manual: si `target/`, `dependency-reduced-pom.xml` o `mvn-wrapper.zip` ya estaban versionados, `.gitignore` no los des-versiona; hay que ejecutar `git rm -r --cached target dependency-reduced-pom.xml mvn-wrapper.zip` y luego commit.
Archivos: `.gitignore`

## 2026-10-08 — L3 Eliminar archivos sueltos (Claude Sonnet 5.5)
- Eliminados del proyecto (movidos a `scratch/L3_archivos_sueltos_eliminados/`, mismo criterio que L1): `Test.java`, `TestError.java` (raíz, no compilaban con Maven), `src/main/java/com/torneosflash/TestWs.java` (clase de prueba con `main`, sin referencias) y `admin-db.html` de la raíz.
- `admin-db.html` de la raíz NO era un duplicado inerte: `RutasDbAdmin` lo servía desde el directorio de trabajo y el `Dockerfile` lo copiaba (`COPY admin-db.html .`). Se conservó `public/admin-db.html` como fuente única (es idéntico salvo por `console.error(e)` extra en los `catch`).
- `Dockerfile`: ahora `COPY public/admin-db.html ./admin-db.html` (sin esto el build de Docker fallaba al borrar el archivo de la raíz).
- `RutasDbAdmin.java`: lista de rutas de búsqueda actualizada a `admin-db.html`, `public/admin-db.html`, `../public/admin-db.html`, `src/main/resources/admin-db.html` (cubre Docker y ejecución local/Procfile).
- `ARCHITECTURE.md`: actualizada la nota de `admin-db.html`.
- Pendiente de verificación manual: compilar (`mvn -q clean compile`), build Docker y abrir `/admin-db/<secret>`.
Archivos: `Dockerfile`, `src/main/java/com/torneosflash/servidor/RutasDbAdmin.java`, `ARCHITECTURE.md`, `Test.java`, `TestError.java`, `TestWs.java`, `admin-db.html`

## 2026-10-08 — L1 Eliminar modelos POO sin uso (Claude Sonnet 5.5)
- Verificado por imports: ninguna clase de `modelo/` ni de `interfaces/` es usada por DAOs, servicios, rutas, sockets ni `Main` (todo el backend trabaja con `JsonObject`).
- Sacadas de `src/` las 13 clases de `modelo/` y las 3 interfaces de `interfaces/` (`Exportable`, `Procesable`, `Validable`), que solo existían para esas clases.
- Movidas a `scratch/L1_modelo_eliminado/` y `scratch/L1_interfaces_eliminado/` (no hay herramienta de borrado): borrar esas carpetas con `git rm -r`.
- Limpiado comentario obsoleto en `Main.java` que mencionaba `Collection<Entidad>`.
Archivos: `src/main/java/com/torneosflash/modelo/*`, `src/main/java/com/torneosflash/interfaces/*`, `Main.java`

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
