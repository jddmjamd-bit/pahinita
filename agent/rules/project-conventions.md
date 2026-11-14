# Regla: Convenciones del proyecto (UltimateClash / TorneosFlash)

> **Estado del proyecto:** la **Fase 0 del roadmap está COMPLETADA** (S1–S10, D1–D5, A1–A8, F1–F6, L1–L3). El proyecto avanza en **Fase 1 (funcionalidades)** en un entorno cerrado de pruebas, sin clientes reales todavía.
> **Objetivo de todo agente:** construir funcionalidades nuevas con **cero deuda técnica nueva**, **cero retrabajo** y **sin reintroducir ningún problema que la Fase 0 ya resolvió**.
> **Documentos de referencia:** [`ARCHITECTURE.md`](../../ARCHITECTURE.md) · [`analisis_y_roadmap.md`](../../analisis_y_roadmap.md) · [`GUIA_PRUEBAS_Y_VARIABLES.md`](../../GUIA_PRUEBAS_Y_VARIABLES.md) · [`CHANGELOG.md`](../../CHANGELOG.md) · [`auto-push.md`](auto-push.md)

---

## 0. Prioridades y forma de trabajar

1. **Orden de prioridad ante un conflicto:** seguridad del dinero > seguridad de cuentas y datos personales > integridad de la BD > lo que pide la tarea > estilo/comodidad.
2. **Si una petición obliga a romper una regla de seguridad o de dinero:** no la implementes en silencio. Avisa al usuario del riesgo, propone la alternativa segura y espera confirmación explícita. Nunca debilites autenticación, autorización, validación de montos ni el ledger para "hacer que funcione".
3. **Trabajo multi-agente:** hay varios agentes trabajando en funcionalidades distintas, así que puede haber trabajo incompleto. Si al archivo que vas a modificar le faltan partes, **no las edites**; continúa con tu funcionalidad ignorando lo incompleto, salvo que sea necesario para lo que te pidieron. Haz **únicamente lo que se te pide**, sin prestar atención a lo demás mientras no afecte tu tarea.
4. **Sin refactors no pedidos.** No renombres, muevas ni "limpies" código ajeno. Conserva los comentarios y docstrings que no estén relacionados con tu cambio.
5. **Antes de crear algo nuevo, busca si ya existe** (sección 2). Duplicar un helper de seguridad o de dinero es un error, no una opción de estilo.
6. **Idioma:** código, nombres, comentarios, mensajes al usuario, commits y documentación en **español** (como el resto del proyecto).

## 1. Tokens

- Si tus tokens de uso llegan al **10 % o menos** del total: modifica el `CHANGELOG.md` con lo que alcanzaste a hacer y lo que faltó por terminar, notifica al usuario y deja de editar. Nunca dejes a medias una operación de dinero o una migración sin documentarlo.

---

## 2. Piezas reutilizables OBLIGATORIAS

| Necesidad | Usar SIEMPRE | PROHIBIDO |
|---|---|---|
| `userId` en una ruta HTTP | `Peticion.userIdDeSesion(ctx)` / `AuthMiddleware.getUserId(ctx)` (0 o `null` = sin sesión → `ServicioException.noAutenticado`) | Leer `userId`, `id_usuario` o `username` del emisor desde body, query o path |
| `userId` en un evento de socket | `client.getAuthenticatedUserId()` vía el helper `usuarioAutenticado(client, evento)` | Usar `userData` / campos enviados por el cliente como identidad |
| Leer el body | `Peticion.entero / texto / textoOpcional / booleanoOpcional / pathEntero` | `body.get(...)` sin validar tipo ni presencia |
| Errores | `ServicioException.solicitudInvalida / noAutenticado / prohibido / noEncontrado / demasiadasSolicitudes / noDisponible / interno(msg, causa)` + `ManejadorErrores` | `ctx.status(500).result(e.getMessage())`, devolver trazas o mensajes de SQL |
| Montos del cliente | `ValidadorMonto` (`deposito`, `retiro`, `torneo`, `sorteo`; agrega un método nuevo por cada tipo de monto nuevo, con min/max en `AppConfig`) | Parseo manual, `getAsDouble()`, confiar en el cliente |
| Mover saldo | `WalletService` (método nuevo por cada operación nueva) | `UPDATE users SET saldo ...` fuera de `WalletService` |
| Comisiones y reparto | `ComisionService` (`/api/comisiones/simular` para el frontend) | Repetir la fórmula en otra clase o en el frontend |
| Datos de usuario hacia el cliente | `UsuarioVista.COLUMNAS_SESION`, `UsuarioVista.paraRival(...)` | `SELECT *` sobre `users`, añadir `password`/`email`/`telefono` a vistas de terceros |
| Texto libre del usuario | `ChatSanitizer` (o sus mismas reglas: quitar HTML/controles, límite de longitud, listas blancas) | Guardar o reenviar texto crudo |
| Avisar a un usuario | `NotificadorUsuarios` (`notificarSaldo`, `emitirA`, `enviarPush`) desde un listener del bus | Llamar a FCM/socket directamente desde rutas |
| Reaccionar a algo que ocurrió | `EventBus` + constante y `record` en `Eventos` | Acoplar llamadas directas dentro del flujo de partidas o pagos |
| Hilos / tareas asíncronas | `Ejecutores` (`ejecutarIO`, `ejecutarPush`, `enCpu`, `timers()`) — propagan la zona activa (`normal` / `prueba`) | `new Thread`, `Executors.*`, `CompletableFuture.runAsync` sin executor |
| Acceso a BD según zona (normal / sandbox) | `ConexionDB.getConnection()` para negocio (`ZONA_NORMAL` / `ZONA_PRUEBA`); `getConnectionPrincipal()` solo para auth, sesiones y rol/zona | Consultar datos de negocio en `getConnectionPrincipal()` o abrir auth/sesiones en `getConnectionPruebas()` |
| Configuración | `AppConfig` (variable de entorno + default + rango) | `System.getenv` disperso, URLs/números/secretos escritos en el código |
| Esquema de BD | Migración Flyway `V<n>__descripcion.sql` | DDL desde Java |
| Rate limiting | `RateLimitMiddleware` (listas `RUTAS_FINANCIERAS`, `RUTAS_AUTH`) y `RateLimiter` para cupos por usuario | Endpoints sensibles sin límite |
| Secretos de usuario en reposo | El patrón de `CifradoTokenJugador` (cifrado con clave derivada) | Guardar secretos en texto plano |
| HTTP desde el frontend | `llamarApi` de `frontend/src/store/llamarApi.js` | `fetch` suelto en código nuevo (el `fetch` legacy se migra cuando se toque ese archivo) |

---

## 3. Autenticación y sesión (S1, S2)

1. La sesión es un **JWT HS256 en la cookie httpOnly `session`**, con su JTI persistido en `user_sessions`. **Nunca** devolver el token en JSON, guardarlo en `localStorage`/`sessionStorage` ni aceptar la cookie legacy `userId`.
2. Toda ruta que actúe en nombre del usuario exige sesión: si `Peticion.userIdDeSesion(ctx) == 0` → `ServicioException.noAutenticado(...)`. El **servicio recibe `int userId` por parámetro**; nunca lo busca por su cuenta en el request.
3. **Socket.IO:** la identidad sale del handshake con la cookie JWT (`SocketIOClient.getAuthenticatedUserId()` / `getAuthenticatedSessionJti()`). Cada evento autenticado revalida la sesión (`AuthMiddleware.sesionVigente`). Al cerrar sesión, cambiar contraseña o revocar sesiones, cierra los sockets del usuario (`SocketIOServer.cerrarSocketsDeUsuario`).
4. **Mensajes anti-enumeración:** login, registro, recuperación y consultas públicas responden igual exista o no el usuario/correo/tag. No agregues endpoints que revelen si una cuenta existe.
5. **Contraseñas:** BCrypt siempre dentro de `Ejecutores.enCpu`; 6–128 caracteres y máximo **72 bytes UTF-8**.
6. **Tokens de un solo uso** (verificación de email, recuperación, invitaciones, confirmaciones): 32 bytes de `SecureRandom`, guardar **solo el SHA-256**, con expiración, consumo atómico (`UPDATE ... WHERE usado = false` y revisar filas afectadas), cooldown por cuenta y rate limit por IP. Mismo patrón que `password_reset_tokens` (V16).
7. **Nuevas tablas con credenciales** (2FA, tokens, códigos de respaldo, llaves de API de usuario): ocultarlas/protegerlas en `DbAdminService` (como `user_sessions` y `password_reset_tokens`) y borrarlas en la limpieza post-restore de `ConexionDB` para que un backup no reactive credenciales.
8. **2FA (cuando se implemente):** secreto TOTP cifrado en reposo, nunca devuelto tras la activación, intentos limitados por usuario, códigos de respaldo hasheados, y revocación de las demás sesiones al activarlo/desactivarlo.

## 4. Autorización y propiedad de recursos (S3)

1. **Toda ruta de administración va bajo `/api/admin/*`** (la cubre `AdminMiddleware` automáticamente). Si por compatibilidad no puede, agrega su patrón a `RUTAS_ADMIN` en `AdminMiddleware`. **PROHIBIDO** crear rutas admin fuera de ese esquema o patrones tipo `/secret-admin/*`.
2. **El rol admin se lee de la BD** (`AdminService.esAdmin(userId)` → `users.tipo_suscripcion`), **nunca** del claim `rol` del JWT (puede tener hasta 30 días de antigüedad). Los servicios administrativos críticos (dinero, KYC, evidencias) vuelven a verificar `esAdmin` dentro del servicio (defensa en profundidad), igual que `ChatbotService` y `EvidenciaPagoService`.
3. **Acciones admin por socket** también verifican el rol en la BD; ocultar un botón en el frontend **no es seguridad**.
4. **No existe ninguna ruta para promover admins** y no se debe crear. Se promueve desde `/admin-db`.
5. **Anti-IDOR:** toda consulta sobre un recurso de un usuario filtra por `user_id = ?` con el id de la sesión (tickets, evidencias, mensajes privados, retiros, KYC...). Si el recurso es de otro usuario responde **404** (no confirmar que existe).
6. **IDs de terceros** (`targetUserId`, `clanId`, `amigoId`) sí pueden venir en el body, pero se valida que existan, que no sean el propio usuario cuando no tenga sentido, y la relación/permiso correspondiente (amistad, membresía, bloqueo).
7. Toda acción admin que cambie dinero o estado de un usuario deja rastro: `admin_id` en la tabla o en la `referencia` del ledger, y un `logger.warn/info` sin datos personales.

## 5. HTTP, CORS, CSRF y cabeceras

1. **Un `GET` nunca modifica estado.** Toda mutación usa `POST/PUT/PATCH/DELETE` para quedar cubierta por la defensa CSRF de `Main` (Origin/`Sec-Fetch-Site`). `GET /admin-fix-status/*` es legacy: **no replicar**.
2. **CORS:** solo lista blanca (`AppConfig.esOrigenPermitido`, variable `CORS_ALLOWED_ORIGINS`). **PROHIBIDO** `*`, `anyHost()` o reflejar cualquier origen con credenciales.
3. **Webhooks servidor-a-servidor** (Wompi hoy; PayPal, Nequi API, cripto mañana) son la única excepción a CSRF y rate limit, y deben añadirse **explícitamente** a la guardia de `Main` y a `RateLimitMiddleware`. A cambio, verifican firma (sección 10.9).
4. **CSP y cabeceras globales** viven en `Main.construirPoliticaCsp`. Un dominio externo nuevo (script, frame, connect, imagen) se agrega ahí de forma explícita y mínima. **PROHIBIDO** `'unsafe-eval'`, scripts inline o comodines amplios.
5. Javalin rechaza cuerpos > ~1 MB: imágenes en base64 respetan los topes de `AppConfig` (ej. `PAGOS_MAX_IMAGEN_CHARS`) y el frontend las reduce antes de enviar.
6. Respuestas JSON con `ctx.json(...)`. **No usar `ServicioException.respuestaConError`** (HTTP 200 con `{error}`) en endpoints nuevos; es un contrato heredado.
7. Endpoints nuevos que mueven dinero → agregarlos a `RUTAS_FINANCIERAS`; endpoints nuevos de autenticación/verificación → `RUTAS_AUTH`; endpoints caros (IA, proveedores externos) → límite propio por usuario y por IP (patrón `RutasChatbot` / `ChatbotService`).

## 6. Validación y sanitización de entradas

1. **Todo input se valida en el servidor**, aunque el frontend ya lo valide: presencia, tipo, longitud, rango y lista blanca de valores (`categoria`, `estado`, `tipo`, `tono`... siempre con `Set.of(...)` cerrado).
2. Montos: **solo** `ValidadorMonto` (número, > 0, pesos enteros, rango por tipo, `BigDecimal` escala 0). Errores → 400 por HTTP o `error_negociacion`/`error_socket` solo al emisor por socket.
3. Texto libre: reglas de `ChatSanitizer` (sin etiquetas HTML ni caracteres de control, con tope de longitud). Se guarda **texto plano**; el escape lo hace React al pintar.
4. Imágenes: solo data URI `png/jpeg/gif/webp` (**nunca SVG**) o `/api/media/{id}`; tope de tamaño configurable; respuestas de media con `X-Content-Type-Options: nosniff`.
5. Enlaces del usuario: solo `http(s)` o rutas internas; nunca `javascript:`/`data:` en `href`.
6. Usernames: 3–30 caracteres, sin `< > " ' & \`` ni caracteres de control. Identidades (username, email, tag, teléfono) se normalizan igual que en V17 antes de compararlas.

## 7. Exposición de datos y privacidad (S6)

1. **Nunca** salen al cliente: `password`/hash, JTI, tokens (push, reset, OAuth, player token), secretos 2FA, `external_id` internos innecesarios, ni datos de otros usuarios fuera de su vista permitida.
2. Al **rival / otros jugadores / broadcasts**: solo `UsuarioVista.CAMPOS_RIVAL` (o una lista blanca equivalente). Nunca `email`, `telefono`, `player_tag`, `saldo` ni estado interno.
3. **Payloads de eventos del bus, correos al admin y logs** identifican por `userId`/`username`, **nunca** por email o teléfono.
4. Archivos sensibles (documentos KYC, selfies, comprobantes privados) **no** se sirven por rutas públicas ni por el canal «pagos»: solo el dueño y admins, con control de acceso en el servicio.
5. Si una funcionalidad recolecta datos nuevos, comparte datos con terceros o cambia reglas de dinero → actualizar `frontend/public/legal/contenido-legal.json` y **subir la versión** ahí y en `DocumentosLegales`.

## 8. SQL y acceso a datos (S4)

1. **Solo `PreparedStatement` con `?`.** `SqlSafetyAcceptanceTest` hace fallar el build si aparece `createStatement`. Identificadores dinámicos (columna de orden, tabla) solo desde un `switch`/lista blanca cerrada o validados contra `information_schema` + regex + comillas dobles (patrón `DbAdminService`).
2. **Columnas explícitas** en todo SQL nuevo; `SELECT *` prohibido en código nuevo.
3. ⚠️ **`GenericDAO` se traga las excepciones** (devuelve lista vacía, `null`, `0` o `-1`). Por eso:
   - En escrituras que importan, **revisa el valor devuelto** (`0` filas = no se hizo) y lanza `ServicioException`.
   - Operaciones de varios pasos o con dinero usan `getConnection()` en `try-with-resources`, `setAutoCommit(false)`, `commit()` y `rollback()` en el `catch`.
   - Nunca pases `Double` como parámetro para dinero (`GenericDAO` lo acepta, pero rompe D1).
4. **Transiciones de estado condicionales:** `UPDATE ... SET estado = 'nuevo' WHERE id = ? AND estado = 'esperado'` y verificar filas afectadas. Así se evita procesar dos veces una disputa, retiro o ticket.
5. Leer-y-luego-escribir en la misma transacción usa `SELECT ... FOR UPDATE` sobre la fila.
6. Restricciones `UNIQUE` como llave de idempotencia (`sala_id`, `external_id`, `referencia`, `(user_id, mision_id, periodo)`).
7. Plazos y vencimientos se comparan con el reloj de la BD (`CURRENT_TIMESTAMP` / `clock_timestamp()`); cortes de negocio en hora de Colombia (`America/Bogota`).

## 9. Migraciones Flyway (A5)

1. Toda tabla, columna, índice, constraint o dato semilla nuevo es un **archivo nuevo** en `src/main/resources/db/migration/`. **Revisa el número más alto existente** (hoy `V25`, el siguiente es `V26`) y, antes de commitear, verifica de nuevo que otro agente no haya usado ese número.
2. **Nunca editar, renumerar ni renombrar** un `V*` ya aplicado (checksum → el servidor no arranca). Para corregir, otra migración.
3. Dinero en `NUMERIC(18, 2)` con `CHECK (... >= 0)` cuando aplique (patrón V19). Nada de `REAL`/`DOUBLE PRECISION` para dinero.
4. Claves foráneas con `ON DELETE` explícito, índices en FKs y filtros frecuentes, `UNIQUE` donde haya idempotencia.
5. Si una migración encuentra datos duplicados o inválidos: **fallar con un error explícito** (patrón V12/V17). Nunca borrar, fusionar ni reasignar datos de usuarios automáticamente.
6. Tablas inmutables tipo auditoría (como `wallet_ledger`): trigger que bloquee `UPDATE/DELETE/TRUNCATE` (patrón V10/V11).
7. Tablas nuevas financieras o con credenciales → registrarlas en `DbAdminService` (`TABLAS_NO_IMPORTABLES`, solo lectura u ocultas) y, si son credenciales, en la limpieza post-restore de `ConexionDB`.
8. Tablas nuevas de negocio → añadirlas (en orden hijo → padre) a `tablasBorrar` y su secuencia a `tablasSecuenciaId` en `ConexionDB.reiniciarDatosZonaPruebas()`, recreando semillas singleton o triggers si aplica. Columnas nuevas de identidad/configuración en `users` (sin saldo ni estadísticas) → copiarlas en `ConexionDB.sincronizarUsuarioConPruebas()`.
9. Documentar la tabla en `ARCHITECTURE.md` (sección 3) y la migración en la sección 14.

## 10. Dinero y ledger (D1–D5)

1. **Todo movimiento de saldo pasa por `WalletService`.** Una operación nueva = un método nuevo en `WalletService` con: transacción atómica, bloqueo de la fila (`FOR UPDATE`), verificación `WHERE saldo >= ?` en la misma transacción, `INSERT` en `wallet_ledger` en esa misma transacción, y evento al bus **después** del commit.
2. **`BigDecimal` siempre** (`rs.getBigDecimal`, `ps.setBigDecimal`). **PROHIBIDO** `double`/`float` para dinero, incluso temporalmente: ya no se permiten puentes `// TODO(D1)`. Construye con `new BigDecimal(String)` o `BigDecimal.valueOf(long)`, **nunca** `new BigDecimal(double)`. Compara con `compareTo`, no con `equals`.
3. **Redondeo explícito** con `RoundingMode`: hacia abajo (`FLOOR`/`DOWN`) para comisiones y premios pagados, hacia arriba (`CEILING`) para tarifas cobradas al usuario. Montos de usuario en pesos enteros (escala 0, como `ValidadorMonto`).
4. **Idempotencia obligatoria:** cada operación de dinero tiene una referencia única (`sala_id`, `external_id`, `pagoId`, id de transacción, `(user, mision, periodo)`). Un reintento devuelve `WalletResult.yaAplicado` sin volver a mover dinero.
5. **Los montos salen de la BD, no del cliente**, cuando ya existen (ej. `EvidenciaPagoService` copia monto y método de `transactions`).
6. **El frontend nunca calcula dinero** (comisión, premio, devolución): lo consulta al backend (`/api/comisiones/simular`).
7. **No se crea dinero de la nada:** toda recompensa en dinero de Fase 1 (misiones, logros, devolución, referidos, VIP, ligas) sale del pozo de su categoría (`admin_wallet` / pozos tipo V19) **en la misma transacción** y nunca lo deja negativo.
8. **Lo que debe ser atómico con el dinero** (saldo, comisiones, `gen_*`, `victorias_*`, pozos) va dentro de la transacción de `WalletService`, **no** en un listener. Si un listener otorga una recompensa, llama a `WalletService` con referencia única (los eventos no se persisten ni se reintentan).
9. **Pasarelas con webhook** (patrón Wompi, S7): verificar la firma con comparación en tiempo constante, verificar monto **y** moneda contra el pendiente guardado, `external_id`/`referencia` únicos, procesamiento idempotente, nunca acreditar por la redirección del navegador, y secretos en `AppConfig` (agregar a `validarConfiguracionProduccion` si son obligatorios).
10. **Anti-fraude:** prohibido transferirse a sí mismo o autorreferirse; límites y cooldowns configurables en `AppConfig`; umbrales que exijan KYC para montos altos.
11. Marca con `// REVIEW-MONEY` todo método o bloque nuevo que calcule o mueva premios, comisiones, devoluciones, préstamos/regalos, límites o tarifas.
12. Todo cambio de dinero lleva un caso en `RenderAcceptanceCheck` que verifique saldo, ledger, idempotencia y, si aplica, concurrencia (patrón de los casos B, C, E y F).

## 11. Tiempo real: Socket.IO (A1) y estado persistido (A2)

1. El servidor es `netty-socketio` detrás de `SocketIOServer`. Comunicación dirigida con **rooms**: `client.join(room)`, `io.to(room).emit(...)`. Las rooms nuevas usan el prefijo `entidad:id` (ej. `clan:12`, `privado:3-8`, `torneo:5`).
2. **Antes de unir a alguien a una room se verifica en la BD** que pertenece (partida, clan, amistad, torneo). Nunca por lo que diga el cliente.
3. `io.emit(...)` (broadcast global) solo para datos públicos. Datos de un usuario → sus sockets (`NotificadorUsuarios.emitirA`) o su room.
4. Cada evento nuevo de socket: identidad con `usuarioAutenticado`, límite por usuario con `socketRateLimiter.intentar("evento|" + userId, ...)`, validación del payload y errores con `error_socket {evento, codigo, mensaje}` solo al emisor.
5. **No hacer crecer `SocketHandler`** (> 100 KB): la funcionalidad nueva va en su propio handler/servicio (protocolo en `servidor/`, lógica en `servicio/`).
6. **Estado de juego en BD (A2):** colas, salas, brackets, partidas 2v2, guerras y torneos se persisten (`match_queue`, `active_matches` o tablas nuevas). Los mapas en memoria solo pueden ser **caché reconstruible** al reiniciar (patrón `recuperarEstadoDesdeBD()`) o contadores efímeros (rate limit). Los temporizadores se reprograman con el tiempo restante real tras un reinicio.
7. **Concurrencia:** candado sobre el objeto de la entidad (`synchronized (match)`) **más** restricciones/condiciones en la BD; la BD es la fuente de verdad.
8. **Resultados de Clash Royale:** validación estricta como `ClashApiServicio.findMatchingBattle()` (batallas posteriores al inicio, tags normalizados, tipo admitido). Si hay ambigüedad → disputa, nunca liquidar una elección dudosa.
9. **Aislamiento por zona en memoria (`normal` vs `prueba`):** todo mapa, caché, cola o temporizador en memoria separa el estado por zona (`ConexionDB.esZonaPrueba()` / `EstadoZonaSocket`, patrón `SocketHandler` y `TemporizadorSalaService`), se limpia en `SocketHandler.limpiarEstadoZonaPrueba()` y retira al usuario de la zona anterior en `SocketHandler.notificarCambioZonaUsuario(...)`.

## 12. Bus de eventos (A4)

1. Funcionalidad nueva que reacciona a algo (notificaciones, misiones, logros, ligas, VIP, referidos) = **listener del `EventBus`**, no llamada directa en el flujo crítico.
2. Evento nuevo: constante en `Eventos` + `record` con sus datos; nombre `entidad.accion` en pasado (`user.verified`, `friend.request_accepted`). Se emite **después** de confirmar la transacción.
3. Listener nuevo: clase en `servicio/` con `registrar(EventBus)` → `bus.on(Eventos.X, "id-unico", this::metodo)` y una línea en `Main` (el orden de registro es el orden de ejecución).
4. Un listener **no bloquea** con HTTP externo (usa `Ejecutores.ejecutarIO`), no lanza excepciones hacia el emisor, debe tolerar ejecutarse dos veces y no revierte nada.
5. Los datos del evento nunca llevan email, teléfono, secretos ni data URIs pesados innecesarios.

## 13. Arquitectura y organización del código (A3, A7, A8)

1. **Capas:** `servidor/Rutas*.java` solo parsean el request (`Peticion`), toman el `userId` de la sesión, llaman al servicio y responden. **PROHIBIDO** lógica de negocio o SQL en rutas o handlers de socket. Los servicios (`servicio/`) no conocen Javalin (`Context`).
2. **Dependencias por constructor**, instanciadas y conectadas en `Main`. **PROHIBIDO** estado mutable `static` nuevo y singletons nuevos (lo estático existente en `AuthMiddleware` o `RutasDbAdmin` es legacy: no replicar).
3. **Ubicación y nombres:** `servidor/` (`RutasX`, middlewares), `servicio/` (`XService`, `XListener`, `XApiCliente`), `dao/`, `eventos/`, `config/`. Evitar clases de más de ~1000 líneas: dividir por responsabilidad.
4. **Clientes de APIs externas:** timeouts (conexión ~10 s, petición ~15 s), reintentos solo ante 5xx con espera creciente, claves desde `AppConfig`, y **degradación elegante** si falta la configuración (la función queda desactivada sin romper la app, patrón Discord/Chatbot).
5. **Hilos:** solo `Ejecutores`. Red sin usuario esperando → `ejecutarIO`; push → `ejecutarPush`; CPU (BCrypt, hashing pesado) → `enCpu`; temporizadores → `timers()` **sin** HTTP dentro.
6. **Logs:** SLF4J con parámetros `{}`. **PROHIBIDO** `System.out`, `printStackTrace` y registrar contraseñas, JWT, cookies, tokens, API keys, `DATABASE_URL`, data URIs o email/teléfono (usar `userId`). `warn` para accesos denegados/abuso, `error` para fallos.
7. **Errores:** el cliente recibe un mensaje genérico; el detalle va al log con `ServicioException.interno(mensaje, causa)`.

## 14. Configuración y secretos (S9, A7)

1. Toda configuración nueva va en `AppConfig`: variable de entorno, default seguro, rango; valor inválido → default + `logger.error`. Los **secretos no tienen default**.
2. Si es obligatoria en producción → agregarla a `AppConfig.validarConfiguracionProduccion()`.
3. Documentar cada variable nueva en `GUIA_PRUEBAS_Y_VARIABLES.md` (Parte 1) y en la sección correspondiente de `ARCHITECTURE.md`.
4. **Nunca** commitear `.env`, claves, `DATABASE_URL` reales ni credenciales de prueba reales.
5. `JWT_SECRET` también deriva claves de `CifradoTokenJugador` y del `state` OAuth de Discord: rotarlo invalida todo eso. Un uso nuevo debe **derivar** su propia clave (HMAC con etiqueta), no reutilizar el secreto tal cual.
6. Frontend: la URL del backend sale de `VITE_API_BASE_URL`; no agregar URLs nuevas escritas en el código.

## 15. Frontend (React + Vite + Zustand + Capacitor)

1. Estructura: `pages/`, `components/`, `store/`, `hooks/`, `services/`, `data/`. Rutas privadas nuevas dentro de `<Route path="/" element={<Lobby />}>` y con `lazy()`; solo se agregan a `RUTAS_PUBLICAS` si realmente son públicas.
2. HTTP con `llamarApi` (envía la cookie) y manejar `401` (cerrar sesión), `403`, `429` (mostrar el `retryAfter`) y `status 0` (sin conexión).
3. **PROHIBIDO** `dangerouslySetInnerHTML`, `innerHTML`, `eval` y `new Function`. Todo texto del usuario se pinta como texto con React.
4. **PROHIBIDO** guardar datos del usuario o tokens en `localStorage`/`sessionStorage` (solo `appTheme`). El token push vive en memoria.
5. **Nunca** enviar `userId` desde el cliente ni calcular dinero en el frontend. La validación del frontend es solo UX.
6. Ocultar opciones de admin en la UI es solo UX; la seguridad la hace el backend.
7. **Vocabulario (F5):** "monto", "torneo", "reto", "desafío". **Nunca** "apuesta"/"apostar" (frontend, backend, FAQ, prompts del chatbot y textos legales).
8. Diseño: responsivo (`@media (max-width: ...)`), modo claro/oscuro con las variables CSS de `index.css` (`var(--...)`), sin colores sueltos.
9. Capacitor: no usar APIs que rompan en nativo; `android/` e `ios/` son generados: no editarlos a mano salvo configuración necesaria.
10. Invariantes críticas del frontend (sesión, persistencia, FAQ, Modo Vista Previa) se protegen con pruebas en `frontend/scripts/acceptance.test.js`.
11. **Modo Vista Previa (`modoVistaPrevia` + `vistaPreviaDemo.js`):** toda pantalla o sección nueva de usuario (salvo `/admin`, que exige sesión real de admin) debe funcionar también en el Modo Vista Previa (`!user && modoVistaPrevia`), mostrando datos de ejemplo desde `frontend/src/data/vistaPreviaDemo.js` sin abrir Socket.IO ni llamar endpoints privados, y llamando a `solicitarAutenticacionVistaPrevia('...')` cuando el visitante intente interactuar.
12. **Revisión visual obligatoria (PC y celular) en cada cambio visual:** cada vez que se modifique cualquier aspecto visual (`*.jsx`, `*.css`, layout, textos visibles, modales o estilos), **es obligatorio revisar visualmente cómo se ve antes de terminar**:
    - Levantar el frontend (`npx vite preview` tras `npm run build` o `npm run dev`), entrar a `/auth`, oprimir **`👀 Ver vista previa de la página`** y recorrer las vistas tocadas.
    - Comprobar tanto **vista de PC** (`~1280×800`) como **vista de celular** (`~390×844`, menú `☰`, scroll, botones y modales) verificando que no haya desbordes, elementos encimados, textos cortados ni pantallas en blanco.
    - Usar el subagente `browser` (o capturas con Chrome headless `--headless --screenshot --window-size=...` si el puerto de depuración remota de Chrome no está activo), cerrar el servidor temporal al terminar y dejar constancia en **Verificación** del `CHANGELOG.md`.

## 16. Pruebas y verificación

1. **Backend:** `mvnw.cmd clean verify` debe pasar (es lo que corre Docker). **Frontend:** en `frontend/`, `npm run build`, `npm run test:acceptance` y `npm run lint`.
2. Lógica pura nueva (validadores, cálculos, parsers) → prueba JUnit 5 en `src/test/java` en el mismo paquete.
3. **Simulaciones de JUnit obligatorias (`RenderAcceptanceCheck` y `RenderDeploymentDatabaseCheck`):** en Render, `scripts/run-deploy-tests.sh` ejecuta ambas suites contra PostgreSQL **antes de iniciar el servidor** y aborta el despliegue si cualquiera falla (mientras que localmente `mvnw.cmd clean verify` omite `RenderAcceptanceCheck` si no existe `TEST_DATABASE_URL`). Por eso:
   - Todo cambio en BD, dinero, rutas HTTP, sockets, permisos o estadísticas debe revisar y actualizar las simulaciones de `RenderAcceptanceCheck` (esquema aislado `tf_qa_*`, servidor HTTP/socket embebido o clientes falsos, sin llamadas externas).
   - **Verifica siempre cada dato sintético de prueba contra las migraciones reales** en `src/main/resources/db/migration/V*.sql` (`VARCHAR(n)`, `NUMERIC`, `CHECK`, `NOT NULL`, `UNIQUE`, FKs) para que la simulación no falle al correr en PostgreSQL durante el despliegue.
   - Mantén la salida de las pruebas limpia: no introduzcas `ERROR` espurios en los logs durante casos exitosos o negativos esperados.
4. **Si se agrega una prueba manual y se puede automatizar en JUnit, se automatiza SIEMPRE:** cada vez que documentes o propongas una prueba manual (por ejemplo en `GUIA_PRUEBAS_Y_VARIABLES.md`) cuya lógica, endpoint HTTP, autorización, validación o persistencia pueda simularse con JUnit (prueba unitaria en `src/test/java` o simulación en `RenderAcceptanceCheck`), **es obligatorio implementarla también en JUnit dentro de la misma tarea**. Deja exclusivamente como verificación manual solo lo que dependa de revisión visual humana en navegador o de proveedores externos reales.
5. **Nunca** apuntar `TEST_DATABASE_URL` a producción ni llamar a proveedores reales (Wompi, Clash, Brevo, FCM, Discord, IA) desde pruebas.
6. Al agregar algo probable, actualiza la matriz de cobertura y los casos de `GUIA_PRUEBAS_Y_VARIABLES.md`, indicando o respaldando su contraparte automatizada en JUnit cuando aplique.
7. Si no pudiste ejecutar una verificación (Maven colgado, falta BD), dilo explícitamente en la línea **Verificación** del `CHANGELOG.md`. Nunca afirmes que algo pasó si no lo ejecutaste.

## 17. Contenido de ayuda (FAQ, chatbot, tutorial)

Al agregar o cambiar una funcionalidad visible para el usuario:
- **FAQ:** agregar/editar preguntas en `frontend/src/data/faq.js` (`id` único y estable, `categoria` existente, texto plano, sin "apuesta"); mantener pasando la prueba P4.
- **Chatbot IA:** agregar el tema a `ChatbotService.TEMAS` y una pregunta base a `PREGUNTAS_SEMILLA` para que la IA le pregunte al admin. No escribir respuestas con cifras de dinero fijas en el código.
- **Tutorial interactivo:** cuando exista, agregar el paso correspondiente.
- **Zona de Pruebas / Sandbox:** si la funcionalidad tiene reglas, límites o disponibilidad particular en el espacio de pruebas (admin y usuarios `prueba`), actualizar también `zona-pruebas-suscripcion` en `frontend/src/data/faq.js` y las entradas correspondientes en `ChatbotService` (`TEMAS` / `PREGUNTAS_SEMILLA`).

## 18. Zona de Pruebas / Sandbox (espacio de pruebas para admin y usuarios `prueba`)

La plataforma cuenta con un sandbox aislado (**Copia Temporal / Zona de Pruebas**, [`ARCHITECTURE.md`](../../ARCHITECTURE.md) sección 24, migración `V23`) respaldado por `TEST_DATABASE_URL` (o el esquema local `tf_zona_pruebas`). Solo acceden cuentas con `tipo_suscripcion = 'prueba'` (asignadas exclusivamente desde `/admin`) y administradores que activan `zona_admin = 'prueba'`.

**Al agregar o modificar cualquier funcionalidad**, es obligatorio actualizar también lo necesario para que funcione y permanezca aislada en el sandbox:

1. **BD y control de acceso (`ConexionDB`, `AdminService`, `DbAdminService`):**
   - Los datos de negocio usan `GenericDAO` / `ConexionDB.getConnection()` (enruta por `ThreadLocal`: `ZONA_NORMAL` vs `ZONA_PRUEBA`); autenticación, sesiones (`user_sessions`), recuperación (`password_reset_tokens`) e identidad/rol/zona usan `getConnectionPrincipal()`.
   - Nunca expongas la asignación de `tipo_suscripcion = 'prueba'` ni la edición de `zona_admin` fuera de `AdminService` (`/api/admin/*`); `DbAdminService` (`/admin-db`) debe seguir bloqueándolo.
   - Agrega toda tabla de negocio nueva (en orden hijo → padre) a `tablasBorrar` y su secuencia a `tablasSecuenciaId` en `ConexionDB.reiniciarDatosZonaPruebas()`, restaurando filas semilla singleton (patrón `leaderboard_pools`, `raffle_pool`, `chatbot_config`) y triggers (patrón `wallet_ledger`) cuando aplique.
   - Si añades columnas de identidad, verificación o configuración en `users` (nunca saldo ni estadísticas de juego), cópialas en `ConexionDB.sincronizarUsuarioConPruebas()`.
   - Si la funcionalidad aporta contadores o métricas principales para el admin, considéralos en `ConexionDB.resumenZonaPruebas()` y la pestaña `test-zone` de `Admin.jsx`.
2. **Hilos, tiempo real y tareas programadas (`Ejecutores`, `SocketHandler`, `Main`):**
   - Todo trabajo asíncrono o temporizador debe ejecutarse con `Ejecutores` para heredar `ConexionDB.zonaActual()`. Nunca uses `new Thread` o `Executors.*` sueltos.
   - Separa cualquier estado en memoria, cola, caché o temporizador entre `normal` y `prueba` (`ConexionDB.esZonaPrueba()`, patrón `SocketHandler.EstadoZonaSocket` y `TemporizadorSalaService`), límpialo en `SocketHandler.limpiarEstadoZonaPrueba()` y retira al usuario de la zona previa en `SocketHandler.notificarCambioZonaUsuario(...)`.
   - Toda recuperación al arranque y tarea periódica en `Main` (`scheduler.scheduleAtFixedRate(...)`) debe ejecutarse también para `ZONA_PRUEBA` dentro de `if (conexion.tieneZonaPruebas()) ConexionDB.ejecutarEnZona(ConexionDB.ZONA_PRUEBA, ...)`.
   - Ninguna acción ejecutada desde `ZONA_PRUEBA` (dinero, webhooks, correos, push, voz o APIs externas) puede acreditar saldo real ni alterar datos o salas de usuarios en `ZONA_NORMAL`.
3. **Frontend (`useAppStore.js`, `Admin.jsx`, páginas nuevas):**
   - Todo estado global en `useAppStore.js` o vista/pestaña de `Admin.jsx` y páginas de usuario que dependa de datos de la zona debe limpiarse o recargarse ante `zona_cambiada`, `zona_pruebas_reiniciada` (`recargarZonaActiva()`) y cambios de `zonaActivaAdmin`.
4. **Pruebas y documentación del sandbox:**
   - Verifica en `RenderAcceptanceCheck` (o amplía `zonaDePruebasAislaOperacionesRestringeSuscripcionAlAdminYPermiteBorradoManual`) que lo nuevo en `ZONA_PRUEBA` no contamine `ZONA_NORMAL` y que `admin.reiniciarZonaPruebas(...)` limpie sus tablas sin errores de FK ni triggers.
   - Si cambia el comportamiento o flujo del sandbox, actualiza `ARCHITECTURE.md` (sección 24), `GUIA_PRUEBAS_Y_VARIABLES.md`, la ayuda (sección 17) y la prueba `P11` en `frontend/scripts/acceptance.test.js`.

---

## 19. Patrones obligatorios por tipo de funcionalidad (Fase 1)

Ya no hay bloqueos de Fase 0: todo se puede implementar, pero **cada grupo exige su patrón**.

| Funcionalidad | Patrón obligatorio |
|---|---|
| Verificación de email, puntos VIP, referidos, misiones, logros, ligas | Tokens hasheados de un solo uso (3.6); listeners de `EventBus` (`user.verified`, `match.finished`...); recompensas idempotentes con clave `UNIQUE`; dinero desde el pozo de su categoría (10.7) |
| 2FA, KYC | Secretos cifrados en reposo (3.8); archivos privados (7.4); revisión admin bajo `/api/admin/*` con `esAdmin` en el servicio; actualizar la política de privacidad (7.5) |
| Amigos, chat privado, clanes, chat de clanes, prestigio | Rooms con membresía verificada en BD (11.2); `ChatSanitizer`; rate limit por usuario; mensajes persistidos; anti-IDOR (4.5); bloqueos/solicitudes con transiciones condicionales (8.4) |
| Límites de dinero, devolución, regalar/prestar, coach, unificación de moneda | `WalletService` + `// REVIEW-MONEY` + idempotencia + anti-fraude (10.10) + caso en `RenderAcceptanceCheck`; revisión con un modelo de razonamiento alto (O4.6T / A4H) |
| Pasarelas con webhook (PayPal, Nequi API, cripto) | Patrón Wompi (10.9); excepción explícita en la guardia de `Main` y en `RateLimitMiddleware` (5.3); secretos en `validarConfiguracionProduccion` |
| Pasarelas manuales (Llaves, Nu, Daviplata) | Flujo Nequi manual + evidencias: aprobación admin, montos desde la BD, transición condicional del estado de la transacción |
| Matchmaking justo, 2v2, torneos (todos contra todos, eliminatoria, mundial), guerra y liga de clanes | Estado persistido + recuperación al reiniciar (11.6); rooms (11.1); inicio atómico e idempotente al estilo `iniciarPartidaTorneo`; validación estricta de resultados y disputa ante ambigüedad (11.8) |
| Notificaciones y su configuración | Listener del bus + `NotificadorUsuarios`; respetar la preferencia del usuario antes de enviar; push en `ejecutarPush` |

### 19.1 Guía obligatoria para implementar Ligas (Prioridad 12.2) sobre Matchmaking Justo

El matchmaking justo (`MatchmakingPuntaje` y `MatchmakingService`) ya reserva la integración de ligas sin romper el emparejamiento actual. Al construir el sistema de **Ligas (12.2)** se deben seguir estas reglas:

1. **Cantidad recomendada de ligas: 8 ligas principales (`liga_nivel` de `1` a `8`)**:
   - `1` = `bronce` (Bronce), `2` = `plata` (Plata), `3` = `oro` (Oro), `4` = `platino` (Platino), `5` = `diamante` (Diamante), `6` = `maestro` (Maestro), `7` = `campeon` (Campeón), `8` = `leyenda` (Leyenda).
   - **Por qué exactamente 8**: en `MatchmakingPuntaje.calcularLigaSubscore`, `8` niveles sobre escala `[0, 1000]` producen saltos de `~142.8` puntos por liga, alineados con los 5 rangos de diferencia entre oleadas (`±15`, `60`, `130`, `220`, `330`). Menos de 5 ligas mezclan niveles muy distintos; más de 10 fragmentan demasiado los rangos cuando la base de usuarios simultáneos aún crece. Si se agregan subdivisiones visuales (I/II/III), mantén las 8 ligas base o pasa el total real en `totalNiveles`.
2. **¿Se cambia la fórmula del puntaje al implementar Ligas?**
   - **Por defecto NO hace falta cambiarla**: `MatchmakingPuntaje` ya define los 3 pesos base (`PESO_WIN_RATE = 0.35`, `PESO_LIGA = 0.25`, `PESO_CLASH = 0.40`). Hoy, cuando `liga_nivel` es `NULL`, la fórmula renormaliza automáticamente `0.35` y `0.40` sobre `0.75` para que no afecte a nadie.
   - **Cómo conectarlo**: cuando un jugador entre o cambie de liga (o al corte de temporada), llama a `MatchmakingService.actualizarLigaUsuario(userId, ligaClave, ligaNivel, MatchmakingPuntaje.LIGAS_RECOMENDADAS_TOTAL)` desde un listener de `EventBus`. El 25% de Liga se activará de inmediato en `matchmaking_profiles` sin migrar puntajes ni afectar cuentas antiguas sin liga aún.
   - **Cuándo ajustar pesos**: solo si los datos reales en producción muestran que la liga interna predice mejor el rendimiento en retos 1v1 que Clash Royale, ajusta las constantes en `MatchmakingPuntaje.java` (por ejemplo `PESO_LIGA = 0.30`, `PESO_WIN_RATE = 0.30`, `PESO_CLASH = 0.40`) manteniendo SIEMPRE: (a) renormalización dinámica ante variables ausentes, (b) `puntaje_interno` 100% oculto para los usuarios (solo diferencias relativas por rangos), y (c) no asignar Liga 1 forzada durante reinicios de temporada antes de jugar partidas de clasificación (dejar `liga_nivel = NULL` o conservar liga atenuada hasta calibrar para no hundir artificialmente el puntaje).

---

## 20. Después de cada tarea

1. Compilar y verificar que no haya errores (sección 16).
2. Verificar que el cambio funcione de punta a punta (incluyendo en la Zona de Pruebas / Sandbox cuando aplique): el frontend llama al endpoint correcto, el evento tiene listener, la migración corre.
3. **Si hubo cambios visuales o de interfaz**, realizar la **revisión visual obligatoria en PC y celular** (sección 15.12) entrando por el Modo Vista Previa (`👀 Ver vista previa de la página` en `/auth`) y actualizar `vistaPreviaDemo.js` si se agregaron vistas o estados nuevos.
4. **No resumir** lo hecho en el chat. En cambio, agregar la entrada al inicio de `CHANGELOG.md` (debajo de la línea `---`) con el formato:
   ```
   ## AAAA-MM-DD — descripción corta (Modelo usado)
   - Cambio 1
   - Cambio 2
   - **Verificación:** qué se ejecutó y qué queda pendiente
   Archivos: `archivo1.java`, `archivo2.jsx`
   ```
5. Editar `ARCHITECTURE.md` solo si el cambio es significativo (tabla, endpoint, evento, variable de entorno, flujo o estructura de carpetas nuevos), en su sección correspondiente.
6. Actualizar `GUIA_PRUEBAS_Y_VARIABLES.md` si hay variables nuevas o casos de prueba manual nuevos; **si la prueba manual nueva se puede simular o verificar por JUnit (`*Test.java` o `RenderAcceptanceCheck`), automatizarla ahí mismo sin dejarla solo manual**.
7. Si completaste un ítem del roadmap, marcarlo como hecho en `analisis_y_roadmap.md`.
8. Agregar el contenido de ayuda correspondiente (sección 17).
9. Adaptar e integrar el cambio en la **Zona de Pruebas / Sandbox** (sección 18: `ConexionDB`, estado en memoria, tareas en `Main`, UI/store y verificación de aislamiento/reinicio).
10. Ejecutar el archivo `actualizar-txt.bat`.
11. Ejecutar `git diff --stat` y confirmar que solo se tocaron los archivos esperados (sin `.env`, secretos, `target/`, `contexto-txt/` ni builds).
12. Notificar al usuario las verificaciones manuales que debe hacer.
13. Seguir la regla de [auto-push](auto-push.md) (add, commit descriptivo en español con convencional commits, push a `main`) a no ser que se indique lo contrario.

## 21. Checklist rápido de seguridad (antes de terminar)

- [ ] El `userId` sale de la sesión (HTTP) o del socket autenticado, nunca del cliente.
- [ ] Rutas admin bajo `/api/admin/*` y rol verificado en la BD.
- [ ] Recursos de usuario filtrados por dueño (anti-IDOR).
- [ ] Todo input validado en el servidor; montos con `ValidadorMonto`.
- [ ] SQL con `PreparedStatement`, columnas explícitas, resultado de escrituras verificado.
- [ ] Dinero solo por `WalletService`, `BigDecimal`, ledger, idempotente y con `// REVIEW-MONEY`.
- [ ] Ningún dato sensible en respuestas, broadcasts, eventos ni logs.
- [ ] Mutaciones por `POST/PUT/PATCH/DELETE`; endpoints nuevos clasificados en el rate limit.
- [ ] Estado de juego persistido en BD; rooms con membresía verificada.
- [ ] Esquema por migración Flyway nueva con el número correcto.
- [ ] Configuración en `AppConfig` y documentada.
- [ ] Sin `innerHTML`, sin datos de usuario en `localStorage`, sin "apuesta".
- [ ] Si hubo cambios visuales: actualizado `vistaPreviaDemo.js` (si aplica) y **revisada visualmente la interfaz en versión PC y celular** usando el Modo Vista Previa (sección 15.11 y 15.12).
- [ ] Contenido de ayuda (sección 17) y **Zona de Pruebas / Sandbox** (sección 18) al día e integrados.
- [ ] Simulaciones de JUnit (`RenderAcceptanceCheck` y `*Test.java`) revisadas contra restricciones reales de `V*.sql`, toda prueba manual automatizable cubierta en JUnit, y `clean verify` / `build` ejecutados.
- [ ] `CHANGELOG.md` (y `ARCHITECTURE.md`/guía si aplica) actualizados.