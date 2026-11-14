import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { PREGUNTAS } from '../src/data/faq.js'

const app = await readFile(new URL('../src/App.jsx', import.meta.url), 'utf8')
const store = await readFile(new URL('../src/store/useAppStore.js', import.meta.url), 'utf8')
const api = await readFile(new URL('../src/store/llamarApi.js', import.meta.url), 'utf8')
const lobbyJsx = await readFile(new URL('../src/pages/Lobby.jsx', import.meta.url), 'utf8')
const lobbyCss = await readFile(new URL('../src/pages/Lobby.css', import.meta.url), 'utf8')
const chatJsx = await readFile(new URL('../src/pages/Chat.jsx', import.meta.url), 'utf8')
const chatCss = await readFile(new URL('../src/pages/Chat.css', import.meta.url), 'utf8')
const indexCss = await readFile(new URL('../src/index.css', import.meta.url), 'utf8')
const faqJsx = await readFile(new URL('../src/pages/Faq.jsx', import.meta.url), 'utf8')
const faqCss = await readFile(new URL('../src/pages/Faq.css', import.meta.url), 'utf8')
const authJsx = await readFile(new URL('../src/pages/Auth.jsx', import.meta.url), 'utf8')
const authCss = await readFile(new URL('../src/pages/Auth.css', import.meta.url), 'utf8')
const matchJsx = await readFile(new URL('../src/pages/Match.jsx', import.meta.url), 'utf8')
const matchCss = await readFile(new URL('../src/pages/Match.css', import.meta.url), 'utf8')
const adminJsx = await readFile(new URL('../src/pages/Admin.jsx', import.meta.url), 'utf8')
const adminCss = await readFile(new URL('../src/pages/Admin.css', import.meta.url), 'utf8')
const canalesJs = await readFile(new URL('../src/services/notificacionesCanales.js', import.meta.url), 'utf8')
const capacitorJs = await readFile(new URL('../src/hooks/useCapacitor.js', import.meta.url), 'utf8')
const tiempoJs = await readFile(new URL('../src/utils/tiempoMatchmaking.js', import.meta.url), 'utf8')
const demoJs = await readFile(new URL('../src/data/vistaPreviaDemo.js', import.meta.url), 'utf8')
const sorteosJsx = await readFile(new URL('../src/pages/Sorteos.jsx', import.meta.url), 'utf8')
const leaderboardJsx = await readFile(new URL('../src/pages/Leaderboard.jsx', import.meta.url), 'utf8')
const chatbotJsx = await readFile(new URL('../src/pages/Chatbot.jsx', import.meta.url), 'utf8')
const soporteJsx = await readFile(new URL('../src/pages/Soporte.jsx', import.meta.url), 'utf8')
const financeJsx = await readFile(new URL('../src/pages/Finance.jsx', import.meta.url), 'utf8')
const vozDiscordJsx = await readFile(new URL('../src/components/VozDiscord.jsx', import.meta.url), 'utf8')

test('P2: las rutas privadas esperan la sesión, envían al login si no hay usuario y dejan /faq pública', () => {
  assert.match(app, /RUTAS_PUBLICAS\s*=\s*\[[^\]]*'\/faq'[^\]]*\]/)
  assert.match(app, /if \(!sessionReady && !RUTAS_PUBLICAS\.includes\(pathname\)\)/)
  assert.match(app, /if \(sessionReady && !user && !RUTAS_PUBLICAS\.includes\(pathname\)\)/)
  assert.match(app, /<Navigate to="\/auth" replace \/>/)
  assert.match(faqJsx, /faq-public-nav/)
  assert.match(authJsx, /to="\/faq"/)
})

test('P3: los datos de usuario no se persisten en localStorage y la API envía cookies', () => {
  assert.doesNotMatch(store, /localStorage\.setItem\(['"]currentUser['"]\s*,/)
  assert.doesNotMatch(store, /persist\s*\(/)
  assert.match(store, /localStorage\.removeItem\('currentUser'\)/)
  assert.match(api, /credentials:\s*'include'/)
})

test('P4: las FAQ incluyen pagos, Discord, Player Tag, Token de API, sorteos y recuperación sin vocabulario prohibido', () => {
  const contenido = PREGUNTAS.map(({ pregunta, respuesta }) => `${pregunta} ${respuesta.join(' ')}`).join('\n')
  assert.match(contenido, /recarg|retiro|pago/i)
  assert.match(contenido, /discord/i)
  assert.match(contenido, /sorteo|ticket/i)
  assert.match(contenido, /recuper\w*.*contraseña|contraseña.*recuper/i)
  assert.ok(PREGUNTAS.some(p => p.id === 'player-tag'))
  assert.ok(PREGUNTAS.some(p => p.id === 'token-api'))
  assert.doesNotMatch(contenido, /\bapuesta|\bapostar/i)
})

test('P5: las pantallas principales, Auth y FAQ declaran cortes responsive para móvil', () => {
  assert.match(lobbyCss, /@media\s*\(\s*max-width\s*:/)
  assert.match(faqCss, /@media\s*\(\s*max-width\s*:/)
  assert.match(authCss, /@media\s*\(\s*max-width\s*:/)
})

test('P6: el tema alterna claro/oscuro, se guarda y se aplica al documento', () => {
  assert.match(store, /localStorage\.getItem\('appTheme'\)\s*\|\|\s*'dark'/)
  assert.match(store, /localStorage\.setItem\('appTheme',\s*newTheme\)/)
  assert.match(app, /document\.documentElement\.setAttribute\('data-theme',\s*theme\)/)
  assert.match(app, /onClick=\{toggleTheme\}/)
})

test('P7: el registro ofrece botones de ayuda (?) para Player Tag y Token de API', () => {
  assert.match(authJsx, /aria-controls="ayuda-player-tag"/)
  assert.match(authJsx, /aria-controls="ayuda-api-token"/)
  assert.match(authJsx, /¿Dónde está\?/)
  assert.match(authJsx, /¿Para qué sirve aquí\?/)
})

test('P8: el chat privado define estilos propios de burbuja y habilita el canal de voz antes y durante la partida', () => {
  assert.match(matchCss, /\.mini-chat\s+\.msg\b/)
  assert.match(matchCss, /\.mini-chat\s+\.msg\.own\b/)
  assert.match(matchCss, /\.mini-chat\s+\.msg\.other\b/)
  const aparicionesVozActiva = matchJsx.match(/<VozDiscord\s+enPartida=\{true\}\s*\/>/g) || []
  assert.equal(aparicionesVozActiva.length, 2)
})

test('P9: Admin Finanzas muestra la zona combinada de ganancias y el aporte por jugador en las 7 categorías sin fetch suelto', () => {
  assert.doesNotMatch(adminJsx, /\bfetch\s*\(/)
  assert.match(adminJsx, /admin-earnings-zone/)
  assert.match(adminJsx, /gananciaComisionesAdmin/)
  assert.match(adminJsx, /gananciaAdminLeaderboard/)
  assert.match(adminJsx, /gananciaTotalCombinadaAdmin/)
  for (const campo of [
    'ganancia_generada',
    'gen_sorteos',
    'gen_misiones',
    'gen_logros',
    'gen_leaderboard',
    'gen_devolucion',
    'gen_referidos',
    'comision_total_aportada'
  ]) {
    assert.match(adminJsx, new RegExp(`\\b${campo}\\b`))
  }
  assert.match(adminCss, /\.admin-earnings-zone\b/)
  assert.match(adminCss, /\.user-contributions-grid\b/)
})

test('P10: Match usa llamarApi sin fetch suelto, expone el temporizador individual de 2:30 y documenta su uso en FAQ', () => {
  assert.doesNotMatch(matchJsx, /\bfetch\s*\(/)
  assert.match(matchJsx, /\bllamarApi\s*\(/)
  assert.match(matchJsx, /activar_timer_negociacion/)
  assert.match(matchJsx, /timer-clock-card/)
  assert.match(store, /newSocket\.on\('timer_negociacion'/)
  assert.match(matchCss, /\.negotiation-timer-panel\b/)
  assert.match(matchCss, /\.timer-clock-card\b/)
  assert.ok(PREGUNTAS.some(p => p.id === 'temporizador-sala'))
})

test('P11: Admin gestiona la Zona de Pruebas (selector de zona, suscripción prueba y borrado manual) sin fetch suelto', () => {
  assert.doesNotMatch(adminJsx, /\bfetch\s*\(/)
  assert.match(adminJsx, /\/api\/admin\/test-zone/)
  assert.match(adminJsx, /\/api\/admin\/test-zone\/switch/)
  assert.match(adminJsx, /\/api\/admin\/test-zone\/reset/)
  assert.match(adminJsx, /\/api\/admin\/users\/subscription/)
  assert.match(store, /newSocket\.on\('zona_cambiada'/)
  assert.match(store, /newSocket\.on\('zona_pruebas_reiniciada'/)
  assert.match(lobbyJsx, /test-zone-banner/)
  assert.match(adminCss, /\.admin-zone-bar\b/)
  assert.match(adminCss, /\.test-zone-reset-card\b/)
  assert.ok(PREGUNTAS.some(p => p.id === 'zona-pruebas-suscripcion'))
})

test('P12: Matchmaking justo oculta el puntaje numérico, muestra nivel discreto tocable en el título, anticipa búsquedas con tiempos de habilitación/aceptación y notifica al mayor solo si el menor acepta', () => {
  assert.match(store, /\/api\/matchmaking\/estado/)
  assert.match(store, /newSocket\.on\('matchmaking_reto_recibido'/)
  assert.match(store, /newSocket\.on\('matchmaking_reto_respuesta'/)
  assert.match(store, /despacharAvisoCanales/)
  assert.match(matchJsx, /desafiar_busqueda/)
  assert.match(matchJsx, /fair-matchmaking-panel/)
  assert.match(matchJsx, /mostrarVariables/)
  assert.match(matchJsx, /mm-header-level/)
  assert.match(matchJsx, /mm-header-vars-panel/)
  assert.match(matchJsx, /segundosParaHabilitar/)
  assert.match(matchJsx, /segundosRestantesAceptar/)
  assert.doesNotMatch(matchJsx, /Activar avisos en PC/)
  assert.doesNotMatch(matchJsx, /mm-channels-row/)
  assert.doesNotMatch(matchJsx, /Ver variables que aplican/)
  assert.match(lobbyJsx, /responder_reto_matchmaking/)
  assert.match(lobbyJsx, /mm-challenge-modal/)
  assert.match(lobbyJsx, /mm-challenge-countdown/)
  assert.match(lobbyJsx, /live-notification-accept-left/)
  assert.match(matchCss, /\.fair-matchmaking-panel\b/)
  assert.match(matchCss, /\.mm-header-level\b/)
  assert.match(lobbyCss, /\.mm-challenge-modal\b/)
  assert.match(lobbyCss, /\.mm-challenge-countdown\b/)
  assert.match(tiempoJs, /formatearTiempoReloj/)
  assert.match(canalesJs, /CANALES_MATCHMAKING\s*=\s*\['web',\s*'app',\s*'pc'\]/)
  assert.match(canalesJs, /LocalNotifications/)
  assert.match(canalesJs, /window\.Notification/)
  assert.match(capacitorJs, /pushNotificationReceived/)
  assert.match(capacitorJs, /pushNotificationActionPerformed/)
  assert.ok(PREGUNTAS.some(p => p.id === 'matchmaking-justo'))
  assert.doesNotMatch(matchJsx + lobbyJsx + canalesJs, /\bapuestas?\b/i)
})

test('P13: Sidebar directo sin desplegable de un solo juego, pestañas de chat desplazables con punto por canal y recuperación/recarga móvil al reconectar socket', () => {
  assert.doesNotMatch(lobbyJsx, /\bclashMenuOpen\b/)
  assert.doesNotMatch(lobbyJsx, />JUEGOS</)
  assert.match(lobbyJsx, /handleNavigation\('\/match'\)/)
  assert.match(lobbyJsx, /handleNavigation\('\/sorteos'\)/)
  assert.match(lobbyCss, /\.chat-area[\s\S]*min-width:\s*0/)
  assert.match(chatJsx, /CANALES_CHAT/)
  assert.match(chatJsx, /chat-tab-dot/)
  assert.match(chatJsx, /chatSinLeer/)
  assert.match(chatJsx, /marcarCategoriaLeida/)
  assert.match(chatCss, /\.chat-tabs[\s\S]*overflow-x:\s*auto/)
  assert.match(chatCss, /\.chat-tab-dot\b/)
  assert.match(store, /chatSinLeer/)
  assert.match(store, /window\.location\.reload\(\)/)
  assert.match(indexCss, /background-attachment:\s*scroll/)
  assert.match(indexCss, /100dvh/)
  assert.match(app, /AppErrorBoundary/)
  assert.match(app, /pageshow/)
  assert.match(app, /vite:preloadError/)
  assert.match(app, /visibilitychange/)
})

test('P14: redirige por defecto a /auth y habilita Modo Vista Previa de solo lectura con datos de ejemplo sin conectar ni permitir interacción real', () => {
  assert.match(authJsx, /auth-preview-btn/)
  assert.match(authJsx, /activarVistaPrevia/)
  assert.match(authJsx, /Ver vista previa de la página/)
  assert.match(authCss, /\.auth-preview-btn\b/)
  assert.match(app, /if \(!modoVistaPrevia \|\| pathname\.startsWith\('\/admin'\)\)/)
  assert.match(store, /modoVistaPrevia:\s*false/)
  assert.match(store, /solicitarAutenticacionVistaPrevia/)
  assert.doesNotMatch(store, /localStorage\.setItem\(['"]modoVistaPrevia/)
  assert.match(lobbyJsx, /preview-mode-banner/)
  assert.match(lobbyJsx, /preview-auth-modal/)
  assert.match(lobbyJsx, /if \(user && user\.id\)/)
  assert.match(lobbyCss, /\.preview-mode-banner\b/)
  assert.match(lobbyCss, /\.preview-auth-modal\b/)
  assert.match(chatJsx, /DEMO_CHAT_MENSAJES/)
  assert.match(matchJsx, /match-preview-switcher/)
  assert.match(matchJsx, /DEMO_SALA_PRIVADA/)
  assert.match(matchCss, /\.match-preview-switcher\b/)
  assert.match(sorteosJsx, /DEMO_SORTEOS/)
  assert.match(leaderboardJsx, /DEMO_LEADERBOARD/)
  assert.match(chatbotJsx, /DEMO_CHATBOT_MENSAJES/)
  assert.match(soporteJsx, /DEMO_SOPORTE_TICKETS/)
  assert.match(financeJsx, /solicitarAutenticacionVistaPrevia/)
  assert.match(vozDiscordJsx, /modoVistaPrevia/)
  assert.ok(PREGUNTAS.some(p => p.id === 'vista-previa'))
  assert.doesNotMatch(demoJs, /\bapuestas?\b|\bapostar\b/i)
})
