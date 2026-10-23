# Guía de Pruebas Manuales y Variables de Entorno

> Actualizada el 22 de octubre de 2026. Esta guía separa las pruebas JUnit que bloquean el arranque de los recorridos manuales que dependen de usuarios, navegador o servicios externos.

---

## Parte 1: Variables de Entorno

### 🔴 Obligatorias en producción (Render detiene el arranque si falta alguna)

| Variable | Default | Descripción |
|----------|---------|-------------|
| `DATABASE_URL` | *(vacío)* | URL de PostgreSQL (formato Render `postgres://user:pass@host/db` o JDBC). |
| `JWT_SECRET` | *(sin default aceptado en Render)* | Secreto HMAC-SHA256 para firmar JWT. **Mínimo 32 caracteres.** En desarrollo local puede generarse uno temporal; en Render el proceso falla antes de iniciar si falta o es corto. |
| `CLASH_ROYALE_API_TOKEN` | *(vacío)* | Token de la API de Clash Royale. Sin él no se puede verificar Player Tags ni buscar resultados de partidas. |
| `WOMPI_PUBLIC_KEY` | *(vacío)* | Llave pública de Wompi (widget de pago). |
| `WOMPI_PRIVATE_KEY` | *(vacío)* | Llave privada de Wompi (consultar estado de transacciones). |
| `WOMPI_EVENTS_SECRET` | *(vacío)* | Secreto de eventos de Wompi para verificar firma SHA-256 del webhook. **Sin él, los webhooks se rechazan (503).** |
| `WOMPI_INTEGRITY_SECRET` | *(vacío)* | Secreto de integridad de Wompi (generación de referencia). |

### 🟡 Recomendadas (funciona sin ellas pero pierde funcionalidad importante)

| Variable | Default | Descripción |
|----------|---------|-------------|
| `APP_BASE_URL` | `https://torneos-beta.onrender.com` | URL pública del sitio. Usado en push, CORS, HSTS, recovery links. |
| `BREVO_API_KEY` | *(vacío)* | API key de Brevo. Sin ella no se envían correos (recuperación de contraseña, soporte, chatbot). |
| `BREVO_SENDER_EMAIL` | *(vacío)* | Email remitente de Brevo. |
| `FIREBASE_SERVICE_ACCOUNT` | *(vacío)* | JSON de service account de Firebase. Sin él no hay push notifications. |
| `DB_ADMIN_SECRET` | *(vacío)* | Secreto para acceder al panel `/admin-db`. **Sin él, el panel queda deshabilitado.** |
| `CORS_ALLOWED_ORIGINS` | *(vacío)* | Orígenes extra para CORS separados por coma. Ya incluye `APP_BASE_URL`, localhost y capacitor. |
| `RATE_LIMIT_PROXY_HOPS` | `0` | Saltos de proxy confiables en `X-Forwarded-For`. **En Render debe ser `1`.** Con `0` ignora XFF y usa IP directa. Rango: 0-10. |

### 🟢 Opcionales con defaults seguros

#### Servidor y red

| Variable | Default | Rango | Descripción |
|----------|---------|-------|-------------|
| `PORT` | `5000` | 1-65535 | Puerto HTTP principal. Un valor inválido vuelve a `5000`. |
| `BIND_HOST` | `0.0.0.0` | — | Interfaz de escucha. |
| `SOCKET_IO_PORT` | `9092` | 0-65535 | Puerto interno de netty-socketio. |
| `WS_MAX_MESSAGE_BYTES` | `8000000` | 1KB-50MB | Tamaño máximo de mensaje WebSocket. |
| `DB_SSLMODE` | `require` | require/verify-ca/verify-full | Modo SSL de la conexión a PostgreSQL. |

#### Sesión y cookies

| Variable | Default | Rango | Descripción |
|----------|---------|-------|-------------|
| `JWT_EXPIRATION_DAYS` | `30` | 1-3650 | Días de validez del JWT. Un valor inválido vuelve a `30`. |
| `COOKIE_SECURE` | `true` | true/false | Flag `Secure` en la cookie de sesión. Un valor inválido vuelve a `true`; `COOKIE_SAMESITE=None` siempre fuerza `Secure`. |
| `COOKIE_SAMESITE` | `Lax` | Strict/Lax/None | Política SameSite de la cookie. |
| `PASSWORD_RESET_TOKEN_MINUTES` | `15` | 5-60 | Minutos de validez del enlace de recuperación. |
| `PASSWORD_RESET_COOLDOWN_SECONDS` | `60` | 30-600 | Cooldown entre solicitudes de recuperación por cuenta. |

#### Límites de montos (COP, pesos enteros)

| Variable | Default | Descripción |
|----------|---------|-------------|
| `MONTO_MIN_DEPOSITO` | `1000` | Mínimo para recargar. |
| `MONTO_MAX_DEPOSITO` | `5000000` | Máximo para recargar. |
| `MONTO_MIN_RETIRO` | `10000` | Mínimo para retirar. |
| `MONTO_MAX_RETIRO` | `5000000` | Máximo para retirar. |
| `MONTO_MIN_TORNEO` | `1000` | Mínimo de apuesta por partida. |
| `MONTO_MAX_TORNEO` | `10000` | Máximo de apuesta por partida. |
| `MONTO_MIN_SORTEO` | `1000` | Precio mínimo de un sorteo. |
| `MONTO_MAX_SORTEO` | `100000000` | Precio máximo de un sorteo. |

#### Rate limiting

| Variable | Default | Rango | Descripción |
|----------|---------|-------|-------------|
| `RATE_LIMIT_ENABLED` | `true` | true/false | Habilitar/deshabilitar rate limiting. Un valor inválido vuelve a `true`. |
| `RATE_LIMIT_GENERAL_PER_MIN` | `60` | ≥1 | Límite general por IP/min. |
| `RATE_LIMIT_FINANCIAL_PER_MIN` | `10` | ≥1 | Límite financiero por IP/min. |
| `RATE_LIMIT_AUTH_PER_MIN` | `10` | ≥1 | Límite de autenticación por IP/min. |
| `RATE_LIMIT_MEDIA_PER_MIN` | `30` | ≥1 | Límite de media por IP/min. |
| `RATE_LIMIT_CHATBOT_PER_MIN` | `10` | ≥1 | Límite del chatbot por IP/min. |

#### Pools de hilos (A8)

| Variable | Default | Rango | Descripción |
|----------|---------|-------|-------------|
| `HTTP_MAX_THREADS` | `64` | ≥16 | Hilos máximos de Jetty. |
| `HTTP_MIN_THREADS` | `8` | ≥1 | Hilos mínimos de Jetty. |
| `HTTP_IDLE_TIMEOUT_MS` | `60000` | ≥1000 | Timeout de hilo ocioso. |
| `SOCKET_IO_POLLING_MAX_CONCURRENT` | `mitad de HTTP_MAX_THREADS` (mínimo 2) | 2 a la mitad de `HTTP_MAX_THREADS` | Máximo de solicitudes largas de polling de Socket.IO; un valor inválido vuelve al default calculado. |
| `IO_POOL_THREADS` | `16` | ≥1 | Hilos del pool de IO (correos, Clash API). |
| `IO_POOL_QUEUE` | `500` | ≥1 | Cola del pool de IO. |
| `PUSH_POOL_THREADS` | `4` | 1-32 | Hilos del pool de push FCM. |
| `PUSH_POOL_QUEUE` | `200` | 1-10000 | Cola del pool de push. |
| `PUSH_MAX_PER_USER_PER_MIN` | `10` | 1-1000 | Máximo push por usuario/min. |
| `CPU_POOL_THREADS` | *(núcleos)* | ≥1 | Hilos del pool de CPU (BCrypt). |
| `CPU_POOL_QUEUE` | `100` | ≥1 | Cola del pool de CPU. |
| `TIMER_POOL_THREADS` | `4` | ≥1 | Hilos del pool de temporizadores. |
| `DB_POOL_MAX_SIZE` | `20` | ≥2 | Conexiones máximas de HikariCP. |

#### Temporizadores de partidas

| Variable | Default | Rango | Descripción |
|----------|---------|-------|-------------|
| `BUSQUEDA_TIMEOUT_MINUTOS` | `10` | 1-1440 | Tiempo máximo de búsqueda de rival. |
| `NEGOCIACION_DESCONEXION_SEGUNDOS` | `90` | 1-3600 | Timer de abandono por desconexión en negociación. |
| `POLLING_INTERVALO_SEGUNDOS` | `5` | 2-60 | Intervalo de sondeo a la API de Clash. |
| `POLLING_MAX_INTENTOS` | `120` | 1-10000 | Intentos máximos de sondeo (120 × 5s = 10 min). |
| `CHAT_HISTORIAL_LIMITE` | `50` | 1-500 | Mensajes de historial cargados al conectar. |

#### Chatbot IA (roadmap 20)

| Variable | Default | Descripción |
|----------|---------|-------------|
| `CHATBOT_API_KEY` | *(vacío)* | Sin ella el chatbot queda desactivado. |
| `CHATBOT_PROVIDER` | `gemini` | `gemini` o `openai`. |
| `CHATBOT_MODEL` | `gemini-flash-latest` | Modelo de IA. |
| `CHATBOT_ADMIN_EMAIL` | *(vacío)* | Correo al que llegan alertas del chatbot. |
| `CHATBOT_MAX_MENSAJES_POR_MIN` | `6` | 1-120 |
| `CHATBOT_MAX_MENSAJES_DIA` | `100` | 1-10000 |
| `CHATBOT_MAX_CONCURRENTES` | `8` | 1-100 |
| `CHATBOT_MAX_CORREOS_HORA` | `10` | 1-1000 |
| `CHATBOT_TIMEOUT_SEGUNDOS` | `25` | 5-120 |
| `CHATBOT_CONTEXTO_MENSAJES` | `8` | 0-30 |
| `CHATBOT_MAX_TOKENS` | `1024` | 64-8192 |

#### Soporte (roadmap 24.5)

| Variable | Default | Descripción |
|----------|---------|-------------|
| `SOPORTE_ADMIN_EMAIL` | `CHATBOT_ADMIN_EMAIL` o `BREVO_SENDER_EMAIL` | Correo de tickets de soporte; se ignoran direcciones inválidas. |
| `SOPORTE_MAX_ABIERTOS` | `5` | 1-100 |
| `SOPORTE_MAX_TICKETS_DIA` | `10` | 1-1000 |
| `SOPORTE_MAX_CORREOS_HORA` | `10` | 1-1000 |

#### Evidencias de pago (roadmap 7)

| Variable | Default | Descripción |
|----------|---------|-------------|
| `PAGOS_MAX_IMAGEN_CHARS` | `800000` | 50K-900K. Tope de la imagen base64. |
| `PAGOS_HISTORIAL_LIMITE` | `20` | 1-100 |

#### Discord chat de voz (roadmap 84)

| Variable | Default | Descripción |
|----------|---------|-------------|
| `DISCORD_CLIENT_ID` | *(vacío)* | Sin las 4 primeras el chat de voz queda desactivado. |
| `DISCORD_CLIENT_SECRET` | *(vacío)* | |
| `DISCORD_BOT_TOKEN` | *(vacío)* | |
| `DISCORD_GUILD_ID` | *(vacío)* | |
| `DISCORD_REDIRECT_URI` | `APP_BASE_URL/api/voz/discord/callback` | Debe coincidir con Developer Portal. |
| `DISCORD_VOICE_CATEGORY_ID` | *(vacío)* | Categoría para canales de voz. |
| `DISCORD_INVITE_URL` | *(vacío)* | Enlace de invitación al servidor. |
| `DISCORD_VOICE_MAX_MINUTOS` | `90` | 5-1440 |

#### Panel de BD

| Variable | Default | Descripción |
|----------|---------|-------------|
| `DB_ADMIN_MAX_BACKUP_BYTES` | `1073741824` (1 GiB) | 1MiB - 10GiB |
| `WOMPI_USER_PRINCIPAL_ID` | *(vacío)* | Para consultas admin a Wompi. |
| `WOMPI_X_API_KEY` | *(vacío)* | Para consultas admin a Wompi. |

---

## Pruebas automatizadas y arranque en Render

### Qué se ejecuta y cuándo

| Etapa | Prueba | Si falla |
|---|---|---|
| Construcción Docker | `npm run build`, contratos automáticos del frontend y `mvn clean verify`: JUnit ejecuta validaciones de configuración, JWT, SQL parametrizado, chat, rate limiting, URLs y montos. | La imagen no se construye y Render conserva el deploy anterior. |
| Inicio de cada instancia | `scripts/run-deploy-tests.sh` ejecuta `RenderDeploymentDatabaseCheck` y `RenderAcceptanceCheck` con JUnit antes del backend. | El proceso termina con error y el servidor no abre el puerto. |
| Inicio del backend en Render | `Main` comprueba las variables obligatorias cuando `RENDER=true`. | El proceso termina antes de crear pools, conectar a la BD o aceptar tráfico. |

Las pruebas de configuración construyen `AppConfig` con mapas de valores temporales. No cambian las variables del proceso. Las pruebas de aceptación usan credenciales ficticias y no llaman a Clash Royale, Wompi, Brevo, Firebase, Discord ni proveedores de IA. La suite que se conecta a PostgreSQL usa solo `TEST_DATABASE_URL`.

La guía A–Q sigue siendo el inventario completo de aceptación. La tabla siguiente marca qué comprobaciones ya están automatizadas, con sus límites. “Parcial” significa que se cubre la lógica del servicio o un contrato de código, pero todavía falta validar la ruta HTTP, el navegador, el socket o el servicio externo indicado en el caso.

### Cobertura automatizada de A–Q

| Casos | Estado | Alcance de la automatización |
|---|---|---|
| A1–A14, A16 | Docker runtime | JUnit prueba rutas HTTP de registro, login, cookies, sesión deslizante, JWT manipulado, sesiones simultáneas, logout/revocación, respuestas de recuperación genéricas, reset de un uso y cooldown; verifica además colisiones de username/tag en PostgreSQL. Clash se simula. La entrega real por Brevo sigue manual. |
| A15 | Manual / parcial | El alta normal confirma el flujo del tag con un proveedor Clash falso. La respuesta de Supercell y la API key real requieren una prueba manual o sandbox externo. |
| B1–B2, B4–B10 | Parcial, Docker runtime | Inicio Wompi, rangos, firma inválida, monto alterado, acreditación idempotente, retiro, rechazo por saldo y ledger con DB real. La aprobación positiva de Wompi y su consulta externa no se simulan como pago real. |
| B3, B6 | Parcial, Docker runtime | Se verifica el núcleo transaccional e idempotente del wallet. El webhook válido completo, incluida correlación con API Wompi, queda manual/sandbox. |
| C8–C9, C16 | Parcial, Docker runtime | Inicio de partida contra PostgreSQL, rollback por saldo insuficiente y dos confirmaciones concurrentes sin doble cobro. Emparejamiento, negociación por socket, timers y resultado de Clash siguen manuales. |
| D1–D2, D5, D7, M6 | Parcial, build | Sanitización de HTML, mensajes privados, canales no permitidos, SVG y rutas multimedia. Emisión/persistencia por socket y permisos de sala siguen manuales. |
| E1–E2, E4 | Docker runtime | Crea un corte con pozo y victorias sintéticos, verifica snapshot, reset de contador/pozo, distribución a jugador/admin, ledger, historial e idempotencia. Invoca la misma operación del servicio, sin esperar al cron. |
| E3 | Parcial, Docker runtime | Repetir un corte persistido no duplica pagos. No mata el proceso en mitad de una transacción ni simula una indisponibilidad de PostgreSQL. |
| F1–F4 | Docker runtime | Crear sorteo, participación, vencimiento y reembolso de tickets al eliminar con PostgreSQL real. |
| G1–G4 | Parcial, Docker runtime | Alta, cuotas abiertas y diarias, privacidad, rol admin y respuesta. El envío de correo/push queda fuera. |
| H1–H4 | Docker runtime | JUnit confirma desactivación sin clave, ejecuta el servicio con un proveedor IA falso y valida respuesta, límite por minuto y límite diario en PostgreSQL. La conexión y calidad de respuesta de Gemini/OpenAI se siguen verificando manualmente. |
| I1, I6 | Docker runtime | Sin credenciales Discord, la ruta autenticada informa que voz está desactivada y `/api/voz/mi-canal` rechaza solicitudes sin sesión. |
| I2–I5 | Manual | OAuth, permisos y creación/borrado de canales requieren Discord configurado y llamadas externas. |
| J1–J5 | Parcial, Docker runtime | Rutas HTTP comprueban 401 sin sesión y 403 con sesión de usuario; PostgreSQL valida rol real, aprobación de retiro sin doble descuento y liquidación idempotente de disputa. J6 y la IP del proxy real siguen manuales. |
| K1–K4, K6 | Docker runtime | Comprueba que el panel está deshabilitado sin secreto, oculta sesiones/tokens y enmascara contraseñas; cambia una celda sintética, protege saldo/ledger, crea y limpia un backup temporal, y rechaza un dump corrupto antes de activar mantenimiento. Usa solo la base aislada. |
| K5 | Manual | Restaurar un backup válido reemplaza la base completa; requiere una operación dedicada, copia revisada y confirmación administrativa. No se ejecuta en cada arranque. |
| L1–L4 | Parcial, Docker runtime | Autorización del servicio, unicidad, almacenamiento e historial con DB real. Emisión socket y push quedan manuales. |
| M1–M2 | Manual | Requieren probar los hooks globales de `Main` con el servidor completo y un origen HTTP; la prueba Javalin aislada no los duplica. |
| M5 | Docker runtime | La ruta real de inicio Wompi recibe un `userId` falso en el body y la suite verifica que el movimiento se vincula al usuario de la sesión. |
| M3–M4 | Parcial, build + Docker runtime | Escaneo impide `createStatement` en fuentes productivas y login verifica que no se devuelva `password`; `userData` de socket aún requiere inspección de integración. |
| N1–N3, N5 | Parcial, Docker runtime | JUnit arranca Javalin con el middleware real y límites temporales: valida respuestas 429 en auth/finanzas/general y comprueba que `X-Forwarded-For` no evade el límite con cero saltos confiables. N5 queda cubierto para ese caso; no inspecciona la conexión de Render. |
| N4 | Manual | La IP que Render entrega y el número correcto de saltos confiables solo se confirman desde una instancia detrás de su proxy. |
| O5 | Docker runtime | La reconciliación de un inicio legado verifica el vínculo sin volver a cobrar. O1–O4 necesitan reiniciar el servidor con sockets, colas y timers vivos. |
| P1 | Docker build | `npm run build` se ejecuta en la imagen. |
| P2–P6 | Parcial, Docker build | Pruebas de contrato sobre rutas privadas, persistencia en memoria, credenciales cookie, contenido FAQ, media queries y tema. No son una prueba visual en navegador ni reemplazan verificar tamaños/dispositivos reales. |
| Q1–Q2 | Docker runtime | Se validan las migraciones de la base de prueba y se aplican todas en un esquema vacío aislado. |
| Q3 | Docker runtime | Se crea un esquema aparte con duplicados de referencia Wompi; la migración debe fallar y conservar ambas filas. |

Los casos marcados como manuales se dejan así porque incluyen estado visual/gestos, servicios de terceros, red real o restauración destructiva. Ejecutarlos automáticamente en cada deploy podría cargar credenciales productivas, enviar correos o tocar datos externos.

### Base de datos exclusiva de pruebas

Configura `TEST_DATABASE_URL` en el entorno del servicio Render con la URL interna de una segunda base PostgreSQL. **No uses `DATABASE_URL` como valor de esta variable.** El nombre de la base debe incluir un segmento `test`, `prueba`, `pruebas` o `qa`, por ejemplo `torneosflash_test`. La prueba también rechaza el mismo nombre de base que `DATABASE_URL`.

Antes de iniciar el backend, JUnit aplica y valida Flyway en esa base, confirma que haya al menos 17 migraciones exitosas y que exista `public.users`. Luego crea un esquema con nombre aleatorio `tf_qa_*`, aplica allí las migraciones desde cero y ejecuta 23 pruebas de aceptación con usuarios sintéticos; algunas levantan un servidor HTTP en un puerto temporal y todas las conexiones de negocio apuntan al esquema aislado. Al terminar, cierra servidores y pools y elimina solo los esquemas con UUID generados por esa ejecución. Las operaciones nunca apuntan a `DATABASE_URL`. Flyway tiene `cleanDisabled=true`.

`TEST_DATABASE_URL` debe estar disponible como variable de entorno en tiempo de ejecución de Render. Si falta, la conexión falla, el nombre no parece de pruebas o Flyway detecta un error, el backend no inicia. Mantén `DATABASE_URL` conectada a la base normal de la aplicación.

Render proporciona `RENDER=true`; no hace falta crear esa variable. En previews de pull request (`IS_PULL_REQUEST=true`) no se exige el conjunto de secretos de producción. Para iniciar en una instancia de producción deben existir `DATABASE_URL`, `JWT_SECRET` de al menos 32 caracteres, `CLASH_ROYALE_API_TOKEN` y las cuatro credenciales requeridas de Wompi (`WOMPI_PUBLIC_KEY`, `WOMPI_PRIVATE_KEY`, `WOMPI_EVENTS_SECRET`, `WOMPI_INTEGRITY_SECRET`). El error enumera nombres de variables, nunca sus valores.

En Settings de Render, deja Docker Command vacío para que se use el `CMD` del Dockerfile. Un comando personalizado podría saltarse la prueba previa al arranque. La prueba corre en cada inicio de instancia (deploy, reinicio o escalado).

### Ejecutar pruebas localmente

- Windows: `mvnw.cmd clean verify`
- Linux/macOS: `./mvnw clean verify`
- En Render: Docker ejecuta build, los contratos del frontend y `mvn clean verify`; el runner de JUnit revisa PostgreSQL y ejecuta la suite sobre el esquema aislado antes de iniciar cada instancia.

La suite de servicio de PostgreSQL no se ejecuta durante la compilación Docker; corre en runtime para usar `TEST_DATABASE_URL`. No configures esa variable con la URL de producción. `npm run test:acceptance` en `frontend/` ejecuta los contratos frontend sin navegador; `mvnw.cmd clean verify` ejecuta las pruebas JUnit locales sin conectarse a PostgreSQL.

## Parte 2: Pruebas Manuales

Los casos A–Q a continuación contienen los recorridos de aceptación completos; la cobertura automatizada parcial está especificada en la matriz anterior. Ejecuta los pasos que faltan con sandbox/usuarios de prueba para pagos, recuperación de correo, restore y servicios externos.

### 🔐 A — Autenticación y Sesiones

| # | Prueba | Resultado esperado |
|---|--------|-------------------|
| A1 | Registrar cuenta nueva con username, email, password, playerTag y telefono | Respuesta genérica "Solicitud recibida...". No inicia sesión automáticamente. |
| A2 | Registrar con username/email que ya existe | **Misma** respuesta genérica (no revela que ya existe). |
| A3 | Login con credenciales válidas | 200 + cookie `session` httpOnly + datos del usuario. Verificar que NO hay cookie `userId`. |
| A4 | Login con email inexistente o password incorrecto | Mismo error genérico en ambos casos (no revela cuál falló). |
| A5 | `GET /api/session` con cookie válida | 200 + datos del usuario. La cookie se renueva (sesión deslizante). |
| A6 | `GET /api/session` sin cookie o con JWT expirado/manipulado | 401 + cookie borrada. |
| A7 | `POST /api/logout` | Cookie borrada. Verificar que la sesión queda revocada en `user_sessions`. |
| A8 | Después de logout, usar el JWT anterior para otro request | Debe rechazarse (JWT revocado en BD). |
| A9 | Login en dos navegadores distintos | Ambas sesiones activas (JTI distintos). Logout en uno NO cierra la otra. |
| A10 | Solicitar recuperación de contraseña (`POST /api/password/forgot`) con email válido | Respuesta genérica. Verificar que llega correo con enlace (requiere Brevo configurado). |
| A11 | Solicitar con email inexistente | **Misma** respuesta genérica. |
| A12 | Usar enlace de recuperación (`POST /api/password/reset`) | Contraseña cambiada. Todas las sesiones del usuario revocadas. Token consumido (un solo uso). |
| A13 | Usar el mismo enlace otra vez | Rechazado ("Token inválido o expirado"). |
| A14 | Solicitar recuperación dos veces rápido | Segunda solicitud rechazada por cooldown. |
| A15 | Registro con Player Tag + API token del juego | Tag verificado contra API de Supercell. Sin API key válida → fallo cerrado. |
| A16 | Registro con Player Tag ya vinculado a otra cuenta | Respuesta genérica (no revela que está en uso). |

### 💰 B — Finanzas y Wompi

| # | Prueba | Resultado esperado |
|---|--------|-------------------|
| B1 | Solicitar recarga Wompi con monto válido ($1000-$5000000) | Link de pago generado, transacción `pendiente` en BD, referencia única. |
| B2 | Solicitar con monto inválido (negativo, decimal, fuera de rango, texto) | Rechazado con mensaje descriptivo. |
| B3 | Webhook Wompi con firma válida y transacción APPROVED | Saldo acreditado, transacción → `completado`, fila en `wallet_ledger`. |
| B4 | Webhook Wompi con firma inválida o sin cabecera | 401, saldo no cambia. |
| B5 | Webhook Wompi con monto distinto al calculado por el servidor | Rechazado, saldo no cambia. |
| B6 | Webhook duplicado (mismo transaction_id) | Idempotente: no acredita dos veces (`WHERE estado = 'pendiente'`). |
| B7 | Solicitar retiro con saldo suficiente | Transacción pendiente, saldo descontado, fila en `wallet_ledger`. |
| B8 | Solicitar retiro con saldo insuficiente | Rechazado. |
| B9 | Solicitar retiro con monto fuera de rango | Rechazado. |
| B10 | Verificar `wallet_ledger` después de cada operación | `saldo_anterior` + `monto` = `saldo_posterior` para depósitos/premios. Cadena continua por usuario. |

### ⚔️ C — Partidas y Matchmaking

| # | Prueba | Resultado esperado |
|---|--------|-------------------|
| C1 | Buscar partida con sesión y estado `normal` | Estado → `buscando_partida`, fila en `match_queue`. |
| C2 | Buscar partida sin sesión o con `estado ≠ normal` | Rechazado. |
| C3 | Que dos jugadores se emparejen | Ambos reciben `partida_encontrada` con datos del rival (solo campos de `CAMPOS_RIVAL`, sin email/teléfono/saldo). Fila en `active_matches`. |
| C4 | Timeout de búsqueda (10 min default) | `busqueda_timeout` emitido, fila eliminada de `match_queue`, estado → `normal`. |
| C5 | Cancelar búsqueda manualmente | Fila eliminada, estado → `normal`. |
| C6 | Negociación: ambos votan mismo monto y modo | Modal de confirmación emitido a ambos. |
| C7 | Negociación: votan montos distintos | Error "Montos distintos". |
| C8 | Ambos confirman partida | Saldo descontado a ambos, `matches` creado con `estado='en_curso'`, polling de API inicia. |
| C9 | Confirmar con saldo insuficiente | Error, no se cobra, sala se preserva. |
| C10 | Confirmar partida + resultado encontrado en API de Clash | Ganador recibe premio, estadísticas actualizadas, comisiones distribuidas en `admin_wallet`, `matches.estado='finalizada'`. |
| C11 | Polling agota intentos sin resultado | `matches.estado='disputa'` (verificar que incluye `AND estado = 'en_curso'` — **Bug 4 fix**). |
| C12 | Cancelar partida en negociación | `salidas_chat` +1, sala limpiada, ambos liberados. |
| C13 | Cancelar partida ya iniciada | Rechazado ("La partida ya inició"). |
| C14 | Desconexión en negociación | Timer de 90s. Si no reconecta → sala cancelada, penalización `salidas_desconexion`. |
| C15 | Desconexión durante partida activa | Sin timer (espera indefinida). La API resuelve sin importar la conexión. |
| C16 | Doble confirmación simultánea (race condition) | Solo se cobra una vez (`synchronized(match)` + `FOR UPDATE` + `WHERE estado`) — **Bug 2 fix**. |
| C17 | `negociacion_live` después de que ambos votaron | Bloqueado si ya hay 2 votos — **Bug 3 fix**. |

### 💬 D — Chat

| # | Prueba | Resultado esperado |
|---|--------|-------------------|
| D1 | Enviar mensaje en canal `general` | Sanitizado (sin HTML), guardado en `messages`, emitido a todos. |
| D2 | Enviar `<script>alert(1)</script>` | Etiquetas eliminadas, texto plano guardado. |
| D3 | Enviar mensaje en `anuncios` como usuario normal | Rechazado (solo admins, verificado en BD). |
| D4 | Enviar mensaje en `anuncios` como admin | Aceptado. Imagen/video permitidos con validación. |
| D5 | Enviar mensaje privado en sala de partida | Sanitizado, guardado, emitido solo a la sala. |
| D6 | Enviar mensaje privado a sala ajena | Rechazado con log de warning. |
| D7 | Enviar imagen con data URI SVG | Rechazado (solo png/jpeg/gif/webp). |
| D8 | Verificar que `clash_logs` solo llegan a admins conectados con sesión vigente | Usuarios normales NO reciben logs de Clash. |
| D9 | Rate limiting de chat (por socket) | Mensajes excesivos silenciosamente descartados. |

### 🏆 E — Leaderboard

| # | Prueba | Resultado esperado |
|---|--------|-------------------|
| E1 | Ganar partidas y verificar que `victorias_dia/semana/mes/ano` suben | Contadores actualizados en la misma transacción de liquidación. |
| E2 | Corte de leaderboard (ejecutar manualmente o esperar el cron) | Pozo leído y reseteado en la misma transacción. Pagos persistidos en `leaderboard_cut_payments`. |
| E3 | Reiniciar el servidor a mitad de un corte | Los pagos `pendiente` se reintentan automáticamente cada minuto. |
| E4 | Verificar que el sobrante se reparte mitad jugadores / mitad admins | Calcular manualmente y comparar con BD. |

### 🎰 F — Sorteos

| # | Prueba | Resultado esperado |
|---|--------|-------------------|
| F1 | Admin crea sorteo con nombre y precio válidos | Sorteo creado. |
| F2 | Usuario participa con tickets suficientes | Participación registrada bajo bloqueo transaccional. |
| F3 | Participar después de `fecha_limite` | Rechazado. |
| F4 | Eliminar sorteo con participantes | Tickets devueltos en la misma transacción (no se pueden devolver dos veces). |

### 🎫 G — Soporte (tickets)

| # | Prueba | Resultado esperado |
|---|--------|-------------------|
| G1 | Crear ticket de soporte con sesión | Ticket creado, correo enviado al admin (si Brevo configurado). |
| G2 | Exceder `SOPORTE_MAX_ABIERTOS` | Rechazado. |
| G3 | Exceder `SOPORTE_MAX_TICKETS_DIA` | Rechazado. |
| G4 | Admin responde ticket | Respuesta guardada, usuario notificado. |

### 🤖 H — Chatbot IA

| # | Prueba | Resultado esperado |
|---|--------|-------------------|
| H1 | Sin `CHATBOT_API_KEY` configurada | Chatbot desactivado, respuesta informativa. |
| H2 | Con API key, enviar mensaje | Respuesta del modelo IA con contexto de la plataforma. |
| H3 | Exceder `CHATBOT_MAX_MENSAJES_POR_MIN` | Rate limited. |
| H4 | Exceder `CHATBOT_MAX_MENSAJES_DIA` | Bloqueado hasta el día siguiente. |

### 🎤 I — Discord (chat de voz)

| # | Prueba | Resultado esperado |
|---|--------|-------------------|
| I1 | Sin variables `DISCORD_*` | `GET /api/voz/estado` → `{disponible:false}`. No se muestra nada en el frontend. |
| I2 | Vincular cuenta Discord (OAuth2) | Redirige a Discord, vuelve con `?discord=ok`, registro en `discord_links`. |
| I3 | Vincular la misma cuenta Discord desde otro usuario | Rechazado ("ya está vinculada"). |
| I4 | Partida con ambos vinculados | Canal `partida-<id>` creado en Discord, privado para solo los 2 jugadores. |
| I5 | Partida termina o se resuelve disputa | Canal de voz borrado automáticamente. Verificar `voice_channels.estado = 'cerrado'`. |
| I6 | `GET /api/voz/mi-canal` sin sesión | 401. |

### 🔧 J — Panel Admin

| # | Prueba | Resultado esperado |
|---|--------|-------------------|
| J1 | Acceder a `/api/admin/*` sin sesión | 401. |
| J2 | Acceder a `/api/admin/*` como usuario normal | 403 (verificado en BD, no en JWT claim). |
| J3 | Aprobar retiro desde panel admin | Saldo no cambia (ya descontado), transacción → `completado`. |
| J4 | Resolver disputa: asignar ganador | Liquidación atómica, comisiones distribuidas, estadísticas actualizadas. |
| J5 | Resolver disputa de partida ya finalizada | Rechazado (idempotencia `WHERE estado = 'disputa'`). |
| J6 | `GET /api/admin/check-ip` | Muestra IP efectiva del rate limit, configuración de hops y XFF recibido. |

### 🗄️ K — Panel de Base de Datos (`/admin-db`)

| # | Prueba | Resultado esperado |
|---|--------|-------------------|
| K1 | Acceder sin `DB_ADMIN_SECRET` configurado | Panel deshabilitado. |
| K2 | Listar tablas | Todas las tablas visibles excepto las protegidas (`user_sessions`, `password_reset_tokens`). |
| K3 | Editar celda de una tabla | Cambio guardado con `PreparedStatement`. |
| K4 | Backup completo | Archivo `pg_dump` descargado. |
| K5 | Restore con frase de confirmación | Tráfico pausado → `pg_restore --single-transaction` → Flyway migrations → tráfico reanudado. Sesiones anteriores revocadas. |
| K6 | Restore con archivo corrupto o sin tablas financieras | Rechazado antes de tocar la BD. |

### 🧾 L — Evidencias de Pago

| # | Prueba | Resultado esperado |
|---|--------|-------------------|
| L1 | Admin publica evidencia de retiro aprobado | Imagen guardada, emitida por socket a canal `pagos`, push al usuario. |
| L2 | Publicar evidencia del mismo retiro dos veces | Idempotente (`ON CONFLICT DO NOTHING`). |
| L3 | Usuario normal intenta publicar evidencia | Rechazado (middleware admin). |
| L4 | Usuario ve historial de evidencias | Solo lectura, imágenes válidas. |

### 🛡️ M — Seguridad General

| # | Prueba | Resultado esperado |
|---|--------|-------------------|
| M1 | Verificar headers de respuesta HTTP | `Content-Security-Policy`, `Strict-Transport-Security`, `X-Content-Type-Options: nosniff`, `X-Frame-Options`. |
| M2 | Request desde origen no permitido (CORS) | Bloqueado. |
| M3 | `git grep "createStatement" src/main/java` | 0 resultados (todo PreparedStatement). |
| M4 | Verificar que `password` nunca aparece en respuestas JSON al frontend | Revisar `GET /api/session` y datos de socket `userData`. |
| M5 | Enviar userId falso en el body de un endpoint financiero | Ignorado (userId siempre viene del JWT). |
| M6 | Intentar escribir en `clash_logs` como usuario normal | Rechazado por `ChatSanitizer` (no está en `CANALES_PUBLICOS`). |

### ⏱️ N — Rate Limiting

| # | Prueba | Resultado esperado |
|---|--------|-------------------|
| N1 | Más de 60 requests/min a rutas generales | 429 Too Many Requests. |
| N2 | Más de 10 requests/min a rutas financieras | 429. |
| N3 | Más de 10 requests/min a rutas de auth | 429. |
| N4 | Con `RATE_LIMIT_PROXY_HOPS=1` en Render, verificar que la IP es la del cliente real | `GET /api/admin/check-ip` debe mostrar la IP del usuario, no la del proxy. |
| N5 | Con `RATE_LIMIT_PROXY_HOPS=0`, verificar que `X-Forwarded-For` se ignora | Usa IP directa de la conexión TCP. |

### ♻️ O — Recuperación tras Reinicio (A2)

| # | Prueba | Resultado esperado |
|---|--------|-------------------|
| O1 | Reiniciar servidor con partida en negociación | Sala restaurada de `active_matches`. Timer de reconexión programado. |
| O2 | Reiniciar servidor con partida en curso (polling activo) | Sala restaurada. Polling reanudado desde `poll_count` actual. |
| O3 | Reiniciar servidor con búsquedas en cola | Búsquedas restauradas de `match_queue` con segundos restantes. Las expiradas se limpian. |
| O4 | Verificar que estados huérfanos se limpian | `users` con `estado ≠ normal` pero sin fila en `match_queue` ni `active_matches` → reseteados a `normal`. |
| O5 | Partida con cobro previo al reinicio | `reconciliarPartidasTorneoAlReiniciar` busca matches compatibles y vincula. Casos ambiguos → `inicio_revision` (revisión manual). |

### 🌐 P — Frontend React

| # | Prueba | Resultado esperado |
|---|--------|-------------------|
| P1 | `npm run build` en `frontend/` | Build exitoso sin errores. |
| P2 | Navegar a la app sin sesión | Redirige a login. No hay datos en `localStorage` (solo tema). |
| P3 | Login → verificar que `currentUser` NO se guarda en localStorage | Solo en memoria (store). |
| P4 | Verificar que la FAQ tiene las preguntas de: pagos, Discord, sorteos, recuperación | Todas presentes. |
| P5 | Responsive en móvil | Layout adaptable. |
| P6 | Dark mode | Toggle funcional, tema persistido en localStorage. |

### 📋 Q — Flyway Migrations

| # | Prueba | Resultado esperado |
|---|--------|-------------------|
| Q1 | Verificar `SELECT version, success FROM flyway_schema_history ORDER BY installed_rank` | V1 a V17, todas con `success = true`. |
| Q2 | Arrancar con BD vacía | Flyway aplica las 17 migraciones automáticamente. |
| Q3 | V17 con duplicados existentes | Migración aborta con mensaje explicativo (no borra datos). |
