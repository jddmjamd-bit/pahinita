# ARCHITECTURE.md — UltimateClash (TorneosFlash)

> Documento de referencia para que cualquier modelo o desarrollador entienda el sistema.
> Última actualización: 2026-10-10

---

## 1. Stack actual

| Capa | Tecnología | Notas |
|------|-----------|-------|
| **Backend** | Java 17, Javalin 5 | Servidor HTTP + middleware |
| **WebSocket** | Adaptador custom (`socketio/`) | Emula Socket.IO sobre WS nativo de Javalin. **Migrar a netty-socketio** (pendiente) |
| **BD** | PostgreSQL (Render) | Pool: HikariCP, max 20 conexiones. Enlace de acceso endurecido (sección 11) |
| **ORM** | Ninguno | SQL directo con `PreparedStatement` vía `GenericDAO` |
| **Migraciones BD** | Flyway 10 | Scripts versionados en `src/main/resources/db/migration/`; se aplican al arrancar (`ConexionDB.migrar()`). Ver sección 14 |
| **Auth** | Cookie `userId` en texto plano | ⚠️ **Inseguro**. Migrar a JWT httpOnly (pendiente) |
| **Frontend** | Vanilla JS (`app.js`, 120 KB) | SPA monolítica sin framework. **Migrar a React + Vite** (pendiente, en progreso bajo `frontend/`) |
| **Mobile App** | Capacitor | Genera builds nativos para Android e iOS encapsulando la app React. Gestiona Splash Screen y permisos |
| **Pagos** | Wompi (tarjeta), Nequi (manual) | Webhook Wompi no verifica firma |
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
│   │   ├── hooks/useCapacitor.js    # Inicializa Capacitor, permisos y Push Notifications
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
│   │   └── Eventos.java             # Catálogo: nombres de eventos (`match.finished`) y sus datos (`PartidaFinalizada`)
│   ├── servicio/
│   │   ├── ClashApiServicio.java    # Integración con API de Clash Royale
│   │   ├── ChatSanitizer.java       # Validación/limpieza de mensajes de chat y usernames (anti-XSS)
│   │   ├── CorreoServicio.java      # Envío de emails vía Brevo
│   │   ├── NotificacionPushServicio.java # Push FCM
│   │   ├── RateLimiter.java         # Contador de solicitudes por clave, ventana deslizante de 1 min (lógica pura, sin HTTP). Ver sección 10
│   │   ├── WalletService.java       # Todo movimiento de dinero (transacciones atómicas, BigDecimal)
│   │   ├── ComisionService.java     # Política de comisiones: % escalonado + reparto por categoría. Ver sección 9
│   │   ├── UsuarioVista.java        # Lista blanca de columnas de `users` que pueden salir al cliente (sesión vs. rival). Nunca `SELECT *` sobre users hacia el frontend
│   │   ├── ValidadorMonto.java      # Valida montos del cliente (número, > 0, entero, rango por tipo). Ver sección 8
│   │   ├── TicketsPartidaListener.java   # Listener de `match.finished`: tickets de sorteo + `tickets_ganados` a cada jugador (A4)
│   │   └── ResultadoPartidaListener.java # Listener de `match.finished`: saldo del ganador + `resultado_api` + push (A4)
│   ├── servidor/                    # Handlers HTTP (rutas REST)
│   │   ├── RateLimitMiddleware.java # Rate limiting por IP (S8): clasifica cada request y responde 429. Ver sección 10
│   │   ├── RutasAuth.java           # Login, registro, sesión
│   │   ├── RutasAdmin.java          # Operaciones admin (aprobar retiros, etc.)
│   │   ├── RutasDbAdmin.java        # CRUD admin sobre BD
│   │   ├── RutasFinanzas.java       # Depósitos, retiros, Wompi webhook
│   │   ├── RutasLeaderboard.java    # Rankings diario/semanal/mensual/anual
│   │   ├── RutasComisiones.java     # GET /api/comisiones/simular y /distribucion (solo lectura). Ver sección 9
│   │   ├── RutasMedia.java          # Upload/descarga de videos
│   │   └── RutasSorteos.java        # CRUD sorteos y participaciones
│   └── socketio/                    # Adaptador custom Socket.IO
│       ├── SocketIOServer.java      # Parseo de paquetes Engine.IO/Socket.IO
│       └── SocketIOClient.java      # Wrapper del cliente WS
├── src/main/resources/
│   └── db/migration/                # Migraciones Flyway: V1__esquema_base.sql, V2__migrar_datos_antiguos.sql (A5)
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
| `matches` | Historial de partidas | `jugador1`, `jugador2`, `monto`, `ganador`, `estado` |
| `transactions` | Depósitos y retiros | `usuario_id`, `tipo`, `metodo`, `monto`, `referencia`, `estado` |
| `admin_wallet` | Bóveda de comisiones del admin | `monto`, `razon`, `categoria` (sorteos, misiones, etc.) |
| `user_tokens` | Tokens FCM por usuario | `user_id`, `fcm_token` |
| `user_tickets` | Tickets de sorteo acumulados | `user_id`, `cantidad`, `acumulado` |
| `raffles` | Sorteos activos/completados | `nombre`, `categoria`, `precio`, `tickets_necesarios`, `ganador_id` |
| `raffle_entries` | Participaciones en sorteos | `raffle_id`, `user_id`, `tickets_asignados` |
| `raffle_votes` | Votos de categoría de sorteo | `user_id`, `categoria` |
| `leaderboard_history` | Historial de rankings | `user_id`, `periodo`, `victorias`, `posicion`, `premio` |
| `leaderboard_pools` | Acumulado de premios del ranking | `dia`, `semana`, `mes`, `ano` |
| `media_files` | Videos subidos (como BYTEA) | `filename`, `content_type`, `data` |

> [!WARNING]
> No hay tabla de ledger (auditoría de movimientos de dinero). Los saldos se modifican con `UPDATE users SET saldo` directo. Crear tabla `wallet_ledger` es prioridad.

---

## 4. Flujo de una partida

```mermaid
sequenceDiagram
    participant J1 as Jugador 1
    participant S as Servidor (SocketHandler)
    participant J2 as Jugador 2

    J1->>S: buscar_partida (monto, modo)
    S->>S: Añadir a cola en memoria (ConcurrentHashMap)
    Note over S: Matchmaking: primer rival con mismo monto y modo

    S->>J1: rival_encontrado
    S->>J2: rival_encontrado

    J1->>S: reportar_resultado (ganador: J1)
    J2->>S: reportar_resultado (ganador: J1)

    alt Ambos coinciden
        S->>S: Liquidar: saldo ganador += monto*2 - comisión
        S->>S: Registrar match en BD
        S->>J1: resultado_final
        S->>J2: resultado_final
    else Disputa
        S->>S: Pedir evidencia (screenshots)
        S->>S: Admin resuelve manualmente
    end
```

> [!CAUTION]
> **Todo el estado de partidas activas vive en `ConcurrentHashMap` en memoria.** Si el servidor se reinicia, se pierde todo. Persistir en BD es prioridad.

---

## 5. Flujo de dinero

```
Depósito:
  Wompi (tarjeta) → webhook → RutasFinanzas → UPDATE users SET saldo += monto
  Nequi (manual)  → admin aprueba → UPDATE users SET saldo += monto

Torneo (monto):
  1. Se descuenta al buscar partida: UPDATE users SET saldo -= monto WHERE saldo >= monto (WalletService.realizarTorneo)
  2. Al liquidar: ganador recibe (monto * 2 - comisión)
  3. Comisión se distribuye en admin_wallet por categoría (25% ganancia, 20% sorteos, 15% leaderboard, 15% devolución, 10% misiones, 10% referidos, 5% logros). Esos % son sobre la comisión y viven solo en `ComisionService` (sección 9)

Retiro:
  Usuario solicita → admin aprueba manualmente → UPDATE users SET saldo -= monto
```

> [!WARNING]
> - No hay transacciones SQL atómicas (si falla a mitad, el saldo queda inconsistente)
> - `saldo` es `NUMERIC` en BD pero se lee como `double` en Java → errores de precisión
> - No hay tabla ledger que registre cada movimiento individual

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

## 7. Seguridad: XSS en chat (S5)

Defensa en dos capas; ninguna confía en la otra.

| Capa | Dónde | Qué hace |
|------|-------|----------|
| **Render (frontend)** | `public/app.js` (`agregarBurbuja`, `convertirLinks`, `abrirMediaModal`), `frontend/src/pages/Chat.jsx` | El contenido del usuario entra solo por `textContent` / nodos DOM / JSX. Los links se crean con `createElement('a')` (solo `http(s)`, `rel="noopener noreferrer"`). Las plantillas grandes que aún usan `innerHTML` pasan los datos del usuario por `esc()`. Los `src` de imagen/video se validan con `resolverMedia()`. |
| **Servidor** | `servicio/ChatSanitizer.java`, usado por `SocketHandler` (`mensaje_chat`, `mensaje_privado`) y `RutasAuth` (registro) | Valida canal (`general`/`anuncios`/`clash`), tipo (`texto`/`imagen`/`video`), quita etiquetas HTML y caracteres de control, limita longitud (500 texto, 50 usuario) y solo reenvía campos permitidos. Imagen = data URI `png/jpeg/gif/webp` (sin SVG) o `/api/media/{id}`; video = `/api/media/{id}`; multimedia solo en `anuncios`. |

- El servidor **no** convierte a entidades HTML (`&lt;`): se guarda texto plano y cada frontend lo escapa al pintar, así no se ven entidades dobles.
- `GET /api/media/{id}` responde con `X-Content-Type-Options: nosniff`.
- Los usernames nuevos (3–30 caracteres) no pueden llevar `<`, `>`, `"`, `'`, `&`, el acento grave ni caracteres de control.
- Límite conocido: `usuario` sigue llegando del cliente (se limpia pero no se verifica contra la sesión); se resuelve en S2 (identidad desde JWT en el handshake del socket).

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
- Fuera de alcance de D5 (siguen pendientes): `double` en la comisión de Wompi y en `match.monto` (D1), reembolso atómico si falla el cobro del segundo jugador (D2), `/api/deposit` sin chequeo de admin (S3).

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

Cada jugador se atribuye la mitad de cada categoría (`Desglose.porJugador()`, columnas `users.gen_*` y `ganancia_generada`); de ahí salen también los tickets de sorteo (`RutasSorteos.acumularTicketsPorPartida`).

| Endpoint | Respuesta |
|---|---|
| `GET /api/comisiones/simular?monto=5000` | `{ success, monto, pozo, porcentajeComision, comision, premio }`. El monto se valida con `ValidadorMonto.torneo()` (400 si es inválido). Lo usa `Match.jsx` para "Si ganas recibes". |
| `GET /api/comisiones/distribucion` | `{ success, distribucion: { sorteos: 20, misiones: 10, ... } }`. Lo usa `Admin.jsx` para los títulos del desglose. |

- Para cambiar la política se edita **solo** `ComisionService` (constantes y enum). El panel admin y `Match.jsx` se actualizan solos.
- Pendiente: el frontend legacy de `public/` (`app.js`, `public/js/*`) no se tocó en D4 y podría conservar su propia copia de la fórmula (no verificado); muere con la migración a React. La tarifa de pasarela Wompi (`RutasFinanzas`, `/ 0.964 + 840`) es otro cobro y sigue con `double` (D1).

---

## 10. Rate limiting (S8)

Middleware global (`servidor/RateLimitMiddleware`, registrado en `Main` antes de rutas y sockets) que cuenta requests **por IP** con ventana deslizante de 1 minuto. El conteo vive en `servicio/RateLimiter` (en memoria: se reinicia con el servidor). Al pasarse del límite responde **429** con `{ "error": "Demasiadas solicitudes...", "retryAfter": N }` y el header `Retry-After`; las respuestas normales llevan `X-RateLimit-Limit` / `X-RateLimit-Remaining` del cubo más estricto que les aplica.

| Cubo | Límite (env) | Qué rutas |
|------|--------------|-----------|
| General | `RATE_LIMIT_GENERAL_PER_MIN` = 60 | Todo lo que empiece por `/api/`, `/admin-db/`, `/secret-admin/`, `/admin-fix-status/` y `/check-ip` |
| Financiero | `RATE_LIMIT_FINANCIAL_PER_MIN` = 10 | `/api/deposit`, `/api/transaction/create`, `/api/transaction/withdraw`, `/api/wompi/init`, `/api/admin/transaction/process`, `/api/admin/resolve-dispute`. Cuentan aquí **y** en el general |
| Media | `RATE_LIMIT_MEDIA_PER_MIN` = 300 | `GET /api/media/*` (los videos hacen muchas peticiones Range; el chat carga varias imágenes a la vez) |

- **Exentos:** `OPTIONS` (preflight CORS), archivos estáticos del frontend, WebSocket (Socket.IO) y `POST /api/wompi/webhook` (lo llama Wompi, no un usuario; limitarlo podría perder pagos reales. Su defensa es verificar la firma, tarea S7).
- **IP del cliente:** detrás de Render `ctx.ip()` es la IP del proxy. Se lee `X-Forwarded-For` contando desde la **derecha** con `RATE_LIMIT_PROXY_HOPS` (default 1): lo que está a la izquierda lo escribe el cliente y es falsificable. `0` ignora el header. Si Render/Cloudflare añaden más de una entrada y varios usuarios caen en la misma clave, subir a 2 (se comprueba con `/check-ip`).
- **Apagar (solo para pruebas locales):** `RATE_LIMIT_ENABLED=false`.
- **CORS en la 429:** el middleware corre antes que el plugin CORS de Javalin y al rechazar se saltan los handlers restantes, así que `RateLimitMiddleware` añade a mano `Access-Control-Allow-Origin` (refleja el `Origin`), `Allow-Credentials` y `Expose-Headers: Retry-After, X-RateLimit-*`. Sin eso la app React/Capacitor (otro origen que el backend) no podría leer el mensaje de la 429.
- **Clientes (frontend):** `Auth.jsx` muestra "Demasiadas solicitudes" en las validaciones de usuario/correo/tag en vez de marcarlos como ocupados; `Match.jsx` consulta la ganancia con debounce de 400 ms; `Sorteos.jsx` recarga solo `/api/raffle/offers` tras participar (2 peticiones por clic en +/−, antes 5). Finance, Leaderboard y Chat ya muestran `data.error` o ignoran respuestas no OK. No auditados: `Admin.jsx` y el frontend legacy de `public/`.
- **Memoria:** una clave guarda como máximo `limite` marcas de tiempo; se limpian las inactivas cada minuto y hay tope de 100.000 claves.
- **Límites conocidos:** (1) es por IP, no por usuario: varios jugadores detrás de la misma red/CGNAT comparten cupo (por eso todo es configurable); cuando el JWT (S1) esté conectado se puede añadir un cubo por `userId`. (2) No cubre mensajes de Socket.IO (spam de chat por socket): requiere un limitador propio en `SocketHandler`. (3) Login/registro entran en el cubo general (60/min); un cubo más estricto para fuerza bruta de contraseñas no estaba en S8.

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
| Sin clave configurada | Si está vacía o es la antigua `torneos2024`, el panel y su API responden 404 y se loguea un error |
| Fuerza bruta | Comparación SHA-256 en tiempo constante; 5 fallos desde una IP → 429 durante 15 min (en memoria). Sin header = 401, no cuenta |
| Cabeceras | `Cache-Control: no-store`, `Referrer-Policy: no-referrer`, `X-Frame-Options: DENY`, `X-Robots-Tag: noindex`, CSP `frame-ancestors 'none'` |
| SQL | Todo nombre de tabla/columna se valida contra `information_schema` y se cita con comillas dobles; los valores van siempre por `PreparedStatement` |

---

## 12. Configuración por variables de entorno (A7)

Toda configuración de despliegue se lee en `config/AppConfig` (nada de números ni URLs escritos en `Main` o en los handlers). Todas las variables de esta tabla son **opcionales**: sin definirlas queda el default, que es el valor que antes estaba escrito en el código. Un valor inválido (no numérico o fuera de rango) cae al default y deja un `logger.error` al arrancar. Las demás variables viven en sus secciones: montos (8), rate limiting (10) y BD (11).

| Variable | Default | Rango | Qué controla |
|---|---|---|---|
| `APP_BASE_URL` | `https://torneos-beta.onrender.com` | http(s) | URL del sitio en las push (enlace e icono `/icon-192.png`). Se quita la `/` final |
| `BIND_HOST` | `0.0.0.0` | | Interfaz en la que escucha Javalin. No se usa `HOST` para no chocar con esa variable de sistema |
| `WS_MAX_MESSAGE_BYTES` | `50000000` | >= 1024 | Tamaño máximo de un mensaje WebSocket (el chat manda imágenes en base64) |
| `BUSQUEDA_TIMEOUT_MINUTOS` | `10` | 1 - 1440 | Minutos de búsqueda de rival antes de cancelarla (`SocketHandler`) |
| `NEGOCIACION_DESCONEXION_SEGUNDOS` | `90` | 1 - 3600 | Segundos que se espera a un jugador desconectado durante la negociación antes de cancelar el match |
| `POLLING_INTERVALO_SEGUNDOS` | `5` | 2 - 60 | Cada cuánto se consulta la API de Clash Royale por el resultado de una partida |
| `POLLING_MAX_INTENTOS` | `120` | 1 - 10000 | Intentos máximos; espera total = intervalo x intentos (default 10 min) y luego se crea la disputa |
| `CHAT_HISTORIAL_LIMITE` | `50` | 1 - 500 | Mensajes de historial que recibe cada canal al conectarse |

- Los mensajes que ven los jugadores ("...nadie respondió en 10 minutos", "No se encontró el resultado en 10 minutos") y el campo `tiempo` de `rival_desconectado` se calculan con estos valores.
- `DB_ADMIN_SECRET` ya no tiene valor por defecto: sin ella el panel `/admin-db` queda deshabilitado (ver docstring de `RutasDbAdmin`).
- Siguen fijos en el código y fuera de A7: CORS `anyHost()` y la zona horaria del reset del leaderboard (usa la de la JVM, UTC en Render). Los pools de hilos pasaron a variables de entorno en A8 (siguiente sección).

---

## Hilos y pools (A8)

Todos los pools se crean en `config/Ejecutores` (con tamaños de `AppConfig`) y se reparten por constructor; ninguna clase crea sus propios `Executors`/`new Thread`. Cada pool tiene un solo trabajo, así el trabajo lento no deja sin hilos a los usuarios.

| Pool | Hilos (env, default) | Para qué | Si se llena |
|------|----------------------|----------|-------------|
| `http` (Jetty `QueuedThreadPool`, `config.jetty.threadPool`) | `HTTP_MAX_THREADS`=64 (min 16), `HTTP_MIN_THREADS`=8, `HTTP_IDLE_TIMEOUT_MS`=60000 | Peticiones HTTP y handlers de Socket.IO. Antes: el default de Javalin (250 hilos) | Las peticiones esperan en la cola de Jetty |
| `io` | `IO_POOL_THREADS`=16, `IO_POOL_QUEUE`=500 | Espera de red sin usuario esperando: push FCM, correos Brevo, sondeo de la API de Clash (`Ejecutores.ejecutarIO`) | La tarea se descarta con un `warn` (push/correo son "mejor esfuerzo"; el sondeo reintenta en el siguiente tick) |
| `cpu` | `CPU_POOL_THREADS`=nº de núcleos, `CPU_POOL_QUEUE`=100 | Hash/verificación BCrypt en login y registro (`Ejecutores.enCpu`) | Lo ejecuta el hilo que lo pidió (contrapresión; nunca se pierde) |
| `timers` | `TIMER_POOL_THREADS`=4 | Solo temporizadores cortos: ping de Socket.IO, timeouts de búsqueda y abandono, revisión de sorteos/leaderboard. Reemplaza 3 pools sueltos (2 + 4 + 2 hilos) | - |
| BD (HikariCP, `torneos-db`) | `DB_POOL_MAX_SIZE`=20 | Conexiones a PostgreSQL | Esperan hasta 5 s por una conexión |

- **Sondeo de la API de Clash:** el `scheduleAtFixedRate` de `SocketHandler.iniciarPollingApi` ya no ejecuta la llamada HTTP (hasta 10 s) en el hilo del temporizador; cada tick la encola en `io`. Un `AtomicBoolean` por partida (`enCurso`) evita que dos sondeos de la misma partida se solapen (un solape podría liquidar dos veces; antes lo impedía el propio `scheduleAtFixedRate`).
- **Timeouts:** el pool de IO es acotado, así que toda llamada que corre ahí debe tener tope de tiempo: Brevo y FCM ahora usan `connectTimeout` 10 s y `timeout` 15 s por petición; `/check-ip` usa 5 s. El `HttpClient` NO usa el pool `io` como executor (con `send()` síncrono y pool acotado podría bloquearse a sí mismo).
- **Apagado:** `Main` registra un shutdown hook: `app.stop()` → `Ejecutores.cerrar()` (cancela timers, deja terminar IO 5 s y CPU 2 s) → `ConexionDB.cerrar()`.
- **Pools con nombre:** hilos daemon `io-N`, `cpu-N`, `timer-N`, Jetty `http-N`, Hikari `torneos-db ...`; un volcado de hilos (`jstack`) los distingue.
- **Ajuste:** los hilos HTTP bloquean esperando BD; con `HTTP_MAX_THREADS` muy por encima de `DB_POOL_MAX_SIZE` solo se acumulan esperas de hasta 5 s. Si hay latencia por cola de Jetty, sube `HTTP_MAX_THREADS`; si `PostgreSQL` rechaza conexiones, baja `DB_POOL_MAX_SIZE`.
- **Fuera de A8 (pendiente):** `enviarPushATodos` envía los FCM uno por uno (con muchos tokens ocupa un hilo de `io` mucho rato); `GET /api/verify-tag` llama a Clash en el hilo HTTP; `GET /api/media/{id}` carga el archivo completo en RAM por petición; el ping de Socket.IO envía de forma secuencial (un cliente lento retrasa a los demás).

---

## 13. Bus de eventos (A4)

`eventos/EventBus` es un publicar/suscribir en memoria. Quien termina una acción de negocio llama `emit(nombre, datos)` y **no sabe quién escucha**; cada funcionalidad nueva (notificaciones, misiones, logros...) se suscribe con `on(nombre, idListener, listener)` sin tocar al emisor. Es lo que pide la convención "cada funcionalidad nueva lanza un evento y no notifica directamente". El catálogo de nombres y sus datos está en `eventos/Eventos`. Se crea una sola vez en `Main` y se reparte por constructor (`AdminService`, `SocketHandler`); no hay singleton estático.

| Evento | Datos | Lo emiten | Listeners (en orden) |
|---|---|---|---|
| `match.finished` | `Eventos.PartidaFinalizada`: `matchId`, `origen` (`API` / `DISPUTA`), `participantes` (ids de los 2 jugadores), `ganadorId`, `ganadorNombre`, `monto`, `premio`, `nuevoSaldoGanador`, `desglose` (`ComisionService.Desglose`) | `SocketHandler` (sondeo de la API de Clash, `API`) y `AdminService.resolverDisputa` (`DISPUTA`) | 1. `tickets-sorteo` (`TicketsPartidaListener`) · 2. `notificacion-resultado` (`ResultadoPartidaListener`) |

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

**Cómo se comporta según el estado de la BD** (`baselineOnMigrate=true`, `baselineVersion=0`):

| BD | Resultado |
|---|---|
| Vacía | Corre V1 y V2; crea todo |
| Con tablas pero sin `flyway_schema_history` (la de Render antes de A5) | Flyway la marca como versión 0 (baseline) y aplica V1 y V2 encima; V1 es idempotente, no cambia lo existente |
| Ya migrada | Aplica solo los scripts nuevos; si no hay, no hace nada |

**Reglas**
- **Toda tabla o columna nueva es un archivo nuevo** `V<n>__descripcion.sql` (el siguiente es `V3`; el `wallet_ledger` de D3 será `V3`). Nunca se agregan tablas en Java.
- **Nunca editar un `V*` ya aplicado:** Flyway guarda el checksum y `validateOnMigrate` hace fallar el arranque. Para corregir algo, una migración nueva.
- **Fail-fast:** si una migración falla, `Main` loguea el error y sale con código 1 (no se sirve tráfico con el esquema a medias). Cada script corre en su propia transacción en PostgreSQL: si falla se revierte entero.
- `cleanDisabled=true`: la app nunca puede ejecutar `flyway clean`.
- El panel `/admin-db` (`DbAdminService`) oculta y rechaza la tabla `flyway_schema_history`.
- La conexión de Flyway es la misma de Hikari (mismo usuario y SSL, sección 11): el usuario de la BD debe poder crear tablas y la tabla de historial.
- Para ver qué está aplicado: `SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank;`.
