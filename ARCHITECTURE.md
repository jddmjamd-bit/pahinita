# ARCHITECTURE.md — UltimateClash (TorneosFlash)

> Documento de referencia para que cualquier modelo o desarrollador entienda el sistema.
> Última actualización: 2026-10-19

---

## 1. Stack actual

| Capa | Tecnología | Notas |
|------|-----------|-------|
| **Backend** | Java 17, Javalin 5 | Servidor HTTP + middleware |
| **WebSocket** | `netty-socketio` 2.0.12 (`socketio/`) | Servidor Socket.IO v3/v4 con soporte nativo de rooms, namespaces y reconexión automática, enlazado en `/socket.io/` sobre el puerto de Javalin (A1 completado) |
| **BD** | PostgreSQL (Render) | Pool: HikariCP, max 20 conexiones. Enlace de acceso endurecido (sección 11) |
| **ORM** | Ninguno | SQL directo con `PreparedStatement` vía `GenericDAO` |
| **Migraciones BD** | Flyway 10 | Scripts versionados en `src/main/resources/db/migration/`; se aplican al arrancar (`ConexionDB.migrar()`). Ver sección 14 |
| **Auth** | JWT HS256 en cookie httpOnly `session` con JTI persistido y revocable (S1) | `AuthMiddleware` verifica firma y sesión activa en `user_sessions`, e inyecta `ctx.attribute("userId")`/`("rol")`. Rutas de admin protegidas por `AdminMiddleware` (S3, sección 18). S2 resuelto; ver secciones 16 y 18 |
| **Frontend** | Vanilla JS (`app.js`, 120 KB) | SPA monolítica sin framework. **Migrar a React + Vite** (pendiente, en progreso bajo `frontend/`) |
| **Mobile App** | Capacitor | Genera builds nativos para Android e iOS encapsulando React. El hook mantiene los permisos y el ciclo del token push |
| **Pagos** | Wompi (tarjeta), Nequi (manual) | Webhook Wompi verifica firma SHA-256, monto y moneda (S7) |
| **Notificaciones** | Firebase Cloud Messaging (FCM) | Push via `NotificacionPushServicio` y `@capacitor/push-notifications` en cliente |
| **Email** | Brevo (Sendinblue) | Vía `CorreoServicio` |
| **Deploy** | Render (Docker) | UTC timezone, free tier |

---

## 2. Estructura de carpetas

```
TorneosFlash/
├── public/                          # Frontend legacy (servido como estáticos)
│   ├── index.html                   # SPA principal
│   ├── app.js                       # Lógica completa del frontend (~2500 líneas)
│   ├── style.css                    # Estilos
│   ├── admin-db.html                # Panel admin (fuente única; el Dockerfile lo copia a /app/admin-db.html y RutasDbAdmin lo sirve)
│   └── firebase-messaging-sw.js     # Service worker FCM
├── frontend/                        # Nuevo Frontend (React + Vite + Capacitor)
│   ├── android/                     # Proyecto nativo Android (Capacitor)
│   ├── ios/                         # Proyecto nativo iOS (Capacitor)
│   ├── src/
│   │   ├── hooks/useCapacitor.js    # Permisos, registro/renovación FCM y asociación tras login
│   │   └── App.jsx                  # Raíz de React
│   └── vite.config.js               # Configuración del empaquetador
├── src/main/java/com/torneosflash/
│   ├── Main.java                    # Entry point, configura Javalin, registra rutas
│   ├── config/
│   │   └── AppConfig.java           # Lee variables de entorno
│   ├── dao/
│   │   ├── ConexionDB.java          # Singleton HikariCP + migrar() (Flyway, A5)
│   │   ├── GenericDAO.java          # CRUD genérico (select, insert, update, delete)
│   │   └── UsuarioDAO.java          # Queries específicas de usuarios
│   ├── eventos/
│   │   ├── EventBus.java            # Bus de eventos en memoria: `emit()` / `on()`, síncrono y aislado por listener. Ver sección 13
│   │   └── Eventos.java             # Catálogo: nombres de eventos (`match.finished`, `chatbot.duda_sin_respuesta`, `chatbot.duda_respondida`) y sus datos (`PartidaFinalizada`, `DudaSinRespuesta`, `DudaRespondida`)
│   ├── servicio/
│   │   ├── ClashApiServicio.java    # Integración con API de Clash Royale
│   │   ├── ChatSanitizer.java       # Validación/limpieza de mensajes de chat y usernames (anti-XSS)
│   │   ├── CorreoServicio.java      # Envío de emails vía Brevo
│   │   ├── NotificacionPushServicio.java # Push FCM
│   │   ├── RateLimiter.java         # Contador de solicitudes por clave, ventana deslizante de 1 min (lógica pura, sin HTTP). Ver sección 10
│   │   ├── WalletService.java       # Todo movimiento de dinero (D2): transacciones atómicas, BigDecimal, emite eventos al bus (A4). Ver sección 5
│   │   ├── ComisionService.java     # Política de comisiones: % escalonado + reparto por categoría. Ver sección 9
│   │   ├── UsuarioVista.java        # Lista blanca de columnas de `users` que pueden salir al cliente (sesión vs. rival). Nunca `SELECT *` sobre users hacia el frontend
│   │   ├── ValidadorMonto.java      # Valida montos del cliente (número, > 0, entero, rango por tipo). Ver sección 8
│   │   ├── TicketsPartidaListener.java   # Listener de `match.finished`: tickets de sorteo + `tickets_ganados` a cada jugador (A4)
│   │   ├── ResultadoPartidaListener.java # Listener de `match.finished`: saldo del ganador + `resultado_api` + push (A4)
│   │   ├── ServicioException.java   # Error de negocio con código HTTP + mensaje; lo convierte `ManejadorErrores` en `{error}` (A3)
│   │   ├── NotificadorUsuarios.java # Aviso a un usuario por socket (si está conectado) + push FCM (A3)
│   │   ├── AuthService.java         # Registro, login (BCrypt en el pool CPU), sesión, validación de formato, tag de Clash, token push (A3)
│   │   ├── JwtServicio.java         # Emite y verifica JWT de sesión HS256 con `sub`, `rol`, expiración y JTI revocable (S1)
│   │   ├── FinanzasService.java     # Depósito, recarga, retiro y Wompi (init + webhook) (A3)
│   │   ├── AdminService.java        # Transacciones pendientes, disputas, estadísticas, utilidades admin (A3)
│   │   ├── SorteoService.java       # Tickets, encuesta, participar, crear/eliminar y ejecutar sorteos, tickets por partida (A3)
│   │   ├── LeaderboardService.java  # Rankings, historial y premiar/resetear por periodo (A3)
│   │   ├── MediaService.java        # Subida de videos y descarga con soporte Range (A3)
│   │   ├── DbAdminService.java      # Panel `/admin-db`; tabla, edición y export/import parcial
│   │   ├── PostgresBackupService.java # Backup y restore PostgreSQL completo, temporal y transaccional
│   │   ├── ChatbotService.java      # Chatbot IA: conversación, base de conocimiento, entrenamiento (la IA le pregunta al admin), dudas sin respuesta y tono. Ver sección 17
│   │   ├── ChatbotIaCliente.java    # Cliente HTTP de la IA (Gemini u OpenAI) con timeout y tope de llamadas simultáneas
│   │   ├── ChatbotConocimiento.java # Lógica pura: normaliza preguntas y elige qué entradas de la base ve la IA
│   │   ├── ChatbotListener.java     # Listeners de `chatbot.*`: correo al admin y entrega de la respuesta al usuario
│   │   ├── SoporteService.java      # "Hablar con el admin" (24.5): crear ticket, mis tickets, bandeja admin y responder. Ver sección 19
│   │   ├── SoporteListener.java     # Listeners de `soporte.*`: correo al admin al crear un ticket; aviso + correo al usuario al responderlo
│   │   ├── EvidenciaPagoService.java # Evidencias de pago (7): el admin publica el comprobante de un retiro pagado; historial del canal «pagos». Ver sección 20
│   │   └── EvidenciaPagoListener.java # Listener de `pagos.evidencia_publicada`: mensaje en vivo al canal «pagos» + push a quien recibió el pago
│   ├── servidor/                    # Capa HTTP (A3): parsea el request, llama a un servicio y responde. Sin lógica de negocio ni SQL
│   │   ├── RateLimitMiddleware.java # Rate limiting por IP (S8): clasifica cada request y responde 429. Ver sección 10
│   │   ├── AuthMiddleware.java      # Sesión JWT (S1): valida el JTI persistido, inyecta identidad y revoca/renueva la sesión. Ver sección 16
│   │   ├── AdminMiddleware.java     # Exige sesión + rol admin (leído de la BD) en `/api/admin/*`, `/api/deposit` y `/admin-fix-status/*` (S3). Ver sección 18
│   │   ├── ManejadorErrores.java    # Único handler de `ServicioException` → `{error}` con su código HTTP (A3)
│   │   ├── Peticion.java            # Lee campos del body, parámetros de ruta y el userId de la sesión JWT; campo faltante/inválido → 400 (A3)
│   │   ├── RutasAuth.java           # Login, registro, sesión
│   │   ├── RutasAdmin.java          # Operaciones admin (aprobar retiros, etc.)
│   │   ├── RutasDbAdmin.java        # CRUD admin sobre BD
│   │   ├── RutasFinanzas.java       # Depósitos, retiros, Wompi webhook
│   │   ├── RutasLeaderboard.java    # Rankings diario/semanal/mensual/anual
│   │   ├── RutasComisiones.java     # GET /api/comisiones/simular y /distribucion (solo lectura). Ver sección 9
│   │   ├── RutasMedia.java          # Upload/descarga de videos
│   │   ├── RutasChatbot.java        # `/api/chatbot/*` (usuario) y `/api/admin/chatbot/*` (admin). Ver sección 17
│   │   ├── RutasSoporte.java        # `/api/soporte/tickets` (usuario) y `/api/admin/soporte/tickets*` (admin). Ver sección 19
│   │   ├── RutasEvidenciaPago.java  # `/api/pagos/evidencias` (usuario) y `/api/admin/pagos/*` (admin). Ver sección 20
│   │   └── RutasSorteos.java        # CRUD sorteos y participaciones
│   └── socketio/                    # Adaptador custom Socket.IO
│       ├── SocketIOServer.java      # Parseo de paquetes Engine.IO/Socket.IO
│       └── SocketIOClient.java      # Wrapper del cliente WS
├── src/main/resources/
│   └── db/migration/                # Migraciones Flyway V1–V17; V17 aplica unicidad normalizada de identidades
├── agent/rules/                     # Reglas para modelos IA
│   ├── auto-push.md
│   └── project-conventions.md       # ← NUEVO
├── ARCHITECTURE.md                  # ← ESTE ARCHIVO
├── CHANGELOG.md                     # ← NUEVO
├── pom.xml                          # Maven: dependencias
└── Dockerfile                       # Imagen para Render
```

---

## 3. Tablas de la BD

| Tabla | Propósito | Columnas clave |
|-------|----------|----------------|
| `users` | Usuarios registrados | `id`, `username`, `email`, `password` (bcrypt), `saldo`, `player_tag`, `total_victorias`, `total_monto_torneos`, `gen_*` (desglose comisiones) |
| `messages` | Chat de la app | `canal`, `usuario`, `texto`, `tipo` |
| `matches` | Historial de partidas | `jugador1`, `jugador2`, `monto`, `ganador`, `estado`, `sala_id` (único cuando no es nulo) |
| `transactions` | Depósitos y retiros | `usuario_id`, `tipo`, `metodo`, `monto`, `referencia`, `estado`, `external_id`. V12 hace única `referencia` en depósitos Wompi; V11 hace único `external_id` no nulo. |
| `admin_wallet` | Bóveda de comisiones del admin | `monto`, `razon`, `categoria` (sorteos, misiones, etc.) |
| `user_tokens` | Tokens FCM por usuario | `user_id`, `fcm_token` |
| `user_tickets` | Tickets de sorteo acumulados | `user_id`, `cantidad`, `acumulado` |
| `raffles` | Sorteos activos/completados | `nombre`, `categoria`, `precio`, `tickets_necesarios`, `ganador_id` |
| `raffle_entries` | Participaciones en sorteos | `raffle_id`, `user_id`, `tickets_asignados` |
| `raffle_votes` | Votos de categoría de sorteo | `user_id`, `categoria` |
| `leaderboard_history` | Historial de rankings | `user_id`, `periodo`, `victorias`, `posicion`, `premio`, `corte_pago_id` (único para historiales de cortes nuevos) |
| `leaderboard_pools` | Acumulado de premios del ranking y marcas del último corte | `dia`, `semana`, `mes`, `ano`, `ultimo_corte_*` |
| `leaderboard_cuts` | Cortes recuperables del leaderboard | `periodo`, `clave_corte`, `pozo`, `estado` |
| `leaderboard_cut_payments` | Snapshot de destinatarios y estado recuperable de cada pago | `corte_id`, `user_id`, `tipo_pago`, `monto`, `estado`, `intentos`, `ultimo_error` |
| `media_files` | Videos subidos (como BYTEA) | `filename`, `content_type`, `data` |
| `chatbot_conocimiento` | Base de conocimiento del chatbot y dudas pendientes | `pregunta`, `pregunta_normalizada`, `respuesta`, `origen` (ia/usuario/admin), `estado` (pendiente/respondida/descartada), `veces` |
| `chatbot_duda_usuarios` | Quiénes hicieron cada duda pendiente (para avisarles) | `duda_id`, `user_id` |
| `chatbot_mensajes` | Historial del chat de cada usuario con el bot | `user_id`, `rol` (usuario/bot), `texto`, `fecha` |
| `chatbot_preferencias` | Tono elegido por cada usuario | `user_id`, `tono` |
| `chatbot_config` | Ajustes globales del bot | `clave`, `valor` (`tono_defecto`) |
| `wallet_ledger` | Auditoría inmutable de movimientos de dinero (D3) | `id` (BIGSERIAL), `user_id`, `tipo` (deposito/retiro/torneo/premio/comision/liquidacion), `monto`, `saldo_anterior`, `saldo_posterior`, `referencia`, `fecha`. Solo INSERT, nunca UPDATE/DELETE. `user_id=0` para comisiones del sistema |
| `legal_acceptances` | Registro de aceptación de documentos legales | `user_id`, `documento`, `version`, `aceptado_en` |
| `support_tickets` | Tickets de "Hablar con el admin" (24.5) | `user_id`, `categoria` (pregunta/sugerencia/bug/cuenta/otro), `asunto`, `mensaje`, `estado` (abierto/resuelto), `respuesta`, `respondido_por`, `fecha_creacion`, `fecha_respuesta` |
| `payment_evidences` | Comprobantes de retiros pagados que el admin publica en el canal «pagos» (7) | `transaction_id` (UNIQUE: una por retiro), `user_id` (a quien se pagó), `admin_id`, `monto`, `metodo`, `nota`, `imagen` (data URI base64), `fecha`. Usuario, monto y método se copian de la transacción |
| `match_queue` | Cola de búsquedas de partida activas persistidas en BD (A2) | `id`, `user_id` (UNIQUE), `username`, `creado_en`, `expira_en` |
| `active_matches` | Salas en negociación y partidas en curso persistidas en BD (A2) | `id`, `sala_id` (UNIQUE), `player1_id`, `player2_id`, `iniciado`, `inicio_revision`, `monto`, `match_db_id`, `player_tag1`, `player_tag2`, `match_start_time`, `last_modo`, `last_dinero`, `votos_json`, `confirmaciones_json`, `poll_count` |

**Tokens FCM en Capacitor:** `useCapacitor` escucha el evento nativo `registration`, conserva el token solo en memoria y lo registra en `POST /api/register-token` cuando existe una sesión; una renovación envía el token anterior para reemplazarlo en la misma transacción. Al cerrar sesión, el cliente incluye los tokens de este dispositivo en `POST /api/logout`, que los elimina por usuario antes de borrar la cookie. Los tokens que FCM invalide se eliminan al recibir el error correspondiente del proveedor.

---

## 4. Socket.IO, canales y flujo de una partida

```mermaid
sequenceDiagram
    participant J1 as Jugador 1
    participant S as Servidor (SocketHandler)
    participant J2 as Jugador 2

    J1->>S: Handshake con cookie JWT
    S->>S: Validar JWT/sesión y cargar usuario
    S-->>J1: Historial anuncios/general/clash
    Note over S,J1: clash_logs solo se entrega a admins autenticados
    J1->>S: buscar_partida
    S->>J1: partida_encontrada
    S->>J2: partida_encontrada
    J1->>S: iniciar_juego / confirmar_partida_resp
    J2->>S: iniciar_juego / confirmar_partida_resp
    S->>S: WalletService persiste cobros y active_matches atómicamente
    S->>J1: juego_iniciado / resultado_api
    S->>J2: juego_iniciado / resultado_api
```

**Autorización por canal y cancelaciones:** los sockets anónimos no reciben historial ni mensajes en vivo de `clash_logs`; el servidor vuelve a comprobar el rol admin en la BD para cada difusión de logs. Los canales `general`, `anuncios` y `clash` conservan su difusión pública, pero publicar requiere sesión y `anuncios` además requiere admin. Los mensajes privados se envían únicamente a la room de la partida. `reportar_resultado` no se registra porque no existe handler. `cancelar_busqueda`, `cancelar_inicio` y `cancelar_match` requieren la identidad JWT del socket y la pertenencia/estado correspondiente; sus fallos devuelven `error_socket` con `{evento, codigo, mensaje}` y sus éxitos `cancelacion_completada`. La UI cambia de estado al recibir confirmación.

**Puente de polling:** Engine.IO mantiene solicitudes GET long-polling y publica `pingInterval` y `pingTimeout` en el handshake. Este servidor usa 25 s + 20 s; el proxy HTTP espera 60 s para dejar margen al long-poll. El presupuesto total de solicitudes se limita a la mitad de los hilos HTTP (32 por defecto) y se divide entre semáforos GET y POST, para que los GET pendientes no bloqueen el envío de eventos. El tope se configura con `SOCKET_IO_POLLING_MAX_CONCURRENT`. El cliente React actual fuerza WebSocket, así que no ocupa esos cupos.

> [!NOTE]
> **A2 completado (Persistencia de estado en BD):** Las búsquedas en cola (`match_queue`) y las salas/partidas activas (`active_matches`) se sincronizan en PostgreSQL. Al reiniciar el servidor, `SocketHandler.recuperarEstadoDesdeBD()` restaura las salas en negociación, reanuda el sondeo de la API de Clash Royale (`iniciarPollingApi`) desde el `poll_count` guardado para las partidas en curso y reprograma los temporizadores de búsqueda con el tiempo restante real.

> **Inicio de partida recuperable (V14):** `WalletService.iniciarPartidaTorneo()` bloquea la fila de `active_matches` y, en una única transacción, inserta `matches` con el `sala_id` único, descuenta ambos saldos, agrega los dos asientos al ledger y marca la sala iniciada con su `match_db_id`, tags y hora de inicio. Un reintento de la misma sala devuelve la partida persistida sin repetir el débito. Al arrancar, `reconciliarPartidasTorneoAlReiniciar()` vincula automáticamente una partida antigua solo si encuentra un único `matches` compatible posterior a la última actualización de la negociación y los dos movimientos correspondientes del ledger. Si hay más de un candidato, marca `inicio_revision` y conserva la sala bloqueada, sin cobrar ni eliminarla por timeout; requiere conciliación administrativa.

---

## 5. Flujo de dinero (D2)

Todo movimiento de saldo pasa por `WalletService` (D2). Cada operación es una transacción SQL atómica
(`conn.setAutoCommit(false)` + commit/rollback) y usa `BigDecimal` internamente. Las deducciones verifican
saldo con `WHERE saldo >= ?` dentro de la misma transacción. Después de cada commit exitoso se emite un
evento al bus de eventos (A4): `wallet.deposited`, `wallet.withdrawn`, `wallet.bet`, `wallet.awarded`.

```
Depósito:
  Wompi (tarjeta) → /api/wompi/init → webhook verificado → FinanzasService → WalletService.acreditarDepositoWompi() → evento wallet.deposited
  Nequi (manual)  → admin aprueba → AdminService → WalletService.depositar() → evento wallet.deposited

Torneo (monto):
  1. WalletService.iniciarPartidaTorneo() guarda el débito de ambos jugadores, matches, wallet_ledger y active_matches en una transacción.
  2. El match queda vinculado por sala_id único; los reintentos recuperan match_db_id y saldos sin volver a cobrar.
  3. Al liquidar: WalletService.liquidar() paga al ganador y distribuye comisiones atómicamente.
  4. Comisión se distribuye en admin_wallet por categoría (ComisionService, sección 9).
  5. Se emite match.finished al bus → listeners acreditan tickets y notifican.

Retiro:
  Usuario solicita → WalletService.retirar() descuenta atómicamente → evento wallet.withdrawn
  Admin aprueba/rechaza la transacción pendiente

Premio (leaderboard, sorteo):
  Leaderboard: WalletService.premiarLeaderboard() acredita saldo, registra wallet_ledger y marca
  leaderboard_cut_payments como pagado en la misma transacción; bloquear el pago por ID lo hace idempotente.
  Sorteo: WalletService.premiar() → evento wallet.awarded
```

Los cierres de leaderboard guardan primero en una transacción el monto del pozo, el ranking, los pagos
individuales a jugadores/admins, la marca del período y el reinicio de victorias. El job vuelve a procesar
cortes pendientes cada minuto; cada pago fallido conserva su error y puede reintentarse, sin recalcular
montos ni duplicar créditos. El historial se vincula al pago con una clave única, y el corte solo pasa a
completado cuando todos los créditos y los historiales de jugadores existen.

En `POST /api/wompi/init`, `FinanzasService` genera una referencia `TF-` e inserta el pendiente con `ON CONFLICT`.
El índice parcial único de V12 serializa solicitudes concurrentes; ante una colisión, el servicio genera otra
referencia hasta cinco veces. Los intentos que colisionan no dejan filas pendientes; si se agotan los intentos,
la solicitud falla sin crear una recarga. El webhook correlaciona la referencia con el ID externo de Wompi (V11).

> [!NOTE]
> - Las transacciones SQL son atómicas (D2 completado). Si falla a mitad, se hace rollback.
> - Todas las columnas monetarias son `NUMERIC` en PostgreSQL y se leen, calculan y escriben como `BigDecimal` en todo el backend (`GenericDAO`, `UsuarioDAO`, `WalletService`, `FinanzasService`, `AdminService`, `LeaderboardService`, `SorteoService`, `SocketHandler`) sin pasar por `double` (D1 completado).
> - `UsuarioDAO.actualizarSaldo()` y `obtenerSaldo()` están deprecados (usan `double`). Todo pasa por WalletService.
> - **D3 completado**: cada operación de `WalletService` inserta una fila en `wallet_ledger` dentro de la misma transacción SQL (atómico). La tabla es inmutable (solo INSERT). Las comisiones de liquidación se registran con `user_id=0`.

---

## 6. Convenciones

Ver archivo completo en [`agent/rules/project-conventions.md`](file:///c:/Users/juand/Downloads/pagina/TorneosFlash/agent/rules/project-conventions.md).

**Resumen rápido:**
- `userId` → siempre de JWT/sesión, nunca del body
- Dinero → `WalletService` + `BigDecimal` + marca `// REVIEW-MONEY`
- HTML usuario → escapar, nunca `innerHTML`
- SQL → `PreparedStatement` con `?`
- Tablas nuevas → Flyway
- Config → variables de entorno
- Post-tarea → editar `CHANGELOG.md` + auto-push

---

**Prevención de SQL Injection (S4):**
- Todas las consultas en `GenericDAO`, `UsuarioDAO` y servicios usan exclusivamente `PreparedStatement` con parámetros `?`.
- Se eliminaron `GenericDAO.execute(String)` y `UsuarioDAO.ejecutarUpdate(String, Object...)`, y se reemplazaron todos los usos de `Statement` en `DbAdminService` por `PreparedStatement` con validación estricta de identificadores (`information_schema` + regex `^[a-zA-Z_][a-zA-Z0-9_]*$`).
- En `LeaderboardService`, las columnas de ordenamiento y reseteo salen únicamente de listas blancas cerradas (`switch`).

## 7. Seguridad: XSS en chat (S5)

Defensa en dos capas; ninguna confía en la otra.

| Capa | Dónde | Qué hace |
|------|-------|----------|
| **Render (frontend)** | `public/app.js` (`agregarBurbuja`, `convertirLinks`, `abrirMediaModal`), `frontend/src/pages/Chat.jsx` | El contenido del usuario entra solo por `textContent` / nodos DOM / JSX. Los links se crean con `createElement('a')` (solo `http(s)`, `rel="noopener noreferrer"`). Las plantillas grandes que aún usan `innerHTML` pasan los datos del usuario por `esc()`. Los `src` de imagen/video se validan con `resolverMedia()`. |
| **Servidor** | `servicio/ChatSanitizer.java`, usado por `SocketHandler` (`mensaje_chat`, `mensaje_privado`) y `RutasAuth` (registro) | Valida canal (`general`/`anuncios`/`clash`), tipo (`texto`/`imagen`/`video`), quita etiquetas HTML y caracteres de control, limita longitud (500 texto, 50 usuario) y solo reenvía campos permitidos. Imagen = data URI `png/jpeg/gif/webp` (sin SVG) o `/api/media/{id}`; video = `/api/media/{id}`; multimedia solo en `anuncios`. |

- El servidor **no** convierte a entidades HTML (`&lt;`): se guarda texto plano y cada frontend lo escapa al pintar, así no se ven entidades dobles.
- `GET /api/media/{id}` responde con `X-Content-Type-Options: nosniff`.
- Los usernames nuevos (3–30 caracteres) no pueden llevar `<`, `>`, `"`, `'`, `&`, el acento grave ni caracteres de control.
- **Identidad del emisor (S2 completado):** `SocketIOServer` autentica la cookie JWT en el handshake y `SocketHandler` usa siempre el `username` y rol reales de la sesión para `mensaje_chat`, `mensaje_privado`, `registrar_socket` y `buscar_partida` (y verifica en BD que solo un admin escriba en `anuncios`).

---

## 8. Validación de montos en el servidor (D5)

Todo monto que llega del cliente pasa por `servicio/ValidadorMonto` **antes** de tocar `WalletService` o la BD. `WalletService` conserva sus propias guardas (`monto > 0`, `WHERE saldo >= ?`) como segunda capa.

**Un monto es válido si:** es número (JSON number o string numérico, sin notación científica ni `+`), es > 0, es en pesos enteros (`5000` / `5000.00` sí, `5000.5` no) y está dentro del rango de su tipo. Devuelve `BigDecimal` de escala 0.

| Tipo | Método | Min (env) | Max (env) | Dónde se usa |
|------|--------|-----------|-----------|--------------|
| Depósito | `deposito()` | `MONTO_MIN_DEPOSITO` = 1000 | `MONTO_MAX_DEPOSITO` = 5.000.000 | `/api/deposit`, `/api/transaction/create`, `/api/wompi/init` |
| Retiro | `retiro()` | `MONTO_MIN_RETIRO` = 10000 | `MONTO_MAX_RETIRO` = 5.000.000 | `/api/transaction/withdraw` |
| Torneo | `torneo()` | `MONTO_MIN_TORNEO` = 1000 | `MONTO_MAX_TORNEO` = 10000 | socket `iniciar_juego`; mínimo en `buscar_partida` |
| Sorteo | `sorteo()` | `MONTO_MIN_SORTEO` = 1000 | `MONTO_MAX_SORTEO` = 100.000.000 (tope `Integer.MAX_VALUE`) | `/api/admin/raffle/create` |

- Los errores de validación HTTP responden **400** con `{ "error": "..." }`; en el socket se emite `error_negociacion` solo al jugador que envió el monto.
- Negociación de torneo: el voto de cada jugador se guarda ya validado y normalizado; un voto nuevo invalida confirmaciones previas; al cobrar se exige que ambos votos existan y coincidan y que ambos tengan saldo.
- Cierre con D1: el cálculo de la tarifa de pasarela Wompi (`FinanzasService.calcularMontoCentavosWompi`) y `ActiveMatch.monto` en `SocketHandler` usan `BigDecimal`. `/api/deposit` exige admin (S3, sección 18).

---

## 9. Comisiones: `ComisionService` (D4)

Una sola clase decide cuánto se cobra y cómo se reparte. Es lógica pura (no toca BD ni mueve dinero); `WalletService.liquidar()` la usa dentro de su transacción y el frontend la consulta por HTTP en vez de replicar la fórmula.

**Porcentaje sobre el pozo** (pozo = monto x 2): 25% con pozo 2.000, baja linealmente hasta 10% con pozo >= 20.000. `comisión = floor(pozo x %)`, `premio = pozo - comisión`.

**Reparto de la comisión** (enum `ComisionService.Categoria`, cada una redondeada hacia abajo a pesos enteros):

| Categoría (`admin_wallet.categoria`) | % de la comisión |
|---|---|
| `sorteos` | 20% |
| `misiones` | 10% |
| `logros` | 5% |
| `leaderboard` | 15% (se reparte en 4 pozos: día/semana/mes/año) |
| `devolucion` | 15% |
| `referidos` | 10% |
| `ganancia` | residuo (= 25%): lo que sobra, así la suma siempre cuadra al peso |

Cada jugador se atribuye la mitad de cada categoría (`Desglose.porJugador()`, columnas `users.gen_*` y `ganancia_generada`); de ahí salen también los tickets de sorteo (`SorteoService.acumularTicketsPorPartida`).

| Endpoint | Respuesta |
|---|---|
| `GET /api/comisiones/simular?monto=5000` | `{ success, monto, pozo, porcentajeComision, comision, premio }`. El monto se valida con `ValidadorMonto.torneo()` (400 si es inválido). Lo usa `Match.jsx` para "Si ganas recibes". |
| `GET /api/comisiones/distribucion` | `{ success, distribucion: { sorteos: 20, misiones: 10, ... } }`. Lo usa `Admin.jsx` para los títulos del desglose. |

- Para cambiar la política se edita **solo** `ComisionService` (constantes y enum). El panel admin y `Match.jsx` se actualizan solos.
- Pendiente: el frontend legacy de `public/` (`app.js`, `public/js/*`) no se tocó en D4 y podría conservar su propia copia de la fórmula (no verificado); muere con la migración a React. La tarifa de pasarela Wompi (`FinanzasService.calcularMontoCentavosWompi`) ya se calcula con `BigDecimal` y `RoundingMode.CEILING` (D1).

---

## 10. Rate limiting (S8)

Middleware global (`servidor/RateLimitMiddleware`, registrado en `Main` antes de rutas y sockets) que cuenta requests **por IP** con ventana deslizante de 1 minuto. El conteo vive en `servicio/RateLimiter` (en memoria: se reinicia con el servidor). Al pasarse del límite responde **429** con `{ "error": "Demasiadas solicitudes...", "retryAfter": N }` y el header `Retry-After`; las respuestas normales llevan `X-RateLimit-Limit` / `X-RateLimit-Remaining` del cubo más estricto que les aplica.

| Cubo | Límite (env) | Qué rutas |
|------|--------------|-----------|
| General | `RATE_LIMIT_GENERAL_PER_MIN` = 60 | Todo lo que empiece por `/api/`, `/admin-db/`, `/secret-admin/`, `/admin-fix-status/` y `/check-ip` |
| Financiero | `RATE_LIMIT_FINANCIAL_PER_MIN` = 10 | `/api/deposit`, `/api/transaction/create`, `/api/transaction/withdraw`, `/api/wompi/init`, `/api/admin/transaction/process`, `/api/admin/resolve-dispute`. Cuentan aquí **y** en el general |
| Auth | `RATE_LIMIT_AUTH_PER_MIN` = 10 | `/api/login`, `/api/register`, `/api/check-username/*`, `/api/check-email/*`, `/api/verify-tag/*`. Cuentan aquí **y** en el general |
| Media | `RATE_LIMIT_MEDIA_PER_MIN` = 300 | `GET /api/media/*` (los videos hacen muchas peticiones Range; el chat carga varias imágenes a la vez) |

- **Exentos:** `OPTIONS` (preflight CORS), archivos estáticos del frontend, WebSocket (Socket.IO) y `POST /api/wompi/webhook` (lo llama Wompi, no un usuario; limitarlo podría perder pagos reales. Su defensa es verificar la firma SHA-256 en `FinanzasService`, S7).
- **IP del cliente:** detrás de Render `ctx.ip()` es la IP del proxy. Se lee `X-Forwarded-For` contando desde la **derecha** con `RATE_LIMIT_PROXY_HOPS` (default 1): lo que está a la izquierda lo escribe el cliente y es falsificable. `0` ignora el header. Si Render/Cloudflare añaden más de una entrada y varios usuarios caen en la misma clave, subir a 2 (se comprueba con `/check-ip`).
- **Apagar (solo para pruebas locales):** `RATE_LIMIT_ENABLED=false`.
- **CORS en la 429:** el middleware corre antes que el plugin CORS de Javalin y al rechazar se saltan los handlers restantes, así que `RateLimitMiddleware` añade a mano `Access-Control-Allow-Origin` (refleja el `Origin`), `Allow-Credentials` y `Expose-Headers: Retry-After, X-RateLimit-*`. Sin eso la app React/Capacitor (otro origen que el backend) no podría leer el mensaje de la 429.
- **Clientes (frontend):** `Auth.jsx` muestra "Demasiadas solicitudes" en las validaciones de usuario/correo/tag en vez de marcarlos como ocupados; `Match.jsx` consulta la ganancia con debounce de 400 ms; `Sorteos.jsx` recarga solo `/api/raffle/offers` tras participar (2 peticiones por clic en +/−, antes 5). Finance, Leaderboard y Chat ya muestran `data.error` o ignoran respuestas no OK. No auditados: `Admin.jsx` y el frontend legacy de `public/`.
- **Memoria:** una clave guarda como máximo `limite` marcas de tiempo; se limpian las inactivas cada minuto y hay tope de 100.000 claves.
- **Chatbot (roadmap 20):** `POST /api/chatbot/mensaje` cuenta en el cubo general y, además, en un límite propio por IP (`RATE_LIMIT_CHATBOT_PER_MIN` = 10) que aplica `RutasChatbot.limitarPorIp` (no `RateLimitMiddleware`) con la misma regla de IP real. Cada mensaje es una llamada de pago a la IA; el límite por usuario está en la sección 17.
- **Límites conocidos:** (1) es por IP, no por usuario: varios jugadores detrás de la misma red/CGNAT comparten cupo (por eso todo es configurable); cuando el JWT (S1) esté conectado se puede añadir un cubo por `userId`. (2) No cubre mensajes de Socket.IO (spam de chat por socket): requiere un limitador propio en `SocketHandler`.

---

## 11. Enlace de acceso a la BD (S10)

`DATABASE_URL` se mantiene igual (formato de Render `postgres://usuario:clave@host/db`). `ConexionDB.EnlaceBD.desde()` lo convierte en una URL JDBC **sin credenciales** más usuario/clave por separado (`HikariConfig.setUsername/setPassword`), así la clave no aparece en la URL, en logs ni en mensajes de error (el log de arranque solo muestra `host` y `sslmode`).

| Aspecto | Comportamiento |
|---|---|
| Credenciales | Antes de la última `@`; usuario hasta el primer `:`; se decodifica `%XX`. Claves con `:` o `@` ya no rompen la conexión |
| SSL | Variable opcional `DB_SSLMODE`: `require` (default, cifrado), `verify-ca` o `verify-full` (además verifican el certificado). Valor inválido → `require` + log de error |
| Parámetros de la URL | `ssl`, `sslmode` y `sslfactory` se ignoran (no se puede bajar a `disable` ni usar `NonValidatingFactory`); `user`/`password` en la query se mueven a Hikari; el resto se conserva |
| Formatos aceptados | `postgres://`, `postgresql://`, `jdbc:postgresql://` y `host:puerto/db` |

- La rotación de la contraseña de la BD se hace en Render (no desde el código).
- `verify-full` no se ha probado contra el certificado de Render; si la conexión falla con ese modo, quitar `DB_SSLMODE`.

### Panel de la BD en el navegador (`/admin-db`)

| Aspecto | Comportamiento |
|---|---|
| URL | `/admin-db` (sin clave). `/admin-db/<algo>` redirige a `/admin-db` |
| Clave | `DB_ADMIN_SECRET`. Se escribe en la pantalla de acceso de `public/admin-db.html`, queda en `sessionStorage` (solo esa pestaña) y viaja en el header `X-DB-Admin-Key` |
| API | `/api/db-admin/tables`, `/table/{name}[/update\|/delete\|/insert]`, `/export`, `/import` (antes llevaban la clave en la ruta) |
| Backup completo | `GET /api/db-admin/backup/full` genera y transmite un archivo PostgreSQL custom (`pg_dump`) |
| Restore completo | `POST /api/db-admin/restore/full` valida un `.dump`, exige `X-DB-Admin-Confirm: RESTAURAR BASE DE DATOS` y restaura en mantenimiento |
| Sin clave configurada | Si está vacía o es la antigua `torneos2024`, el panel y su API responden 404 y se loguea un error |
| Fuerza bruta | Comparación SHA-256 en tiempo constante; 5 fallos desde una IP → 429 durante 15 min (en memoria). Sin header = 401, no cuenta |
| Cabeceras | `Cache-Control: no-store`, `Referrer-Policy: no-referrer`, `X-Frame-Options: DENY`, `X-Robots-Tag: noindex`, CSP `frame-ancestors 'none'` |
| SQL | Todo nombre de tabla/columna se valida contra `information_schema` y se cita con comillas dobles; los valores van siempre por `PreparedStatement` |

El export/import JSON sigue siendo **parcial** y solo cubre tablas editables no financieras. El backup completo se genera con `pg_dump` en formato custom y conserva esquema y datos de la base de la aplicación, incluidos hashes de usuarios, tokens, transacciones, saldos y `wallet_ledger`. El servidor transmite el archivo y borra su copia temporal al terminar; descarga los respaldos a un almacenamiento externo al contenedor. El valor por defecto de `DB_ADMIN_MAX_BACKUP_BYTES` es 1 GiB (rango 1 MiB–10 GiB) y limita el archivo temporal de backup y el tamaño de subida.

Para restaurar, `pg_restore --list` valida el formato y la presencia de tablas de identidad/finanzas antes de pausar el tráfico. Un segundo advisory lock serializa backups y restores entre instancias. `ConexionDB` usa locks compartidos por todas las instancias: las conexiones activas terminan antes de que la instancia restauradora adquiera el lock exclusivo, y las consultas nuevas reciben mantenimiento durante el restore. Luego ejecuta `pg_restore --clean --if-exists --single-transaction --exit-on-error`; cualquier error de PostgreSQL revierte el restore completo, manteniendo juntos usuarios/saldos, transacciones y ledger del snapshot. Después se aplican migraciones Flyway pendientes. Si Flyway falla luego de que PostgreSQL confirmó el restore, el servicio permanece bloqueado en mantenimiento para evitar servir con un esquema incompatible; corregir el error y reiniciar la aplicación.

La imagen de ejecución contiene Java 17 y cliente PostgreSQL 18 (`pg_dump`/`pg_restore`). `DATABASE_URL` debe dar al rol de la aplicación permisos para crear/eliminar los objetos restaurados; se restaura como el rol actual, sin propietarios ni ACL del origen. Los roles globales y tablespaces del clúster no forman parte del backup. Solo se deben restaurar archivos propios y confiables. No se hizo una restauración contra la base de datos desplegada como parte de la implementación.

---

## 12. Configuración por variables de entorno (A7)

Toda configuración de despliegue se lee en `config/AppConfig` (nada de números ni URLs escritos en `Main` o en los handlers). Todas las variables de esta tabla son **opcionales**: sin definirlas queda el default, que es el valor que antes estaba escrito en el código. Un valor inválido (no numérico o fuera de rango) cae al default y deja un `logger.error` al arrancar. Las demás variables viven en sus secciones: montos (8), rate limiting (10) y BD (11).

| Variable | Default | Rango | Qué controla |
|---|---|---|---|
| `APP_BASE_URL` | `https://torneos-beta.onrender.com` | http(s) | URL del sitio para push y enlaces de recuperación (`/auth#reset=...`). Se quita la `/` final |
| `PASSWORD_RESET_TOKEN_MINUTES` | `15` | 5–60 | Vigencia del enlace de recuperación enviado por Brevo |
| `PASSWORD_RESET_COOLDOWN_SECONDS` | `60` | 30–600 | Pausa mínima entre correos de recuperación para una cuenta |
| `PUSH_POOL_THREADS` | `4` | 1–32 | Hilos exclusivos para entregas FCM |
| `PUSH_POOL_QUEUE` | `200` | 1–10000 | Cola acotada de entregas FCM; al llenarse se descartan tareas y queda un aviso en el log |
| `PUSH_MAX_PER_USER_PER_MIN` | `10` | 1–1000 | Máximo de notificaciones push por usuario en una ventana móvil de un minuto |
| `BIND_HOST` | `0.0.0.0` | | Interfaz en la que escucha Javalin. No se usa `HOST` para no chocar con esa variable de sistema |
| `WS_MAX_MESSAGE_BYTES` | `50000000` | >= 1024 | Tamaño máximo de un mensaje WebSocket (el chat manda imágenes en base64) |
| `SOCKET_IO_PORT` | `9092` | 0 - 65535 | Puerto interno donde escucha `netty-socketio` (Javalin expone `/socket.io/` en `PORT` y redirige internamente; si está ocupado elige uno libre) |
| `SOCKET_IO_POLLING_MAX_CONCURRENT` | la mitad de `HTTP_MAX_THREADS` (32 por defecto) | 2–la mitad de `HTTP_MAX_THREADS` | Presupuesto total de solicitudes del puente HTTP long-polling, repartido por igual entre GET largos y POST de eventos; reserva al menos la mitad del pool HTTP para otras rutas. React usa WebSocket directo (`transports: ['websocket']`) |
| `BUSQUEDA_TIMEOUT_MINUTOS` | `10` | 1 - 1440 | Minutos de búsqueda de rival antes de cancelarla (`SocketHandler`) |
| `NEGOCIACION_DESCONEXION_SEGUNDOS` | `90` | 1 - 3600 | Segundos que se espera a un jugador desconectado durante la negociación antes de cancelar el match |
| `POLLING_INTERVALO_SEGUNDOS` | `5` | 2 - 60 | Cada cuánto se consulta la API de Clash Royale por el resultado de una partida |
| `POLLING_MAX_INTENTOS` | `120` | 1 - 10000 | Intentos máximos; espera total = intervalo x intentos (default 10 min) y luego se crea la disputa |
| `CHAT_HISTORIAL_LIMITE` | `50` | 1 - 500 | Mensajes de historial que recibe cada canal al conectarse |
| `DB_ADMIN_MAX_BACKUP_BYTES` | `1073741824` | 1048576–10737418240 | Límite de bytes para los archivos temporales de backup y restore completos del panel DB |

- Los mensajes que ven los jugadores ("...nadie respondió en 10 minutos", "No se encontró el resultado en 10 minutos") y el campo `tiempo` de `rival_desconectado` se calculan con estos valores.
- `DB_ADMIN_SECRET` ya no tiene valor por defecto: sin ella el panel `/admin-db` queda deshabilitado (ver docstring de `RutasDbAdmin`).
- Siguen fijos en el código y fuera de A7: CORS `anyHost()`. Los cortes de leaderboard usan `America/Bogota` (UTC-5), definido en `LeaderboardService`; los pools de hilos pasaron a variables de entorno en A8 (siguiente sección).

---

## Hilos y pools (A8)

Todos los pools se crean en `config/Ejecutores` (con tamaños de `AppConfig`) y se reparten por constructor; ninguna clase crea sus propios `Executors`/`new Thread`. Cada pool tiene un solo trabajo, así el trabajo lento no deja sin hilos a los usuarios.

| Pool | Hilos (env, default) | Para qué | Si se llena |
|------|----------------------|----------|-------------|
| `http` (Jetty `QueuedThreadPool`, `config.jetty.threadPool`) | `HTTP_MAX_THREADS`=64 (min 16), `HTTP_MIN_THREADS`=8, `HTTP_IDLE_TIMEOUT_MS`=60000 | Peticiones HTTP y handlers de Socket.IO. Antes: el default de Javalin (250 hilos) | Las peticiones esperan en la cola de Jetty |
| `io` | `IO_POOL_THREADS`=16, `IO_POOL_QUEUE`=500 | Espera de red sin usuario esperando: correos Brevo y sondeo de la API de Clash (`Ejecutores.ejecutarIO`) | La tarea se descarta con un `warn` (correo es "mejor esfuerzo"; el sondeo reintenta en el siguiente tick) |
| `push` | `PUSH_POOL_THREADS`=4, `PUSH_POOL_QUEUE`=200 | Entregas FCM (`Ejecutores.ejecutarPush`), aisladas de correo y sondeo de Clash; limita cada usuario a `PUSH_MAX_PER_USER_PER_MIN`=10 | La tarea se descarta con un `warn`; un broadcast detiene el encolado al llenarse la cola y no crea una cola secundaria sin límite |
| `cpu` | `CPU_POOL_THREADS`=nº de núcleos, `CPU_POOL_QUEUE`=100 | Hash/verificación BCrypt en login y registro (`Ejecutores.enCpu`) | Lo ejecuta el hilo que lo pidió (contrapresión; nunca se pierde) |
| `timers` | `TIMER_POOL_THREADS`=4 | Solo temporizadores cortos: ping de Socket.IO, timeouts de búsqueda y abandono, revisión de sorteos/leaderboard. Reemplaza 3 pools sueltos (2 + 4 + 2 hilos) | - |
| BD (HikariCP, `torneos-db`) | `DB_POOL_MAX_SIZE`=20 | Conexiones a PostgreSQL | Esperan hasta 5 s por una conexión |

- **Sondeo de la API de Clash:** el `scheduleAtFixedRate` de `SocketHandler.iniciarPollingApi` ya no ejecuta la llamada HTTP (hasta 10 s) en el hilo del temporizador; cada tick la encola en `io`. Un `AtomicBoolean` por partida (`enCurso`) evita que dos sondeos de la misma partida se solapen (un solape podría liquidar dos veces; antes lo impedía el propio `scheduleAtFixedRate`).
- **Timeouts:** los pools de IO y push son acotados, así que toda llamada externa que corre ahí debe tener tope de tiempo: Brevo y FCM usan `connectTimeout` 10 s y `timeout` 15 s por petición; `/check-ip` usa 5 s. El `HttpClient` no usa estos pools como executor (con `send()` síncrono y pool acotado podría bloquearse a sí mismo).
- **Apagado:** `Main` registra un shutdown hook: `app.stop()` → `Ejecutores.cerrar()` (cancela timers, deja terminar IO y push 5 s, CPU 2 s) → `ConexionDB.cerrar()`.
- **Pools con nombre:** hilos daemon `io-N`, `push-N`, `cpu-N`, `timer-N`, Jetty `http-N`, Hikari `torneos-db ...`; un volcado de hilos (`jstack`) los distingue.
- **Ajuste:** los hilos HTTP bloquean esperando BD; con `HTTP_MAX_THREADS` muy por encima de `DB_POOL_MAX_SIZE` solo se acumulan esperas de hasta 5 s. Si hay latencia por cola de Jetty, sube `HTTP_MAX_THREADS`; si `PostgreSQL` rechaza conexiones, baja `DB_POOL_MAX_SIZE`.
- **Push por actividad:** los broadcasts recorren usuarios por páginas y encolan una tarea acotada por destinatario; cada usuario tiene un límite móvil de push/minuto. El chat público excluye sockets conectados que ya recibieron el evento en vivo, y el chat privado solo manda push si el rival no está conectado a esa sala.
- **Fuera de A8 (pendiente):** `GET /api/verify-tag` llama a Clash en el hilo HTTP; `GET /api/media/{id}` carga el archivo completo en RAM por petición; el ping de Socket.IO envía de forma secuencial (un cliente lento retrasa a los demás).

---

## 13. Bus de eventos (A4)

`eventos/EventBus` es un publicar/suscribir en memoria. Quien termina una acción de negocio llama `emit(nombre, datos)` y **no sabe quién escucha**; cada funcionalidad nueva (notificaciones, misiones, logros...) se suscribe con `on(nombre, idListener, listener)` sin tocar al emisor. Es lo que pide la convención "cada funcionalidad nueva lanza un evento y no notifica directamente". El catálogo de nombres y sus datos está en `eventos/Eventos`. Se crea una sola vez en `Main` y se reparte por constructor (`AdminService`, `SocketHandler`); no hay singleton estático.

| Evento | Datos | Lo emiten | Listeners (en orden) |
|---|---|---|---|
| `match.finished` | `Eventos.PartidaFinalizada`: `matchId`, `origen` (`API` / `DISPUTA`), `participantes` (ids de los 2 jugadores), `ganadorId`, `ganadorNombre`, `monto`, `premio`, `nuevoSaldoGanador`, `desglose` (`ComisionService.Desglose`) | `SocketHandler` (sondeo de la API de Clash, `API`) y `AdminService.resolverDisputa` (`DISPUTA`) | 1. `tickets-sorteo` (`TicketsPartidaListener`) · 2. `notificacion-resultado` (`ResultadoPartidaListener`) |
| `chatbot.duda_sin_respuesta` | `Eventos.DudaSinRespuesta`: `dudaId`, `userId`, `username`, `pregunta`, `nueva`, `veces` | `ChatbotService.responder` (la IA dijo `[SIN_RESPUESTA]`, ya guardada la duda) | `chatbot-correo-admin` (`ChatbotListener`): correo al admin solo si `nueva` y con tope por hora |
| `chatbot.duda_respondida` | `Eventos.DudaRespondida`: `dudaId`, `pregunta`, `respuesta`, `usuarios` | `ChatbotService.responderDuda` (solo si estaba pendiente) | `chatbot-aviso-usuarios` (`ChatbotListener`): historial del chat + socket `chatbot_respuesta` + push |
| `soporte.ticket_creado` | `Eventos.TicketCreado`: `ticketId`, `userId`, `username`, `categoria`, `asunto`, `mensaje` (nunca email ni teléfono) | `SoporteService.crear` (ya guardado el ticket) | `soporte-correo-admin` (`SoporteListener`): correo al admin con tope por hora |
| `soporte.ticket_respondido` | `Eventos.TicketRespondido`: `ticketId`, `userId`, `username`, `categoria`, `asunto`, `respuesta` | `SoporteService.responder` (solo la primera respuesta) | `soporte-aviso-usuario` (`SoporteListener`): socket `soporte_respuesta` + push + correo a la cuenta del usuario |

```
SocketHandler (sondeo) ── WalletService.liquidar()  ← transacción SQL: saldo, comisiones, gen_*, victorias_*, pozos del leaderboard
        │
        ├── UPDATE matches SET estado='finalizada'
        ├── EventBus.emit("match.finished", PartidaFinalizada)
        │       1. tickets-sorteo         → SorteoService.acumularTicketsPorPartida + socket `tickets_ganados` a cada jugador
        │       2. notificacion-resultado → (solo origen API) `actualizar_saldo` al ganador + `resultado_api` y push a ambos
        └── liberarJugadores()  (estado normal + `flujo_completado`)
```

**Reglas del bus**
- **Síncrono:** los listeners corren en el hilo que emite, en el orden de registro (`Main`). No hay cola que se llene ni eventos perdidos, y el orden de lo que reciben los clientes es el de siempre (`tickets_ganados` → `actualizar_saldo` → `resultado_api` → `flujo_completado`). Un listener NO debe bloquearse con HTTP externo: para red usa `Ejecutores.ejecutarIO` (el push ya lo hace por dentro).
- **Aislado:** si un listener lanza una excepción queda en el log (`❌ Listener '<id>' falló con el evento '<nombre>'`) y los demás siguen; `emit()` nunca lanza, así un listener roto no puede dejar una liquidación a medias ni a los jugadores sin liberar.
- **Se emite después de confirmar:** el evento cuenta algo que ya pasó (el dinero ya se movió y el match ya está `finalizada`); los listeners no revierten nada.
- **Sin persistencia:** si el servidor se reinicia a mitad de un evento, no se repite. Por eso lo que debe ser atómico con el dinero (saldo, comisiones, `gen_*`, `victorias_*`, pozos del leaderboard) sigue dentro de la transacción de `WalletService.liquidar()` y NO es un listener. Mismo criterio para D2/D3 (ledger).
- **Registrar dos veces no duplica:** `on()` ignora un listener con el mismo `id` ya suscrito al mismo evento (evita dar los tickets dos veces).
- **Anti-bucle:** un listener puede emitir otro evento; más de 8 niveles anidados se descartan con un `error` en el log.

**Cómo añadir algo**
1. Evento nuevo: constante en `Eventos` + `record` con sus datos, y en el emisor `eventBus.emit(Eventos.NOMBRE, new Datos(...))` *después* de confirmar el cambio. Nombres `entidad.accion` en pasado.
2. Listener nuevo: clase en `servicio/` con un método `registrar(EventBus)` que hace `bus.on(Eventos.X, "id-unico", this::metodo)`, y una línea en `Main` (el orden de las líneas es el orden de ejecución). No hay que tocar `SocketHandler` ni `AdminService`.

**Pendiente / fuera de A4**
- Misiones y logros (aún no existen; la comisión ya les reserva `misiones` 10% y `logros` 5%) y el ranking en vivo del leaderboard serán listeners de `match.finished` cuando se construyan. Los contadores `victorias_*` del leaderboard no se movieron a un listener porque deben ser atómicos con la liquidación.
- Todavía no emiten evento: la disputa creada (`disputa_creada` / `disputa_timeout` en el sondeo), la partida cancelada, los movimientos de dinero de `WalletService` (depósito, retiro, premio; D2/D3 y "Notificaciones de dinero" del roadmap), el rate limiting (S8) ni los sorteos.
- La notificación de `DISPUTA` sigue en `AdminService.resolverDisputa` (`actualizar_saldo` + `flujo_completado`, sin `resultado_api` ni push): no se cambió lo que ve el jugador.

---

## 14. Migraciones de BD con Flyway (A5)

El esquema de PostgreSQL ya no lo crea el código Java: lo versionan scripts SQL en `src/main/resources/db/migration/` y los aplica Flyway al arrancar (`ConexionDB.migrar()`, llamado desde `Main` justo después de conectar y antes de crear DAOs). `ConexionDB.inicializarTablas()` ya no existe.

| Script | Qué hace |
|---|---|
| `V1__esquema_base.sql` | Las 13 tablas y todas sus columnas (`CREATE TABLE IF NOT EXISTS` + `ADD COLUMN IF NOT EXISTS`), la fila `leaderboard_pools.id = 1` y el renombrado F5 (`apuesta` → `monto`, `total_apostado` → `total_monto_torneos`) solo si la BD aún tiene los nombres viejos |
| `V2__migrar_datos_antiguos.sql` | Conversión única de datos viejos: desglose `gen_*` desde la antigua `ganancia_generada` y reparto en 7 categorías de las filas de `admin_wallet` sin categoría |
| `V10__endurecer_esquema_finanzas.sql` | Refuerza saldos/tickets, hace inmutable `wallet_ledger` y persiste marcas de corte de leaderboard |
| `V11__blindaje_wompi_y_ledger.sql` | Agrega `transactions.external_id` único para correlacionar transacciones Wompi y bloquea `TRUNCATE` en `wallet_ledger` |
| `V12__referencias_wompi_unicas.sql` | Hace única la referencia de depósitos Wompi. Si existen duplicados históricos, falla sin modificarlos para exigir conciliación antes del despliegue |
| `V13__cortes_leaderboard_recuperables.sql` | Persiste cortes/pagos idempotentes y vincula cada historial de jugador con su pago para recuperarse tras reinicios |
| `V14__inicio_partida_idempotente.sql` | Vincula la sala persistida con el match financiero e impide cobrar dos veces al reintentar el inicio |
| `V15__sesiones_revocables.sql` | Crea `user_sessions`, que vincula cada JTI de JWT a un usuario y una expiración para validar y revocar sesiones |
| `V16__recuperacion_segura_password.sql` | Crea `password_reset_tokens`, que persiste solo hashes SHA-256 de tokens de recuperación de un solo uso |

**Cómo se comporta según el estado de la BD** (`baselineOnMigrate=true`, `baselineVersion=0`):

| BD | Resultado |
|---|---|
| Vacía | Corre V1 y V2; crea todo |
| Con tablas pero sin `flyway_schema_history` (la de Render antes de A5) | Flyway la marca como versión 0 (baseline) y aplica V1 y V2 encima; V1 es idempotente, no cambia lo existente |
| Ya migrada | Aplica solo los scripts nuevos; si no hay, no hace nada |

**Reglas**
- **Toda tabla o columna nueva es un archivo nuevo** `V<n>__descripcion.sql` (el siguiente es `V18`). **Nunca renumerar ni renombrar un `V*` que ya pudo aplicarse en alguna BD** (aunque sea solo en la de Render o en la local): Flyway falla con «Detected applied migration not resolved locally» y el servidor no arranca. Nunca se agregan tablas en Java.
- **Nunca editar un `V*` ya aplicado:** Flyway guarda el checksum y `validateOnMigrate` hace fallar el arranque. Para corregir algo, una migración nueva.
- **Fail-fast:** si una migración falla, `Main` loguea el error y sale con código 1 (no se sirve tráfico con el esquema a medias). Cada script corre en su propia transacción en PostgreSQL: si falla se revierte entero.
- `cleanDisabled=true`: la app nunca puede ejecutar `flyway clean`.
- El panel `/admin-db` (`DbAdminService`) oculta y rechaza la tabla `flyway_schema_history`.
- La conexión de Flyway es la misma de Hikari (mismo usuario y SSL, sección 11): el usuario de la BD debe poder crear tablas y la tabla de historial.
- Para ver qué está aplicado: `SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank;`.

---

## 15. Capa de servicio: rutas delgadas (A3)

Una ruta HTTP hace solo tres cosas: **leer el request, llamar a un servicio y escribir la respuesta**. Las reglas de negocio, el SQL, las notificaciones y las firmas viven en `servicio/`. Los servicios no conocen Javalin (`Context`): reciben tipos simples (`int userId`, `String`, `JsonElement` para montos sin validar) y devuelven datos o lanzan `ServicioException`.

```
RutasX (servidor/)                        XService (servicio/)
  Peticion.entero/texto(body, ...)   →      valida reglas, ValidadorMonto, WalletService, SQL
  servicio.metodo(userId, datos...)  →      lanza ServicioException(código, mensaje) si algo falla
  ctx.json(resultado)                ←      devuelve JsonObject / lista / String de mensaje
                 ManejadorErrores: ServicioException → { "error": "..." } con ese código
```

| Ruta | Servicio | Qué quedó en la ruta |
|---|---|---|
| `RutasAuth` | `AuthService` | Emitir/borrar la cookie de sesión JWT (`AuthMiddleware`, S1), formato de la respuesta |
| `RutasFinanzas` | `FinanzasService` | Responder `OK`/`Error` en texto plano al webhook de Wompi |
| `RutasAdmin` | `AdminService` | Calcular la IP del cliente en `/check-ip` |
| `RutasSorteos` | `SorteoService` | Leer el userId de la sesión JWT (`Peticion.userIdDeSesion`) en las rutas de consulta |
| `RutasLeaderboard` | `LeaderboardService` | Nada más que parsear `periodo` / `userId` |
| `RutasMedia` | `MediaService` | Headers de la respuesta (`Content-Range`, `nosniff`, caché) |
| `RutasDbAdmin` | `DbAdminService` | Acceso al panel: clave `X-DB-Admin-Key`, bloqueo por IP, cabeceras de seguridad (S10) |
| `RutasComisiones` | `ComisionService` + `ValidadorMonto` | Ya era delgada (D4); no se tocó |

**Reglas**
- **Códigos de error:** `solicitudInvalida` (400), `noEncontrado` (404), `interno` (500). `respuestaConError` responde **HTTP 200** con `{error}`: es el contrato heredado de depósito manual y panel admin, el frontend actual lo espera; no usarlo en endpoints nuevos.
- **El servicio recibe `userId` como parámetro.** Hoy la ruta lo saca del body/cookie; cuando entre el JWT (S1/S2) solo cambia la ruta, no el servicio.
- **Un servicio nuevo** = clase en `servicio/` con sus dependencias por constructor, instanciada en `Main` (sección 7) y pasada a `RutasX.register(app, servicio)`. Nada de campos `static` con estado ni de `register(app, db, io, push, ...)`.
- **Notificar al usuario** (socket + push): `NotificadorUsuarios.notificarSaldo` / `emitirA`. Reacciones a algo que ya ocurrió (tickets, resultado de partida) van por el bus de eventos (sección 13).
- **Pools:** BCrypt va por `Ejecutores.enCpu` (dentro de `AuthService`); un servicio que llame a HTTP externo no debe hacerlo en un hilo de temporizador (sección de A8).
- Los jobs de `Main` revisan sorteos vencidos y cierres de leaderboard cada minuto. `LeaderboardService.verificarYCerrarPeriodos()` recupera primero los pagos pendientes y luego detecta cambios de día, semana, mes o año en hora de Colombia.
- `SorteoService.crear()` valida nombre y categoría en el servidor. `participar()` bloquea la fila del sorteo y comprueba `fecha_limite` con `clock_timestamp()` antes de descontar tickets. `eliminar()` bloquea el sorteo y devuelve los tickets y borra sus participaciones y el sorteo en una sola transacción; eliminar una segunda vez no vuelve a acreditar. La acumulación por partida bloquea la fila `user_tickets` del jugador mientras calcula y guarda el nuevo saldo/residuo.

**Pendiente / fuera de A3**
- `SocketHandler` (~50 KB) sigue mezclando protocolo de sockets con lógica de negocio (matchmaking, negociación, chat); es candidato a su propia tarea.
- La lógica de decisión del scheduler de `Main` (qué día se premia) sigue en `Main`.
- `/secret-admin/{username}` fue eliminada (S3), la identidad sale siempre del JWT (S2) y el webhook de Wompi verifica firma SHA-256 y monto (S7).

---

## 16. Sesión con JWT en cookie httpOnly (S1)

La sesión ya no es la cookie `userId` en texto plano (cualquiera podía escribir ahí el id de otro usuario). Ahora es un JWT firmado que solo el servidor puede emitir.

```
login correcto ── crea un JTI en user_sessions ── Set-Cookie: session=<JWT>; HttpOnly; SameSite; Secure
GET /api/session ── extiende expires_at y renueva el token conservando el JTI
POST /api/logout ── elimina el JTI ── desconecta sockets de esa sesión ── borra la cookie

cada request /api ── verifica firma/expiración y JTI activo en user_sessions
                         ├─ activo   → ctx.attribute("userId"), ctx.attribute("rol")
                         └─ revocado → sin identidad; se borra la cookie

cada evento autenticado de Socket.IO ── revalida el JTI antes de ejecutar su handler

ruta ── AuthMiddleware.getUserId(ctx) / Peticion.userIdDeSesion(ctx) (0 si no hay sesión)
```

| Pieza | Detalle |
|---|---|
| Token | HS256, `iss=ultimateclash`, `sub`=userId, claim `rol` (= `users.tipo_suscripcion`), `jti` aleatorio, `iat`, `exp`. Se verifican firma, emisor y expiración |
| Cookie | `session`, `HttpOnly` (el JS del navegador no la puede leer), `SameSite` = `COOKIE_SAMESITE` (default `Lax`), `Secure` = `COOKIE_SECURE` (default `true`; forzado si SameSite=None). Se arma a mano porque `jakarta.servlet.http.Cookie` no soporta SameSite |
| `GET /api/session` | 401 si no hay JWT/JTI activo (y borra la cookie). Si es válido, renueva la expiración y el rol actual de la BD conservando el JTI. Si la cuenta ya no existe, 401 y borra la cookie |
| `POST /api/logout` | Elimina el JTI de `user_sessions`, desconecta los sockets asociados, desasocia los tokens push indicados y borra las cookies. Otras sesiones/dispositivos del usuario siguen activos |
| `user_sessions` | Un registro por sesión iniciada, con `jti`, `user_id`, `expires_at` y `created_at`. Cada petición API y cada evento autenticado de Socket.IO comprueban que siga activo; los vencidos se limpian al iniciar una sesión nueva. La tabla queda oculta y protegida en el editor genérico de DB |
| Cookie legacy `userId` | **No se acepta nunca** (sería volver al agujero). Tras el deploy todos deben iniciar sesión una vez |

Los JWT anteriores a la migración V15 no tienen JTI persistido y se rechazan; los usuarios deben iniciar sesión una vez después del despliegue. Si la BD no puede validar una sesión, la autenticación falla cerrada con 503. Los JTI de una sesión se conservan durante la renovación para que logout invalide también cualquier copia anterior; cada dispositivo conserva su propia sesión. El restore completo borra las sesiones y enlaces de recuperación restaurados antes de reabrir tráfico para evitar reactivar credenciales temporales de la copia.

**Mensajes de autenticación y registro:** todo fallo de login responde `Correo o contraseña incorrectos`. Las consultas públicas de nombre/correo (`/api/check-username/*`, `/api/check-email/*`) validan solo el formato y no consultan si el dato existe; devuelven `valid`, no disponibilidad. El registro usa la misma respuesta HTTP genérica tanto si crea la cuenta como si el nombre, correo o tag ya está ocupado, y nunca inicia sesión automáticamente. `/api/verify-tag/*` valida contra la API pública de Clash Royale, sin revelar si ese tag está vinculado a una cuenta local.

**Recuperación de contraseña (V16):** `POST /api/password/forgot` siempre devuelve el mismo mensaje, tanto si el correo existe como si no. Para una cuenta válida, el servidor genera 32 bytes aleatorios, almacena solo el SHA-256 del token y envía por Brevo un enlace `APP_BASE_URL/auth#reset=<token>` con vencimiento configurable; el token se consume una sola vez. Hay rate limit por IP en ambas rutas y una pausa por cuenta entre envíos. `POST /api/password/reset` cambia el hash de contraseña, consume los enlaces pendientes y revoca todas las sesiones en una transacción; después se cierran los sockets del usuario. Se requiere `BREVO_API_KEY` y `BREVO_SENDER_EMAIL` ya usados por `CorreoServicio`; sin configuración el endpoint conserva la respuesta genérica, pero no puede entregar el correo. Los backups completos contienen los hashes de tokens temporales, pero el restore los elimina junto con las sesiones antes de reabrir tráfico.

**Contraseñas BCrypt:** el registro y la recuperación aceptan de 6 a 128 caracteres, con un máximo de **72 bytes UTF-8**, que es el límite de BCrypt. El login también rechaza contraseñas que exceden ese tamaño; una contraseña histórica más larga se debe restablecer por correo. El contenedor fija la codificación predeterminada de Java en UTF-8 para que BCrypt procese los mismos bytes que valida la aplicación.

| Variable | Default | Qué controla |
|---|---|---|
| `JWT_SECRET` | aleatorio en memoria | Clave HMAC (mínimo 32 caracteres). **Obligatoria en producción**: sin ella cada reinicio/deploy cierra todas las sesiones |
| `JWT_EXPIRATION_DAYS` | `30` | Vida del token. Con la renovación de `/api/session`, solo expira si el usuario no abre la app en ese tiempo |
| `COOKIE_SAMESITE` | `Lax` | `Lax` / `Strict` / `None` |
| `COOKIE_SECURE` | `true` | Poner `false` solo para probar por `http://` en una IP de red local (en `localhost` los navegadores aceptan `Secure`) |

**Límites conocidos / pendientes**
- **S2 (resuelto):** ninguna ruta de usuario lee `userId` ni `username` del body (`RutasFinanzas`, `RutasSorteos`, `RutasAuth.register-token`, `RutasChatbot`, `RutasSoporte` usan `Peticion.userIdDeSesion(ctx)`), y el handshake de Socket.IO extrae el `userId` de la cookie JWT con `AuthMiddleware.userIdDesdeCookieHeader(cookieHeader)` y lo fija en `SocketIOClient.authenticatedUserId`.
- **S3 (resuelto):** el claim `rol` puede quedar viejo hasta el siguiente `/api/session` o login, por eso las rutas de admin NO lo usan: `AdminMiddleware` consulta `users.tipo_suscripcion` en la BD en cada petición (sección 18).
- **App nativa / React en otro origen:** con `SameSite=Lax` el navegador no envía la cookie en `fetch` entre sitios (igual que antes con la cookie sin SameSite). Si la app Capacitor necesita la sesión, usar `COOKIE_SAMESITE=None` **y** cambiar CORS `anyHost()` + `allowCredentials` por una lista de orígenes permitidos; con `None` y `anyHost()` cualquier web podría hacer peticiones autenticadas.

**Cabeceras web y datos persistidos (S10):** `Main` aplica CSP global con `default-src 'self'`, sin scripts inline y con permisos explícitos para el widget de Wompi, fuentes de Google, videos YouTube y los orígenes configurados para API/Socket.IO. Los estilos inline se mantienen porque React usa atributos `style`. `RutasDbAdmin` tiene una política independiente: ese HTML heredado todavía usa scripts y manejadores inline, restringidos a recursos y conexiones del mismo origen. HSTS (`max-age=31536000`) se envía cuando `APP_BASE_URL` comienza por `https://`; no se agrega `includeSubDomains` hasta verificar todos los subdominios. En el navegador solo se conserva el tema en `localStorage`; `currentUser` se elimina al cargar y la identidad/saldo permanecen en memoria. En cada arranque se valida la cookie httpOnly con `/api/session` antes de mostrar las rutas privadas.

## Términos de Servicio y Política de Privacidad (roadmap 1.6)

- **Texto:** `frontend/public/legal/contenido-legal.json` (claves `terminos` y `privacidad`, cada una con `titulo`, `version`, `vigenteDesde`, `intro` y `secciones[{titulo, parrafos[]}]`; un párrafo es un texto o `{ "lista": [...] }`). Es la única fuente: el responsable, los contactos y las reglas de dinero se cambian ahí.
- **Frontend:** `DocumentoLegal` (renderiza un documento; sin `innerHTML`), `pages/Legal.jsx` (rutas públicas `/terminos` y `/privacidad`) y `AceptacionLegal` (checkbox del registro + visor en portal). La URL del JSON es relativa (`/legal/contenido-legal.json`): mismo origen en la web y dentro del paquete en Capacitor.
- **Backend:** `POST /api/register` exige `aceptaTerminos` (`Peticion.booleanoOpcional`; 400 si falta). `AuthService.registrar` guarda la constancia con `UsuarioDAO.registrarConAceptacionLegal` en una sola transacción. La respuesta de alta es genérica y no inicia sesión automáticamente (sección 16).
- **BD:** `legal_acceptances (id, user_id → users ON DELETE CASCADE, documento 'terminos'|'privacidad', version, aceptado_en)`, única por `(user_id, documento, version)` (migración `V4`).
- **Versiones:** constantes en `DocumentosLegales`. Al cambiar un texto hay que subir la versión en el JSON y en esa clase.
- **Legacy:** `public/index.html` tiene el checkbox (`name="aceptaTerminos"`); sus enlaces apuntan a los documentos del despliegue de producción.

---

## 17. Chatbot IA (roadmap parte 20)

Asistente que responde dudas de los usuarios sobre la plataforma. **No se entrena con un documento: la IA le hace preguntas al admin** y lo que éste responde es su única fuente. Cuando un usuario pregunta algo que no sabe, avisa al admin por correo y la respuesta del admin vuelve al usuario.

```
Usuario ── POST /api/chatbot/mensaje ── ChatbotService.responder
             ├─ límites: IP (RutasChatbot), usuario por minuto y por día (ChatbotService), llamadas simultáneas (ChatbotIaCliente)
             ├─ contexto: últimos N mensajes + base de conocimiento relevante (ChatbotConocimiento) + tono
             ├─ IA (Gemini/OpenAI) responde con la etiqueta [RESUELTA] o [SIN_RESPUESTA] al inicio (se quita antes de mostrar)
             └─ [SIN_RESPUESTA] → guarda la duda → emit chatbot.duda_sin_respuesta → correo al admin (si es nueva)

Admin ── pestaña «🤖 Chatbot» del panel (AdminChatbot.jsx)
             ├─ Entrenar: POST /api/admin/chatbot/entrenamiento/siguiente → la IA pregunta → el admin responde → base de conocimiento
             ├─ Dudas de usuarios: responder → emit chatbot.duda_respondida → historial + socket `chatbot_respuesta` + push a quienes preguntaron
             └─ Base de conocimiento: agregar a mano, editar, borrar; tono por defecto
```

| Pieza | Detalle |
|---|---|
| Conocimiento | `chatbot_conocimiento`: `origen` = `ia` (pregunta de entrenamiento), `usuario` (duda sin resolver) o `admin` (agregada a mano); `estado` = `pendiente` / `respondida` / `descartada`. Solo las `respondida` las ve la IA. Una duda repetida (mismo texto normalizado) suma `veces` y añade al usuario a `chatbot_duda_usuarios`, sin volver a enviar correo |
| Qué ve la IA | Con hasta 15 respuestas se le pasan todas; con más, las 8 con más palabras clave en común con la pregunta (y la anterior del usuario). Sin coincidencias, ninguna y la IA dirá que no sabe |
| Entrenamiento | `siguientePregunta`: si ya hay una pregunta de la IA sin responder se devuelve esa; si no, la IA genera una nueva (distinta de las ya hechas). Si la IA no está configurada o falla, se usa una pregunta base de `ChatbotService.PREGUNTAS_SEMILLA` |
| Tono | `formal`, `informal` o `adaptativo` (imita cómo escribe el usuario). El admin fija el tono por defecto (`chatbot_config.tono_defecto`, inicial `adaptativo`); cada usuario puede elegir el suyo (`chatbot_preferencias`) o volver al del admin con `defecto` |
| Permisos | La identidad sale de la sesión JWT (`Peticion.userIdDeSesion`), nunca del body. Las rutas de admin exigen `users.tipo_suscripcion = 'admin'` consultado en la BD en cada llamada (401 sin sesión, 403 si no es admin) |
| Correo | `ChatbotListener` → `CorreoServicio`: a `CHATBOT_ADMIN_EMAIL` o, si está vacía, al remitente de Brevo. Solo duda nueva, máximo `CHATBOT_MAX_CORREOS_HORA` por hora; el correo lleva nombre de usuario e id, nunca email ni teléfono. Lo que pase del tope sigue visible en el panel |

| Variable | Default | Qué controla |
|---|---|---|
| `CHATBOT_API_KEY` | vacía | Clave del proveedor. **Sin ella el chatbot queda desactivado** (el resto de la app no cambia; el admin ve «IA sin configurar») |
| `CHATBOT_PROVIDER` | `gemini` | `gemini` u `openai` |
| `CHATBOT_MODEL` | `gemini-flash-latest` (Gemini) / obligatorio con OpenAI | Los nombres de modelo se retiran seguido: si la IA falla con 404 en el log, revisar este valor |
| `CHATBOT_ADMIN_EMAIL` | remitente de Brevo | Correo al que llegan las dudas |
| `CHATBOT_MAX_MENSAJES_POR_MIN` / `_DIA` | 6 / 100 | Límites por usuario |
| `RATE_LIMIT_CHATBOT_PER_MIN` | 10 | Límite por IP de `POST /api/chatbot/mensaje` |
| `CHATBOT_MAX_CONCURRENTES` | 8 | Llamadas a la IA a la vez; las demás esperan 3 s por un cupo y luego se rechazan con 503 |
| `CHATBOT_MAX_CORREOS_HORA` | 10 | Tope de correos al admin por hora |
| `CHATBOT_TIMEOUT_SEGUNDOS` | 25 | Tiempo máximo de una llamada a la IA (5 - 120) |
| `CHATBOT_CONTEXTO_MENSAJES` | 8 | Mensajes previos que se le envían a la IA (0 - 30) |
| `CHATBOT_MAX_TOKENS` | 1024 | Tope de la respuesta (64 - 8192) |

**Reglas y límites conocidos**
- La IA solo puede responder con la base de conocimiento; las instrucciones le prohíben inventar datos, pedir contraseñas o hablar de «apuestas» (se dice «monto» y «torneo», F5). La base la escribe solo el admin, así que no hay entrada de usuarios en ese bloque del prompt.
- La llamada a la IA bloquea el hilo HTTP hasta `CHATBOT_TIMEOUT_SEGUNDOS`; el tope de simultáneas evita agotar el pool de Jetty.
- Ante 500, 502, 503 o 504 del proveedor (p. ej. Gemini «high demand») `ChatbotIaCliente` reintenta hasta 3 intentos en total, con 1 s y 2 s de espera; el 429 y los 4xx no se reintentan.
- La búsqueda en la base es por palabras clave, no semántica: suficiente para decenas o cientos de respuestas cortas. Si crece mucho, sustituir `ChatbotConocimiento.relevantes` (embeddings).
- El frontend legacy (`public/`) no tiene el chatbot: solo la app React (`/chatbot` y la pestaña del panel admin).
- `RateLimitMiddleware` no clasifica la ruta del chatbot (ese archivo no se pudo editar en esta tarea); el límite por IP vive en `RutasChatbot`. Si se prefiere centralizarlo, mover `limitarPorIp` al middleware como cubo `IA`.

---

## 18. Autorización de admin (S3)

`servidor/AdminMiddleware` es un `app.before` que corta la petición antes de llegar a la ruta si quien la hace no es admin. Se registra en `Main` después de `AuthMiddleware` (necesita el `userId` de la sesión) y antes de las rutas. Las rutas protegidas ya no verifican nada por su cuenta.

```
petición ── RateLimitMiddleware ── AuthMiddleware (JWT → userId) ── AdminMiddleware ── ruta
                                                           ├─ sin userId            → 401 {"error"}
                                                           ├─ AdminService.esAdmin  → SELECT tipo_suscripcion FROM users WHERE id = ?
                                                           └─ no es admin           → 403 {"error"} (+ warn en el log)
```

| Patrón protegido | Qué cubre |
|---|---|
| `/api/admin/*` | `RutasAdmin` (transacciones, disputas, stats, procesar, resolver), `RutasSorteos` (`/api/admin/raffle*`, `/api/admin/raffles`) y `RutasChatbot` (`/api/admin/chatbot/*`, que además conserva su `exigirAdmin`) |
| `/api/deposit` | Depósito manual (acredita saldo a cualquier `userId`) |
| `/admin-fix-status/*` | Resetear el estado de un jugador (el segmento `{adminUser}` de la URL se ignora) |

- **El rol sale de la BD, no del JWT.** El claim `rol` se fija al iniciar sesión y puede quedar viejo hasta 30 días: con él, un admin degradado seguiría entrando y uno recién promovido no. El JWT solo identifica al usuario. Cuesta una consulta por petición admin. Si la BD falla, se trata como no-admin.
- **`OPTIONS` (preflight CORS) pasa sin chequeo:** no lleva cookies ni ejecuta rutas.
- **Una ruta admin nueva** debe colgar de `/api/admin/` para quedar protegida sola. Si por compatibilidad no puede, añadir su patrón a `RUTAS_ADMIN` en `AdminMiddleware`.
- **No existe ruta para crear admins.** `/secret-admin/{username}` se eliminó (cualquiera podía hacerse admin). Para promover a alguien: `/admin-db` → tabla `users` → `tipo_suscripcion = 'admin'` (clave `DB_ADMIN_SECRET`, sección 11). Efecto inmediato en el backend; el frontend lo ve en el siguiente `/api/session`.
- **Frontend:** `Admin.jsx` llama a `/api/admin/*` con `llamarApi` (envía la cookie) y trata 401/403 como error. El frontend legacy `public/js/admin.js` no maneja esas respuestas.

**Pendiente / fuera de S3**
- `GET /admin-fix-status/...` modifica datos con GET (riesgo CSRF con `SameSite=Lax` desde un enlace externo): pasarlo a POST.
- `GET /check-ip` sigue público (muestra la IP pública del servidor).
- Las acciones de admin por Socket.IO (p. ej. anuncios) no pasan por este middleware; dependen de S2 (autenticar el socket).
- `RateLimitMiddleware` todavía menciona `/secret-admin/` (ahora 404; el límite solo frena el sondeo).

---

## 19. Soporte: "Hablar con el admin" (roadmap 24.5)

Sistema de tickets. El usuario abre un formulario desde su menú (`/soporte`), al admin le llega un correo, el admin responde desde la pestaña «🎫 Soporte» de su panel y el usuario recibe la respuesta por correo, por aviso en vivo/push y en su propio panel.

```
Usuario ── POST /api/soporte/tickets ── SoporteService.crear
             ├─ valida categoría y textos (ChatSanitizer), topes por usuario (abiertos y por día) → 429 si se superan
             └─ INSERT en support_tickets → emit soporte.ticket_creado → SoporteListener: correo al admin (tope por hora)

Admin ── POST /api/admin/soporte/tickets/{id}/responder ── SoporteService.responder
             └─ UPDATE ... WHERE estado = 'abierto' (atómico) → emit soporte.ticket_respondido → SoporteListener:
                  socket `soporte_respuesta` + push + correo a users.email del usuario
```

| Endpoint | Quién | Qué hace |
|---|---|---|
| `POST /api/soporte/tickets` `{categoria, asunto, mensaje}` | usuario (sesión JWT) | Abre un ticket. Categorías: `pregunta`, `sugerencia`, `bug`, `cuenta`, `otro` |
| `GET /api/soporte/tickets` | usuario | Sus tickets (máx. 50, recientes primero) con la respuesta si ya la hay. Nunca ve los de otros |
| `GET /api/admin/soporte/tickets?estado=abierto\|resuelto\|todos` | admin | Bandeja (default `abierto`, los más viejos primero). Lleva `user_id` y `username`, nunca email ni teléfono |
| `POST /api/admin/soporte/tickets/{id}/responder` `{respuesta}` | admin | Responde y deja el ticket `resuelto`. Un ticket ya resuelto responde 400 |

| Variable | Default | Qué controla |
|---|---|---|
| `SOPORTE_ADMIN_EMAIL` | `CHATBOT_ADMIN_EMAIL` → remitente de Brevo | Correo al que llegan los tickets nuevos |
| `SOPORTE_MAX_ABIERTOS` | 5 (1 - 100) | Tickets sin responder que un usuario puede tener a la vez |
| `SOPORTE_MAX_TICKETS_DIA` | 10 (1 - 1000) | Tickets que un usuario puede abrir en 24 h |
| `SOPORTE_MAX_CORREOS_HORA` | 10 (1 - 1000) | Tope de correos al admin por hora (lo que pase se ve solo en el panel) |

**Reglas y límites conocidos**
- Los dos topes por usuario se aplican dentro del propio `INSERT` (subconsultas en el `WHERE`), lo que cierra casi toda la ventana entre comprobar y guardar; en PostgreSQL con `READ COMMITTED` dos peticiones exactamente simultáneas aún podrían pasar justas, así que el tope es de abuso, no un límite exacto. El de correos por hora es en memoria (se reinicia con el servidor).
- Cada ticket admite **una sola respuesta** y el usuario no puede replicar: una consulta nueva es un ticket nuevo. Si se necesita conversación, habría que añadir una tabla de mensajes por ticket.
- El correo al usuario va a `users.email` sin verificar que sea suyo (la verificación de email es otra tarea del roadmap). Si no hay correo válido o Brevo no está configurado, el usuario igual ve la respuesta en su panel.
- El aviso en vivo usa `NotificadorUsuarios.emitirA` (directo a los sockets del usuario, sin rooms ni namespaces), igual que el chatbot; no depende de A1.
- Frontend: solo la app React (`pages/Soporte.jsx`, `pages/AdminSoporte.jsx`, `pages/soporteComun.js`); el frontend legacy de `public/` no lo tiene. El texto de usuarios y admin se pinta con React, nunca con `innerHTML`.

---

## 20. Evidencias de pago: canal «Pagos» del chat (roadmap 7)

Cuando el admin paga un retiro por fuera (Nequi, etc.) y lo aprueba, sube la imagen del comprobante. Queda publicada en la pestaña «🧾 Pagos» de `Chat.jsx`, que ven todos los usuarios (solo lectura para ellos). Solo se publican **retiros**: en una recarga quien paga es el usuario, no el admin.

```
Admin ── POST /api/admin/pagos/evidencias {transaccionId, imagen, nota?} ── EvidenciaPagoService.publicar
             ├─ exige admin (AdminMiddleware + tipo_suscripcion en el servicio) y valida la imagen (data URI png/jpeg/gif/webp, tope de largo)
             ├─ INSERT ... SELECT desde transactions (tipo 'retiro', estado 'completado'; usuario, monto y método salen de la BD) ON CONFLICT DO NOTHING
             └─ emit pagos.evidencia_publicada → EvidenciaPagoListener: socket `mensaje_chat` (canal 'pagos') a todos + push a quien recibió el pago

Usuario ── GET /api/pagos/evidencias ── historial (las últimas, con forma de mensaje de chat); se pide al abrir la pestaña
```

| Endpoint | Quién | Qué hace |
|---|---|---|
| `GET /api/pagos/evidencias` | usuario (sesión JWT) | Últimas `PAGOS_HISTORIAL_LIMITE` evidencias, de vieja a nueva, como mensajes `{canal:'pagos', usuario (admin), texto (imagen), tipo:'imagen', fecha, evidencia:{id, transaccionId, para, monto, metodo, nota}}` |
| `GET /api/admin/pagos/retiros` | admin | Retiros aprobados que todavía no tienen evidencia (máx. 50) |
| `POST /api/admin/pagos/evidencias` `{transaccionId, imagen, nota?}` | admin | Publica la evidencia. 400 si el retiro no existe, no es retiro, no está aprobado, ya tiene evidencia o la imagen no es válida |

| Variable | Default | Qué controla |
|---|---|---|
| `PAGOS_MAX_IMAGEN_CHARS` | 800000 (50000 - 900000) | Largo máximo del data URI de la imagen. No pasar de ~900000: Javalin rechaza cuerpos de más de 1 MB por defecto |
| `PAGOS_HISTORIAL_LIMITE` | 20 (1 - 100) | Cuántas evidencias carga el canal al abrirlo |

**Decisiones y límites conocidos**
- **Se publica por HTTP, no por Socket.IO.** El socket todavía no autentica al emisor (S2 pendiente: el anuncio de admin solo se oculta en el frontend), y un comprobante falso es un riesgo de fraude. Por eso `pagos` NO está en `CANALES_PUBLICOS` de `ChatSanitizer`: ningún cliente puede escribir en ese canal por socket.
- **Imagen en la BD como data URI base64** (igual que las imágenes de anuncios); no usa `media_files`, que es solo para videos. El historial NO viaja en la conexión del socket (serían hasta 20 imágenes por cada conexión): se pide por HTTP al abrir la pestaña y lo nuevo llega en vivo. El frontend reduce la imagen (máx. 1280 px, JPEG, ≤ 700000 caracteres) antes de enviarla.
- **Una evidencia por retiro** (`UNIQUE (transaction_id)`). No se puede editar ni borrar desde la app; si hay que quitar una, se hace desde `/admin-db`.
- **Privacidad:** el canal es público para los usuarios con sesión y muestra nombre de usuario, monto y método, además de la imagen. El admin debe tapar números de cuenta/celular y documentos antes de subirla (el formulario lo avisa); el servidor no puede detectarlo.
- El push solo va a quien recibió el pago. Los demás usuarios ven la evidencia en vivo si están conectados, o al abrir la pestaña.
- Frontend: solo la app React (`pages/Chat.jsx`, `Chat.css`, `store/useAppStore.js`); el frontend legacy de `public/` no lo tiene.

---

## 21. Chat de voz con Discord (roadmap 84)

Chat de voz **opcional** entre los dos jugadores de una partida, sobre Discord. Cada jugador necesita su propia cuenta de Discord (gratis); quien no la vincule juega igual, sin voz. Sin las variables `DISCORD_*` la función queda desactivada y el resto de la app no cambia.

```
Usuario ── POST /api/voz/discord/vincular ── VozDiscordService.iniciarVinculacion → {url de Discord con `state` firmado}
Discord ── GET /api/voz/discord/callback?code&state ── completarVinculacion: canjea el código, lee la cuenta, guarda discord_links,
             mete al usuario al servidor (guilds.join) y redirige a /match?discord=<resultado>   (el token OAuth no se guarda)

SocketHandler ── emit match.started ── VozListener ──(pool IO)── prepararCanal: si algún jugador vinculó Discord crea el canal privado
                                                                  `partida-<id>` y da acceso a quien esté en el servidor
Usuario ── GET /api/voz/mi-canal ── miCanal: crea el canal si falta, da acceso a quien lo pide y devuelve https://discord.com/channels/<servidor>/<canal>
SocketHandler / AdminService ── emit match.finished ── VozListener ──(pool IO)── cerrarCanal: borra el canal
Main ── cada minuto ──(pool IO)── limpiarVencidos: borra canales de partidas que ya no están `en_curso` o de más de DISCORD_VOICE_MAX_MINUTOS
```

| Tabla (`V8`) | Qué guarda |
|---|---|
| `discord_links` | `user_id` (PK) → `discord_id` (UNIQUE: una cuenta de Discord = un usuario) y `discord_username` |
| `voice_channels` | Un canal por partida (`match_id` UNIQUE): jugadores, `discord_channel_id` y `estado` activo/cerrado |

| Endpoint | Qué hace |
|---|---|
| `GET /api/voz/estado` | `{disponible, vinculado, discordUsername?, servidorUrl?}` |
| `POST /api/voz/discord/vincular` | `{url}` de autorización de Discord |
| `GET /api/voz/discord/callback` | Vuelta de Discord; redirige a `/match?discord=ok\|cancelado\|discord_en_uso\|estado_invalido\|no_disponible\|error` |
| `DELETE /api/voz/discord` | Desvincula |
| `GET /api/voz/mi-canal` | `{estado: listo\|sin_vincular\|sin_partida\|falta_servidor\|caducado, mensaje, url?, servidorUrl?}` |

| Variable | Default | Qué controla |
|---|---|---|
| `DISCORD_CLIENT_ID`, `DISCORD_CLIENT_SECRET` | — | Aplicación OAuth2 de Discord (obligatorias) |
| `DISCORD_BOT_TOKEN` | — | Token del bot que crea y borra canales (obligatoria) |
| `DISCORD_GUILD_ID` | — | ID del servidor de Discord de la plataforma (obligatoria) |
| `DISCORD_REDIRECT_URI` | `APP_BASE_URL` + `/api/voz/discord/callback` | Debe estar registrada igual en Discord Developer Portal → OAuth2 → Redirects |
| `DISCORD_VOICE_CATEGORY_ID` | vacío | Categoría del servidor donde se crean los canales |
| `DISCORD_INVITE_URL` | vacío | Invitación (https) que se le muestra a quien aún no está en el servidor |
| `DISCORD_VOICE_MAX_MINUTOS` | 90 (5 - 1440) | Vida máxima de un canal aunque la partida siga abierta |

Permisos del bot en el servidor: *Gestionar canales*, *Gestionar roles*, *Ver canales*, *Conectar* y *Crear invitación instantánea* (necesaria para `guilds.join`). No usa el gateway ni «Privileged Intents».

**Decisiones y límites conocidos**
- **REST desde Java, sin Discord.js ni proceso aparte** (`DiscordApiCliente`): crear y borrar canales solo requiere el token del bot por HTTPS. Un solo deploy en Render y la lógica sigue el patrón del proyecto (servicio + listener del bus + migración Flyway). Si en el futuro se quisieran comandos o presencia en Discord, ahí sí haría falta un bot conectado.
- **Canal privado por permisos**: @everyone no puede ver ni conectarse; cada jugador recibe un permiso propio (ver, conectar, hablar). Discord solo deja dar permisos a miembros del servidor: por eso se les mete al vincular y, si aún no están, `mi-canal` responde `falta_servidor` con la invitación.
- **El `state` de OAuth es sin estado en el servidor** (HMAC-SHA256 con una clave derivada de `JWT_SECRET`, 10 min de vigencia): no hay mapas en memoria (A2). Si `JWT_SECRET` no está definido y el servidor se reinicia en medio de una vinculación, el usuario solo debe repetirla.
- **Un canal por partida, nunca se reabre**: si el canal se cerró (por tiempo máximo) y la partida sigue, `mi-canal` responde `caducado`.
- **Privacidad**: el canal se llama `partida-<id>`; al rival nunca se le muestra el usuario de Discord del otro. Solo se guarda el id y el nombre de Discord de quien vincula; nunca su token.
- Los errores de Discord no rompen la partida: `emit` no lanza, el trabajo corre en el pool de IO y la limpieza de cada minuto reintenta los borrados que fallaron.
- Frontend: solo la app React (`components/VozDiscord.jsx`, usado en `pages/Match.jsx`); el frontend legacy de `public/` no lo tiene.

---

## 22. Normalización de identidades y propiedad del Player Tag

La migración `V17__unicidad_identidades_normalizadas.sql` refuerza el registro con índices únicos funcionales sobre `LOWER(BTRIM(username))`, `LOWER(BTRIM(email))`, Player Tag sin `#` y en mayúsculas, y celular sin separadores. Para celulares colombianos, el número local de 10 dígitos que empieza en 3 se iguala a su forma con prefijo `57`; una forma internacional `00 57...` también queda en esa forma. La validación de registro y la consulta del DAO aplican las mismas reglas. Los duplicados preexistentes provocan un error explícito antes de crear índices: deben resolverse manualmente antes de desplegar V17, sin borrar ni reasignar cuentas de forma automática.

La consulta `GET /api/verify-tag/{tag}` solo confirma que Clash Royale reconoce el tag y devuelve información pública. Para terminar el registro, `AuthService` pide un token generado dentro de los ajustes del juego y llama a `POST /v1/players/{tag}/verifytoken` mediante `ClashApiServicio`. El token del jugador solo vive durante la petición, no se almacena ni se incluye en logs. El endpoint está restringido por Supercell a API keys autorizadas; un 403 hace fallar el registro con 503, nunca se acepta un tag sin verificar. La autorización de la API key de cada entorno debe comprobarse por separado.

Los formularios React y legacy de `public/` envían el token solo al registrar y limpian el campo después de la petición. La Política de Privacidad (versión 1.1) declara el envío temporal del token a Supercell y que la plataforma no lo conserva. La FAQ indica que cada celular solo puede usarse en una cuenta.
