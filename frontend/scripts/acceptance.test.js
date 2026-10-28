import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { PREGUNTAS } from '../src/data/faq.js'

const app = await readFile(new URL('../src/App.jsx', import.meta.url), 'utf8')
const store = await readFile(new URL('../src/store/useAppStore.js', import.meta.url), 'utf8')
const api = await readFile(new URL('../src/store/llamarApi.js', import.meta.url), 'utf8')
const lobbyCss = await readFile(new URL('../src/pages/Lobby.css', import.meta.url), 'utf8')
const faqJsx = await readFile(new URL('../src/pages/Faq.jsx', import.meta.url), 'utf8')
const faqCss = await readFile(new URL('../src/pages/Faq.css', import.meta.url), 'utf8')
const authJsx = await readFile(new URL('../src/pages/Auth.jsx', import.meta.url), 'utf8')
const authCss = await readFile(new URL('../src/pages/Auth.css', import.meta.url), 'utf8')
const matchJsx = await readFile(new URL('../src/pages/Match.jsx', import.meta.url), 'utf8')
const matchCss = await readFile(new URL('../src/pages/Match.css', import.meta.url), 'utf8')
const adminJsx = await readFile(new URL('../src/pages/Admin.jsx', import.meta.url), 'utf8')
const adminCss = await readFile(new URL('../src/pages/Admin.css', import.meta.url), 'utf8')

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
