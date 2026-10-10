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

## 2026-10-09 — S8 Revisión y ajustes del rate limiting (Claude Sonnet 5.5)
- Revisión de lo ya implementado en S8 (`RateLimiter`, `RateLimitMiddleware`, `AppConfig`, `Main`): la lógica de conteo, la clasificación de rutas y los límites (60/min general, 10/min financiero por IP) cumplen el roadmap y no encontré errores de compilación por lectura (Javalin 6.1.0: `before`, `skipRemainingHandlers`, `ctx.header` existen; `errorJson` es accesible desde el mismo paquete). No compilé.
- Fix backend (`RateLimitMiddleware`): la respuesta 429 ahora lleva cabeceras CORS (`Allow-Origin` reflejando el `Origin`, `Allow-Credentials`, `Expose-Headers: Retry-After, X-RateLimit-*`). El middleware se registra antes de que Javalin instale el plugin CORS y al rechazar se saltan los handlers restantes, así que la 429 podía salir sin CORS y la app React/Capacitor (otro origen, `API_BASE_URL` fijo a Render) la veía como "Error de conexión" en vez del mensaje. No lo pude comprobar ejecutando, por eso se pone a mano (si el plugin ya las añadía, `ctx.header` solo las reemplaza con el mismo valor).
- Fix frontend para que el límite no se active en uso normal: `Match.jsx` consultaba `/api/comisiones/simular` en cada tecla/flecha del monto (ahora con debounce de 400 ms); `Sorteos.jsx` hacía 5 peticiones por clic en +/− (participar + recargar todo) y con 60/min eran solo 12 clics por minuto, ahora recarga solo `/api/raffle/offers` (2 por clic); `Auth.jsx` mostraba "usuario/correo ocupado" o `undefined` si una validación recibía 429, ahora muestra "Demasiadas solicitudes, espera unos segundos".
- No auditados: `frontend/src/pages/Admin.jsx` y el frontend legacy de `public/` (`app.js` pesa 120 KB; no pude buscar `setInterval`/`fetch` sin `grep`). Es el que se sirve en `/`, así que conviene revisarlo (paso 4 abajo).
- Sin cambios y a tener presente: el límite es por IP y en Colombia las redes móviles comparten IP (CGNAT), así que si aparecen 429 en uso real subir `RATE_LIMIT_GENERAL_PER_MIN` en Render antes de tocar código.
- Pendiente de verificación manual: (1) `mvn -q clean compile`; (2) `cd frontend && npm run build` (y `npx cap sync` si vas a generar la app móvil); (3) los pasos de prueba de la entrada S8 anterior (cubo financiero: diez `400` y luego `429`; cubo general: sesenta `200` y cinco `429`; webhook de Wompi nunca `429`); (4) para el frontend legacy: `git grep -nE "setInterval|setTimeout\(.*fetch" -- public/app.js public/js` y revisar que ningún polling pase de ~1 petición por segundo a `/api/`; (5) CORS de la 429 desde la app React: con el backend local o en Render, abrir DevTools > Network en la página de registro, escribir usuarios distintos rápido hasta superar 60 peticiones/min y comprobar que la respuesta 429 se ve como `429` (no `(failed) net::ERR_FAILED`) y que aparece "Demasiadas solicitudes"; (6) en Sorteos pulsar "+" varias veces seguidas y comprobar en Network que cada clic hace solo `participate` y `offers`; en Match escribir un monto con las flechas del input y comprobar que `simular` se llama una sola vez al soltar.
Archivos: `RateLimitMiddleware.java`, `frontend/src/pages/Auth.jsx`, `frontend/src/pages/Match.jsx`, `frontend/src/pages/Sorteos.jsx`, `ARCHITECTURE.md`

## 2026-10-09 — S8 Rate limiting anti-spam por IP (Claude Sonnet 5.5)
- Nuevo `servicio/RateLimiter.java`: lógica pura de conteo con ventana deslizante de 1 minuto por clave (sin Javalin). Cada clave guarda solo las solicitudes permitidas, así que ocupa como máximo `limite` entradas; `ConcurrentHashMap.compute` hace el intento atómico por clave (dos requests simultáneos no se cuelan juntos); limpia claves inactivas cada minuto, tope de 100.000 claves y log de bloqueo una sola vez por clave y ventana.
- Nuevo `servidor/RateLimitMiddleware.java` (`app.before` global, registrado en `Main` antes de rutas y sockets): 60 req/min por IP en la API y los paneles admin, 10 req/min en rutas financieras (`/api/deposit`, `/api/transaction/create`, `/api/transaction/withdraw`, `/api/wompi/init`, `/api/admin/transaction/process`, `/api/admin/resolve-dispute`; cuentan también en el general) y cubo propio de 300/min para `GET /api/media/*`. Al pasarse responde 429 con `{error, retryAfter}` + `Retry-After`; las respuestas normales llevan `X-RateLimit-Limit` y `X-RateLimit-Remaining`.
- `AppConfig` + `Main`: variables de entorno `RATE_LIMIT_ENABLED` (true), `RATE_LIMIT_GENERAL_PER_MIN` (60), `RATE_LIMIT_FINANCIAL_PER_MIN` (10), `RATE_LIMIT_MEDIA_PER_MIN` (300) y `RATE_LIMIT_PROXY_HOPS` (1). Valores inválidos caen al default con log de error (mismo criterio que los montos).
- IP real: en Render `ctx.ip()` es la del proxy y todos los usuarios compartirían un solo cupo, así que se lee `X-Forwarded-For` desde la DERECHA (`RATE_LIMIT_PROXY_HOPS`); la parte izquierda la escribe el cliente y se podría falsificar para saltarse el límite.
- Exentos a propósito: `OPTIONS` (preflight CORS), archivos estáticos, WebSocket y `POST /api/wompi/webhook` (lo llama Wompi y limitarlo podría perder pagos reales; su defensa es la firma, S7). La ruta se normaliza (minúsculas, `//`, `/` final) para que `/api/deposit/` cuente igual que `/api/deposit`.
- Decisión a revisar: el roadmap dice 60/min por IP; jugadores detrás de la misma red móvil (CGNAT) comparten cupo. Si en uso real aparecen 429 en pantallas normales, subir `RATE_LIMIT_GENERAL_PER_MIN` en Render antes de tocar código. Cuando S1 conecte el JWT conviene añadir un cubo por `userId`.
- No incluido: spam por Socket.IO (chat, `buscar_partida`) no pasa por este middleware; un cubo más estricto para login/registro (fuerza bruta); no se escribieron pruebas automáticas (el proyecto no tiene JUnit en el `pom.xml`) y no pude compilar. No se emite evento en el bus (A4 aún no existe). El frontend no se modificó: las pantallas que ya leen `error` de la respuesta (recarga, retiro y Wompi desde D5) mostrarán el mensaje de 429 en español; no revisé el resto de llamadas `fetch`, que podrían mostrar un error genérico.
- Pendiente de verificación manual: (1) `mvn -q clean compile`; (2) arrancar local y probar el cubo financiero (PowerShell): `1..12 | % { curl.exe -s -o NUL -w "%{http_code} " -X POST http://localhost:5000/api/wompi/init -H "Content-Type: application/json" -d "{}" }` debe dar diez `400` (monto inválido, no crea filas) y luego `429 429`; (3) esperar 1 minuto y probar el general: `1..65 | % { curl.exe -s -o NUL -w "%{http_code} " http://localhost:5000/api/comisiones/distribucion }` debe dar sesenta `200` y cinco `429`; con `curl.exe -i` se ven `Retry-After` y `X-RateLimit-*`; (4) cargar la app normalmente (login, lobby, chat con imágenes, sorteos, ranking) y confirmar que no aparece ningún 429 en DevTools > Network; (5) ya desplegado en Render, abrir `/check-ip` y mirar el `x-forwarded-for` que muestra: si trae dos o más IPs separadas por coma (p. ej. `tuIP, otraIP`), definir `RATE_LIMIT_PROXY_HOPS=2` en Render; si trae solo tu IP, dejar el default 1; (6) el webhook `POST /api/wompi/webhook` nunca debe responder 429.
Archivos: `RateLimiter.java`, `RateLimitMiddleware.java`, `AppConfig.java`, `Main.java`, `ARCHITECTURE.md`

## 2026-10-09 — D4 Unificar lógica de comisiones en ComisionService (Claude Sonnet 5.5)
- Nuevo `servicio/ComisionService.java`: única fuente de verdad de la política de comisiones (lógica pura, `BigDecimal`, sin BD). `calcular(montoPorJugador)` devuelve un `Desglose` inmutable (pozo, %, comisión, premio y monto por categoría); `ganancia` es el residuo, así el reparto siempre suma exactamente la comisión. El enum `Categoria` (sorteos, misiones, logros, leaderboard, devolucion, ganancia, referidos) reemplaza las listas de strings repetidas. Marcado `// REVIEW-MONEY`.
- `WalletService`: `liquidar()` ya no calcula nada: pide el desglose a `ComisionService` y lo usa para pagar, actualizar `gen_*`/`ganancia_generada`, insertar en `admin_wallet` (ahora con un bucle sobre el desglose, no 7 llamadas copiadas) y repartir el pozo del leaderboard. `LiquidacionResult` suma el campo `desglose`. `calcularPorcentajeComision()` queda `@Deprecated` delegando al servicio. Constructor nuevo `WalletService(db, comisiones)`; el de un argumento se mantiene.
- La fórmula matemática es la misma que tenía `WalletService` (mismos redondeos); no cambia cuánto se paga ni cuánto se cobra.
- `SocketHandler` y `RutasAdmin` (resolver disputa): dejan de calcular `comSorteos / 2.0` con `double`; usan `RutasSorteos.acumularTicketsPorPartida(db, userId, liq.desglose)`. En `RutasAdmin` el bloque de tickets+emit que estaba copiado para J1 y J2 pasó a un método `acreditarTickets`. La lista de categorías de `/api/admin/stats` sale de `ComisionService.Categoria`.
- `RutasSorteos`: `acumularTickets` ahora recibe `BigDecimal` (la versión con `double` queda `@Deprecated`); mismo truncado a pesos enteros que antes.
- Nuevo `servidor/RutasComisiones.java` (registrado en `Main`): `GET /api/comisiones/simular?monto=` (valida con `ValidadorMonto.torneo()`, 400 si es inválido) y `GET /api/comisiones/distribucion`. Solo lectura, sin datos de usuarios.
- Frontend React: `Match.jsx` ya no replica la fórmula; consulta `/api/comisiones/simular` para "Si ganas recibes". `Admin.jsx` toma los % del desglose de `/api/comisiones/distribucion` (antes escritos a mano en los títulos).
- Cambio visible: la UI vieja calculaba `floor(pozo - pozo·%)` y el servidor paga `pozo - floor(pozo·%)`, así que podía mostrar $1 menos de lo que realmente se paga (con monto 5000 mostraba 8166 y se pagan 8167). Ahora la UI muestra exactamente lo que se paga. Con monto fuera de rango (ej. > MONTO_MAX_TORNEO) muestra "Ganancia: $0" porque el servidor rechaza el monto.
- Efecto menor: las filas de `admin_wallet` de una partida ahora se insertan en orden sorteos, misiones, logros, leaderboard, devolución, ganancia, referidos (antes referidos iba antes que ganancia). Solo se usan sumas por categoría, no hay impacto.
- Decisión a revisar: los % de reparto son constantes del código (un solo lugar), no variables de entorno, para que nadie pueda dejar una config que sume más de 100% y dé ganancia negativa. Si se quieren configurables hay que validar la suma.
- No incluido: frontend legacy en `public/` (no revisado), tarifa de pasarela Wompi de `RutasFinanzas` (otro cobro, sigue con `double`, es D1) y los `UPDATE` que corrigen `victorias_normales`->`victorias_disputa` tras `liquidar()` en `resolve-dispute` (no son comisiones; conviene moverlos dentro de la transacción de `liquidar` en D2).
- Pendiente de verificación manual: (1) `mvn -q clean compile` (no pude compilar); (2) `GET /api/comisiones/simular?monto=5000` debe dar `pozo 10000, porcentajeComision 0.1833, comision 1833, premio 8167`, y `?monto=1000` da `comision 500, premio 1500`; `?monto=abc` y `?monto=99999` dan 400; (3) `GET /api/comisiones/distribucion` debe dar sorteos 20, misiones 10, logros 5, leaderboard 15, devolucion 15, ganancia 25, referidos 10; (4) jugar o resolver una partida de monto 5000 y comprobar en `admin_wallet` (`detalle = 'Match #N'`): sorteos 366, misiones 183, logros 91, leaderboard 274, devolución 274, referidos 183, ganancia 462 (suma 1833) y que el ganador recibió 8167; (5) en el panel admin > Finanzas, los títulos del desglose muestran los % (ej. "Sorteos (20%)"); (6) en Match, al escribir monto 5000 aparece "Si ganas recibes: $8167"; (7) resolver una disputa y confirmar que ambos jugadores reciben el evento `tickets_ganados`.
Archivos: `ComisionService.java`, `WalletService.java`, `RutasComisiones.java`, `RutasSorteos.java`, `RutasAdmin.java`, `SocketHandler.java`, `Main.java`, `Match.jsx`, `Admin.jsx`, `ARCHITECTURE.md`

## 2026-10-09 — S6 No enviar hashes al frontend (Claude Sonnet 5.5)
- Nuevo `servicio/UsuarioVista.java`: lista blanca de columnas de `users`. `COLUMNAS_SESION` (propio usuario, sin `password`) y `paraRival()` (solo `id, username, total_partidas, total_victorias, total_derrotas, faltas, salidas_chat`).
- Fuga corregida (la grave): `SocketHandler` hacía `SELECT * FROM users` y enviaba esas filas completas como `p1`/`p2` en `partida_encontrada` y como `rival` en `restaurar_partida`, así que cada jugador recibía el **hash de contraseña, email, teléfono, player_tag y saldo** de su rival. Ahora: `buscar_partida` e `intentarMatcheo` leen `COLUMNAS_SESION`, y lo que se emite pasa por `UsuarioVista.paraRival()`. En `restaurar_partida` el rival se relee de la BD por id (antes salía del `userData` que mandó el cliente) y el tope `maxMonto` se sigue calculando con el saldo real, que ya no se envía.
- `UsuarioDAO`: `buscarPorId` y `listarTodos` usan `COLUMNAS_SESION`. `buscarPorEmail` (SELECT *) reemplazado por `buscarCredencialesPorEmail` (solo `id, password`, uso interno del login).
- `RutasAuth`: login verifica con las credenciales y responde con `buscarPorId` (lista blanca); `/api/session` ya no depende de `user.remove("password")`.
- Frontend sin cambios: `app.js` solo usa `username, total_partidas, total_victorias, salidas_chat, faltas` del rival (todos incluidos).
- No incluido: `RutasDbAdmin` (consola admin protegida por secreto, devuelve tablas completas a propósito); `GET /api/admin/stats` sigue enviando `email` de todos (solo admin, pero sin verificación de rol hasta S3); `registrar_socket` sigue confiando en el `user` que manda el cliente (S2). El log de login imprime los primeros 200 chars de la respuesta (incluye email/saldo): conviene quitarlo.
- Pendiente de verificación manual: (1) `mvn -q clean compile`; (2) `git grep -n "buscarPorEmail\|SELECT \* FROM users" -- src` debe dar vacío; (3) con dos usuarios en dos navegadores: login OK, buscar partida, abrir DevTools → Network → WS y revisar el frame `partida_encontrada`: `p1`/`p2` solo deben traer los 7 campos públicos; (4) recargar la página en plena negociación y revisar `restaurar_partida` (`rival` con 7 campos, nombre y stats del rival se siguen viendo); (5) `GET /api/session` y respuesta de `/api/login` sin `password`.
Archivos: `UsuarioVista.java`, `UsuarioDAO.java`, `RutasAuth.java`, `SocketHandler.java`, `ARCHITECTURE.md`

## 2026-10-09 — D5 Validar montos en el servidor (Claude Sonnet 5.5)
- Nuevo `servicio/ValidadorMonto.java`: valida todo monto que llega del cliente. Válido = número (JSON number o string numérico; se rechazan null, booleanos, objetos, notación científica, `+`, texto), > 0, en pesos enteros (`5000` y `5000.00` sí; `5000.5` no) y dentro del rango de su tipo. Devuelve `BigDecimal` escala 0 y mensajes listos para el usuario ("El monto mínimo para retirar es $10.000").
- Rangos (ya estaban en `AppConfig`, configurables por env var; defaults en COP): depósito `MONTO_MIN_DEPOSITO`=1000 / `MONTO_MAX_DEPOSITO`=5.000.000; retiro `MONTO_MIN_RETIRO`=10000 / `MONTO_MAX_RETIRO`=5.000.000; torneo `MONTO_MIN_TORNEO`=1000 / `MONTO_MAX_TORNEO`=10000 (coincide con la tabla de comisión, que llega al piso en pozo 20000); sorteo `MONTO_MIN_SORTEO`=1000 / `MONTO_MAX_SORTEO`=100.000.000 (acotado a `Integer.MAX_VALUE` porque el precio es `int`). Los defaults replican los topes que ya tenía el frontend.
- `RutasFinanzas`: `/api/deposit`, `/api/transaction/create`, `/api/transaction/withdraw` y `/api/wompi/init` validan con `ValidadorMonto` y responden 400 + `{error}` si no pasa. Antes `transaction/create` y `wompi/init` aceptaban cualquier `double` (negativos, 0, NaN, gigantes) y `deposit`/`withdraw` explotaban con 500 ante texto. Ya no se guarda `double` en `transactions.monto`: se guarda el `BigDecimal` validado.
- `SocketHandler`: `iniciar_juego` valida `dinero` (rango de torneo) y guarda el voto normalizado; si es inválido solo se le avisa al que lo envió (`error_negociacion`) y no se guarda. Un voto nuevo borra las confirmaciones previas, y al cobrar (`confirmar_partida_resp`) se exige que ambos votos existan y coincidan (antes se cobraba el monto del primer jugador aunque el otro hubiera cambiado el suyo después de confirmar). Antes de cobrar a nadie se verifica que los dos tengan saldo; el mínimo de `buscar_partida` ya no es `1000` fijo sino `MONTO_MIN_TORNEO`.
- `RutasSorteos`: `POST /api/admin/raffle/create` valida `precio` con el rango de sorteo y `duracionMinutos` (1 minuto a 1 año); la duración ya no se concatena en el SQL (`make_interval(mins => ?)`). `POST /api/raffle/participate` rechaza `ticketsDelta` = 0 o con |valor| > 1.000.000.
- `Main`: crea `ValidadorMonto` y lo pasa a `RutasFinanzas`, `RutasSorteos` y `SocketHandler` (cambian sus firmas).
- Frontend (legacy `public/app.js` y `public/js/finance.js`, React `Finance.jsx`): ahora muestran el mensaje de error del servidor en recarga manual (antes mostraba "undefined") y en Wompi (antes siempre "Error iniciando Wompi").
- No incluido (otras tareas): `/api/deposit` sigue sin exigir admin (S3: no está bajo `/api/admin/*`, así que el middleware de S3 no lo cubriría; hay que moverlo o protegerlo explícitamente) y todas las rutas siguen leyendo `userId` del body (S1/S2; `JwtServicio`/`AuthMiddleware` existen pero `Main` y `RutasAuth` aún no los usan); el cálculo de comisión Wompi y `match.monto` siguen en `double` (D1); si `realizarTorneo` falla para el segundo jugador después de cobrar al primero, este no se reembolsa (la pre-verificación de saldo lo evita casi siempre, pero el cierre real es D2 con transacción atómica); el webhook de Wompi no verifica que el monto pagado coincida con el solicitado (S7).
- Pendiente de verificación manual: (1) compilar; (2) recarga manual con `-5000`, `0`, `500`, `5000.5`, `abc` y `99999999`: debe mostrar el mensaje de rango/formato y no crear fila en `transactions`; con `5000` debe crearla; (3) retiro de `5000` (bajo el mínimo) y de más que el saldo: error y saldo intacto; (4) Wompi con `500` y `-1`: alerta con el mensaje del servidor, sin fila pendiente; (5) dos usuarios en partida: en la negociación probar monto `500` y `20000` (error solo para quien lo envía), luego `1000` en ambos (modal normal); (6) crear sorteo como admin con precio `1000` y duración `5` (debe funcionar y vencer en ~5 min) y con precio `-1`/duración `0` (error); (7) comprobar en consola del navegador que ninguna llamada devuelve 500.
Archivos: `ValidadorMonto.java`, `RutasFinanzas.java`, `RutasSorteos.java`, `SocketHandler.java`, `Main.java`, `public/app.js`, `public/js/finance.js`, `frontend/src/pages/Finance.jsx`, `ARCHITECTURE.md`

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
