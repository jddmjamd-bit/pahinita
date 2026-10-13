# Entrada pendiente de pegar — Chatbot IA (roadmap parte 20)

> No pude escribir en `CHANGELOG.md` ni en `ARCHITECTURE.md` (la herramienta de archivos rechaza sobrescribir
> cualquier archivo existente: «replaced while it was being opened»). Pega la sección 1 debajo de la línea `---`
> de `CHANGELOG.md` y la sección 2 al final de `ARCHITECTURE.md`; después borra este archivo.

---

## 1. Para `CHANGELOG.md`

## 2026-10-12 — Chatbot IA: revisión de lo que dejó el agente bloqueado + frontend (Claude Sonnet 5.5)
- Estado encontrado: el backend estaba completo (`ChatbotService`, `ChatbotIaCliente`, `ChatbotConocimiento`, `ChatbotListener`, `V3__chatbot_ia.sql`, variables `CHATBOT_*` en `AppConfig`, eventos `chatbot.duda_sin_respuesta` / `chatbot.duda_respondida`, cableado en `Main`) y coherente entre sí (firmas, getters, eventos y `ServicioException` revisados a mano). Faltaban: (1) `RutasChatbot.register` aceptaba 2 parámetros pero `Main` la llama con 4 → **error de compilación**, y por eso nunca existió el límite por IP del chatbot; (2) todo el frontend (página del usuario y panel admin); (3) documentación.
- Backend: `RutasChatbot.register(app, chatbot, config, limitador)` + `limitarPorIp` en `POST /api/chatbot/mensaje`: límite por IP `RATE_LIMIT_CHATBOT_PER_MIN` (10) usando el mismo `RateLimiter` del servicio (claves `chatbot|ip|<ip>` vs `chatbot|usuario|<id>`), IP real con `RateLimitMiddleware.ipCliente` (X-Forwarded-For), respeta `RATE_LIMIT_ENABLED`; 429 con `Retry-After`. **Entregado como `RutasChatbot.java.nuevo` (ver pendiente).**
- Frontend React (el que sirve producción: el `Dockerfile` copia `frontend/dist` a `public/`): `store/llamarApi.js` (fetch con `credentials: 'include'`, nunca lanza), `pages/Asistente.jsx` + `.css` (chat del usuario: historial, tono propio, borrar chat, respuesta del admin en vivo por el evento de socket `chatbot_respuesta`; todo texto se pinta con React, sin `dangerouslySetInnerHTML`) y `components/ChatbotAdmin.jsx` + `.css` (pestaña «🤖 Chatbot» del panel admin: Dudas de usuarios, Entrenamiento, Conocimiento y Ajustes de tono). El correo al admin ya apuntaba a `/admin` → «🤖 Chatbot» → «Dudas de usuarios», que coincide.
- NO terminado (herramienta de archivos): (a) reemplazar `RutasChatbot.java` por `RutasChatbot.java.nuevo` (borrar el `.java` viejo y renombrar el `.nuevo` quitando la extensión); (b) conectar el frontend en 3 archivos existentes, ver «Cableado» abajo; (c) pegar estas entradas en los `.md`.
- Cableado pendiente:
  - `frontend/src/App.jsx`: `import Asistente from './pages/Asistente'` y, junto a las demás rutas hijas de `/`, `<Route path="asistente" element={<Asistente />} />`.
  - `frontend/src/pages/Lobby.jsx`, debajo del canal de Chat: `<div className={`channel ${isActive('/asistente')}`} onClick={() => handleNavigation('/asistente')}>🤖 Asistente</div>`.
  - `frontend/src/pages/Admin.jsx`: `import ChatbotAdmin from '../components/ChatbotAdmin';`, un botón más en `.admin-tabs` (`<button className={`admin-tab ${view === 'chatbot' ? 'active' : ''}`} onClick={() => setView('chatbot')}>🤖 Chatbot</button>`) y, antes del `</div>` final del componente, `{view === 'chatbot' && <ChatbotAdmin />}`.
- Observado y NO tocado: (1) el comentario de `ChatbotService` dice que el límite por IP está en `RateLimitMiddleware`; ahora está en `RutasChatbot`; (2) la app React llama a la API con `credentials: 'include'`, pero si se sirve desde otro origen (Capacitor) la cookie `session` con `SameSite=Lax` no viajará: haría falta `COOKIE_SAMESITE=None` + CORS con lista de orígenes (ya anotado en la entrada S1); (3) `GenericDAO` traga las excepciones SQL (`printStackTrace`): si falta la migración V3 el chatbot responderá vacío en vez de fallar con claridad; (4) no se escribieron pruebas (el proyecto no tiene JUnit) y no pude compilar ni ejecutar nada.
- Pendiente de verificación manual: (1) aplicar lo de «NO terminado» y `mvn -q clean compile`; (2) `cd frontend && npm run build`; (3) arrancar con `CHATBOT_API_KEY` (Gemini por defecto; para OpenAI `CHATBOT_PROVIDER=openai` + `CHATBOT_MODEL`) y ver en el log `🤖 Chatbot IA listo` y que Flyway aplicó `V3` (y `V4` de la tarea legal, si ya está completa); sin la clave debe verse `Chatbot IA desactivado` y el resto de la app funcionar igual; (4) como usuario: abrir «🤖 Asistente», preguntar algo; sin conocimiento cargado debe responder que no sabe, avisar al admin (correo si Brevo está configurado) y aparecer en «Dudas de usuarios»; (5) como admin: responder esa duda → el usuario la recibe en su chat (en vivo si está conectado y si no al recargar) y el bot ya la sabe; (6) «Entrenamiento»: «Obtener pregunta», responder, y comprobar que no repite preguntas; (7) enviar 7 mensajes en un minuto (`CHATBOT_MAX_MENSAJES_POR_MIN`=6) y 11 desde la misma IP (`RATE_LIMIT_CHATBOT_PER_MIN`=10): debe responder 429 con mensaje en español; (8) un usuario normal que llame a `/api/admin/chatbot/config` debe recibir 403 y sin sesión 401; (9) mensaje con `<b>hola</b>` o `<script>`: se muestra como texto/queda limpio, nunca se ejecuta.
Archivos: `RutasChatbot.java.nuevo` (nuevo, reemplaza a `RutasChatbot.java`), `frontend/src/store/llamarApi.js`, `frontend/src/pages/Asistente.jsx`, `frontend/src/pages/Asistente.css`, `frontend/src/components/ChatbotAdmin.jsx`, `frontend/src/components/ChatbotAdmin.css`, `CHANGELOG_chatbot_pendiente.md`

---

## 2. Para `ARCHITECTURE.md` (sección nueva al final)

## 17. Chatbot IA (roadmap parte 20)

**Idea:** no se entrena con un documento. La IA le hace preguntas al admin sobre la plataforma y sus respuestas son la base de conocimiento; lo que un usuario pregunta y la base no cubre queda como duda pendiente, se avisa al admin y su respuesta se entrega al usuario.

| Pieza | Archivo | Qué hace |
|-------|---------|----------|
| Orquestación | `servicio/ChatbotService.java` | Conversación, tono, modo entrenamiento, dudas, permisos (usuario/admin salen de la sesión). Sin HTTP (A3) |
| Cliente IA | `servicio/ChatbotIaCliente.java` | Gemini (default) u OpenAI con `java.net.http`; clave en cabecera; tope de llamadas simultáneas; errores solo al log |
| Búsqueda | `servicio/ChatbotConocimiento.java` | Normaliza preguntas y elige qué entradas se pasan a la IA (todas si son ≤ 15; si no, las 8 más relevantes) |
| Eventos | `servicio/ChatbotListener.java` + `eventos/Eventos.java` | `chatbot.duda_sin_respuesta` → correo al admin (tope por hora); `chatbot.duda_respondida` → historial + socket `chatbot_respuesta` + push |
| Rutas | `servidor/RutasChatbot.java` | `/api/chatbot/*` (usuario) y `/api/admin/chatbot/*` (admin). Límite por IP en `POST /mensaje` |
| BD | `db/migration/V3__chatbot_ia.sql` | `chatbot_conocimiento`, `chatbot_duda_usuarios`, `chatbot_mensajes`, `chatbot_preferencias`, `chatbot_config` |
| Frontend | `pages/Asistente.jsx`, `components/ChatbotAdmin.jsx`, `store/llamarApi.js` | Chat del usuario y pestaña «🤖 Chatbot» del panel admin |

- **Cómo sabe que no sabe:** la IA empieza cada respuesta con `[RESUELTA]` o `[SIN_RESPUESTA]`; el servidor quita la etiqueta antes de mostrar el texto.
- **Costos y abuso (3 capas):** límite por usuario por minuto y por día (`ChatbotService`), límite por IP (`RutasChatbot`) y tope de llamadas simultáneas a la IA (`ChatbotIaCliente`).
- **Variables de entorno (todas opcionales; sin `CHATBOT_API_KEY` el chatbot queda desactivado):** `CHATBOT_PROVIDER` (gemini | openai), `CHATBOT_API_KEY`, `CHATBOT_MODEL`, `CHATBOT_ADMIN_EMAIL`, `CHATBOT_MAX_MENSAJES_POR_MIN` (6), `CHATBOT_MAX_MENSAJES_DIA` (100), `CHATBOT_MAX_CONCURRENTES` (8), `CHATBOT_MAX_CORREOS_HORA` (10), `CHATBOT_TIMEOUT_SEGUNDOS` (25), `CHATBOT_CONTEXTO_MENSAJES` (8), `CHATBOT_MAX_TOKENS` (1024), `RATE_LIMIT_CHATBOT_PER_MIN` (10).
