import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { PREGUNTAS } from '../src/data/faq.js'

const app = await readFile(new URL('../src/App.jsx', import.meta.url), 'utf8')
const store = await readFile(new URL('../src/store/useAppStore.js', import.meta.url), 'utf8')
const api = await readFile(new URL('../src/store/llamarApi.js', import.meta.url), 'utf8')
const lobbyCss = await readFile(new URL('../src/pages/Lobby.css', import.meta.url), 'utf8')
const faqCss = await readFile(new URL('../src/pages/Faq.css', import.meta.url), 'utf8')

test('P2: las rutas privadas esperan la sesión y envían al login si no hay usuario', () => {
  assert.match(app, /if \(!sessionReady && !RUTAS_PUBLICAS\.includes\(pathname\)\)/)
  assert.match(app, /if \(sessionReady && !user && !RUTAS_PUBLICAS\.includes\(pathname\)\)/)
  assert.match(app, /<Navigate to="\/auth" replace \/>/)
})

test('P3: los datos de usuario no se persisten en localStorage y la API envía cookies', () => {
  assert.doesNotMatch(store, /localStorage\.setItem\(['"]currentUser['"]\s*,/)
  assert.doesNotMatch(store, /persist\s*\(/)
  assert.match(store, /localStorage\.removeItem\('currentUser'\)/)
  assert.match(api, /credentials:\s*'include'/)
})

test('P4: las FAQ incluyen pagos, Discord, sorteos y recuperación de cuenta', () => {
  const contenido = PREGUNTAS.map(({ pregunta, respuesta }) => `${pregunta} ${respuesta.join(' ')}`).join('\n')
  assert.match(contenido, /recarg|retiro|pago/i)
  assert.match(contenido, /discord/i)
  assert.match(contenido, /sorteo|ticket/i)
  assert.match(contenido, /recuper\w*.*contraseña|contraseña.*recuper/i)
})

test('P5: las pantallas principales y FAQ declaran cortes responsive para móvil', () => {
  assert.match(lobbyCss, /@media\s*\(\s*max-width\s*:/)
  assert.match(faqCss, /@media\s*\(\s*max-width\s*:/)
})

test('P6: el tema alterna claro/oscuro, se guarda y se aplica al documento', () => {
  assert.match(store, /localStorage\.getItem\('appTheme'\)\s*\|\|\s*'dark'/)
  assert.match(store, /localStorage\.setItem\('appTheme',\s*newTheme\)/)
  assert.match(app, /document\.documentElement\.setAttribute\('data-theme',\s*theme\)/)
  assert.match(app, /onClick=\{toggleTheme\}/)
})
