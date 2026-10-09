# ARCHITECTURE.md — UltimateClash (TorneosFlash)

> Documento de referencia para que cualquier modelo o desarrollador entienda el sistema.
> Última actualización: 2026-10-09

---

## 1. Stack actual

| Capa | Tecnología | Notas |
|------|-----------|-------|
| **Backend** | Java 17, Javalin 5 | Servidor HTTP + middleware |
| **WebSocket** | Adaptador custom (`socketio/`) | Emula Socket.IO sobre WS nativo de Javalin. **Migrar a netty-socketio** (pendiente) |
| **BD** | PostgreSQL (Render) | Pool: HikariCP, max 20 conexiones |
| **ORM** | Ninguno | SQL directo con `PreparedStatement` vía `GenericDAO` |
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
│   │   ├── ConexionDB.java          # Singleton HikariCP + inicializarTablas()
│   │   ├── GenericDAO.java          # CRUD genérico (select, insert, update, delete)
│   │   └── UsuarioDAO.java          # Queries específicas de usuarios
│   ├── servicio/
│   │   ├── ClashApiServicio.java    # Integración con API de Clash Royale
│   │   ├── ChatSanitizer.java       # Validación/limpieza de mensajes de chat y usernames (anti-XSS)
│   │   ├── CorreoServicio.java      # Envío de emails vía Brevo
│   │   ├── NotificacionPushServicio.java # Push FCM
│   │   └── ValidadorMonto.java      # Valida montos del cliente (número, > 0, entero, rango por tipo). Ver sección 8
│   ├── servidor/                    # Handlers HTTP (rutas REST)
│   │   ├── RutasAuth.java           # Login, registro, sesión
│   │   ├── RutasAdmin.java          # Operaciones admin (aprobar retiros, etc.)
│   │   ├── RutasDbAdmin.java        # CRUD admin sobre BD
│   │   ├── RutasFinanzas.java       # Depósitos, retiros, Wompi webhook
│   │   ├── RutasLeaderboard.java    # Rankings diario/semanal/mensual/anual
│   │   ├── RutasMedia.java          # Upload/descarga de videos
│   │   └── RutasSorteos.java        # CRUD sorteos y participaciones
│   └── socketio/                    # Adaptador custom Socket.IO
│       ├── SocketIOServer.java      # Parseo de paquetes Engine.IO/Socket.IO
│       └── SocketIOClient.java      # Wrapper del cliente WS
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
  3. Comisión se distribuye en admin_wallet por categoría (25% ganancia, 20% sorteos, 15% leaderboard, 15% devolución, 10% misiones, 10% referidos, 5% logros)

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
