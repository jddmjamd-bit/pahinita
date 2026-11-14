import assert from 'node:assert/strict';
import { execFileSync, spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import test, { after, before } from 'node:test';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright-core';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const FRONTEND_DIR = path.resolve(__dirname, '..');
const DIST_INDEX = path.join(FRONTEND_DIR, 'dist', 'index.html');
const VITE_BIN = path.join(FRONTEND_DIR, 'node_modules', 'vite', 'bin', 'vite.js');
const SCREENSHOT_DIR = process.env.VISUAL_SCREENSHOT_DIR
    ? path.resolve(process.env.VISUAL_SCREENSHOT_DIR)
    : null;

const CANDIDATE_BROWSERS = [
    process.env.CHROME_BIN,
    'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
    'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
    '/usr/bin/google-chrome',
    '/usr/bin/chromium',
    '/usr/bin/chromium-browser',
].filter(Boolean);

function findBrowserExecutable() {
    for (const candidate of CANDIDATE_BROWSERS) {
        if (fs.existsSync(candidate)) {
            return candidate;
        }
    }
    return null;
}

function getFreePort() {
    return new Promise((resolve, reject) => {
        const server = net.createServer();
        server.unref();
        server.on('error', reject);
        server.listen(0, '127.0.0.1', () => {
            const addr = server.address();
            const port = typeof addr === 'object' && addr ? addr.port : 4179;
            server.close((err) => (err ? reject(err) : resolve(port)));
        });
    });
}

async function waitForServer(url, timeoutMs = 15000) {
    const start = Date.now();
    while (Date.now() - start < timeoutMs) {
        try {
            const res = await fetch(url);
            if (res.ok) return;
        } catch {
            // Servidor aún iniciando
        }
        await new Promise((r) => setTimeout(r, 150));
    }
    throw new Error(`El servidor Vite preview no respondió a tiempo en ${url}`);
}

async function ensureDistBuild() {
    if (fs.existsSync(DIST_INDEX)) return;
    execFileSync(process.execPath, [VITE_BIN, 'build'], {
        cwd: FRONTEND_DIR,
        stdio: 'inherit',
    });
}

async function maybeScreenshot(page, fileName, fullPage = true) {
    if (!SCREENSHOT_DIR) return;
    fs.mkdirSync(SCREENSHOT_DIR, { recursive: true });
    await page.screenshot({
        path: path.join(SCREENSHOT_DIR, fileName),
        fullPage,
    });
}

async function assertNoHorizontalOverflow(page, label) {
    const metrics = await page.evaluate(() => {
        const doc = document.documentElement;
        const chatArea = document.querySelector('.chat-area');
        return {
            innerWidth: window.innerWidth,
            docScrollWidth: doc.scrollWidth,
            chatClientWidth: chatArea ? chatArea.clientWidth : null,
            chatScrollWidth: chatArea ? chatArea.scrollWidth : null,
        };
    });
    assert.ok(
        metrics.docScrollWidth <= metrics.innerWidth + 1,
        `${label}: desborde horizontal global (scrollWidth=${metrics.docScrollWidth}, innerWidth=${metrics.innerWidth})`
    );
    if (metrics.chatClientWidth !== null && metrics.chatScrollWidth !== null) {
        assert.ok(
            metrics.chatScrollWidth <= metrics.chatClientWidth + 1,
            `${label}: desborde horizontal interno en .chat-area (scrollWidth=${metrics.chatScrollWidth}, clientWidth=${metrics.chatClientWidth})`
        );
    }
}

async function ensureTheme(page, expectedTheme) {
    const current = await page.evaluate(() => document.documentElement.getAttribute('data-theme'));
    if (current !== expectedTheme) {
        await page.locator('button[title="Cambiar Modo Claro/Oscuro"]').click();
        await page.waitForFunction(
            (target) => document.documentElement.getAttribute('data-theme') === target,
            expectedTheme
        );
        await page.waitForTimeout(220);
    }
}

async function scrollChatAreaToBottom(page) {
    await page.evaluate(() => {
        const area = document.querySelector('.chat-area');
        if (area) {
            area.scrollTop = area.scrollHeight;
        }
    });
    await page.waitForTimeout(100);
}

const BROWSER_PATH = findBrowserExecutable();
let previewProcess = null;
let browser = null;
let baseUrl = '';

before(async () => {
    if (!BROWSER_PATH) return;

    await ensureDistBuild();
    const port = await getFreePort();
    baseUrl = `http://127.0.0.1:${port}`;

    previewProcess = spawn(
        process.execPath,
        [VITE_BIN, 'preview', '--host', '127.0.0.1', '--port', String(port), '--strictPort'],
        {
            cwd: FRONTEND_DIR,
            stdio: 'ignore',
        }
    );

    await waitForServer(`${baseUrl}/auth`);
    browser = await chromium.launch({
        executablePath: BROWSER_PATH,
        headless: true,
        args: ['--no-sandbox', '--disable-gpu'],
    });
});

after(async () => {
    if (browser) {
        await browser.close().catch(() => {});
    }
    if (previewProcess && !previewProcess.killed) {
        previewProcess.kill();
    }
});

test('PC (1280x800): /auth?ref=DEMO2026 autocompleta, permite quitar/editar y respeta oscuro/claro sin desbordes', async (t) => {
    if (!browser) {
        t.skip('No hay Chrome/Edge local disponible en este entorno (p. ej. contenedor Alpine CI).');
        return;
    }

    const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
    const page = await context.newPage();
    const pageErrors = [];
    page.on('pageerror', (err) => pageErrors.push(err.message));

    try {
        await page.goto(`${baseUrl}/auth?ref=DEMO2026`, { waitUntil: 'networkidle' });

        await page.locator('#registro-container').waitFor({ state: 'visible' });
        const refInput = page.locator('#auth-reg-ref');
        assert.equal(await refInput.inputValue(), 'DEMO2026');

        await ensureTheme(page, 'dark');
        await assertNoHorizontalOverflow(page, 'PC /auth oscuro');
        await maybeScreenshot(page, 'visual-auth-desktop.png');

        await ensureTheme(page, 'light');
        await assertNoHorizontalOverflow(page, 'PC /auth claro');

        await page.locator('.auth-clear-ref-btn').click();
        assert.equal(await refInput.inputValue(), '');

        await refInput.fill('MANUAL99');
        assert.equal(await refInput.inputValue(), 'MANUAL99');

        assert.deepEqual(pageErrors, [], `Errores JS inesperados: ${pageErrors.join('; ')}`);
    } finally {
        await context.close();
    }
});

test('PC (1280x800): Vista Previa en /referidos muestra métricas, mensaje corto, split de comisión y modal en oscuro/claro', async (t) => {
    if (!browser) {
        t.skip('No hay Chrome/Edge local disponible en este entorno.');
        return;
    }

    const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
    const page = await context.newPage();
    const pageErrors = [];
    page.on('pageerror', (err) => pageErrors.push(err.message));

    try {
        await page.goto(`${baseUrl}/auth`, { waitUntil: 'networkidle' });
        await page.locator('.auth-preview-btn').click();
        await page.locator('.preview-mode-banner').waitFor({ state: 'visible' });
        await assertNoHorizontalOverflow(page, 'PC vista previa /chat');

        await page.locator('.sidebar .channel', { hasText: 'Referidos' }).click();
        await page.locator('.referidos-container').waitFor({ state: 'visible' });

        const statCards = await page.locator('.referidos-stat-card').count();
        assert.equal(statCards, 4);

        const bodyText = await page.locator('.referidos-container').innerText();
        assert.match(bodyText, /@CamilaArena_Demo/);
        assert.match(bodyText, /DEMO2026/);
        assert.match(bodyText, /\/auth\?ref=DEMO2026/);
        assert.match(bodyText, /@Valquiria_Demo/);
        assert.match(bodyText, /@Montapuercos_Demo/);
        assert.match(bodyText, /@Arqueras_Demo/);

        await page.locator('button.referidos-preset-chip', { hasText: /^50%$/ }).click();
        const splitText = await page.locator('.referidos-split-preview').innerText();
        assert.match(splitText, /\$50/);

        const msgBox = page.locator('#ref-short-msg');
        await msgBox.fill('Únete hoy con mi código especial:');
        const previewMsg = await page.locator('.referidos-msg-preview').innerText();
        assert.match(previewMsg, /Únete hoy con mi código especial:/);
        assert.match(previewMsg, /\/auth\?ref=DEMO2026/);

        await ensureTheme(page, 'dark');
        await assertNoHorizontalOverflow(page, 'PC /referidos oscuro');
        await maybeScreenshot(page, 'visual-referidos-desktop-dark.png');

        await scrollChatAreaToBottom(page);
        await maybeScreenshot(page, 'visual-referidos-desktop-list-dark.png');

        await ensureTheme(page, 'light');
        await assertNoHorizontalOverflow(page, 'PC /referidos claro (lista)');
        await page.evaluate(() => {
            const area = document.querySelector('.chat-area');
            if (area) area.scrollTop = 0;
        });
        await page.waitForTimeout(100);
        await assertNoHorizontalOverflow(page, 'PC /referidos claro');
        await maybeScreenshot(page, 'visual-referidos-desktop-light.png');

        await page.locator('button[type="submit"]', { hasText: 'Guardar configuración' }).click();
        await page.locator('.preview-auth-modal').waitFor({ state: 'visible' });
        await page.locator('.preview-auth-modal button', { hasText: 'Seguir viendo' }).click();
        await page.locator('.preview-auth-modal').waitFor({ state: 'hidden' });

        assert.deepEqual(pageErrors, [], `Errores JS inesperados: ${pageErrors.join('; ')}`);
    } finally {
        await context.close();
    }
});

test('Celular (390x844): /auth?ref=DEMO2026 y /referidos (menú ☰) se adaptan en oscuro/claro sin desbordes', async (t) => {
    if (!browser) {
        t.skip('No hay Chrome/Edge local disponible en este entorno.');
        return;
    }

    const context = await browser.newContext({ viewport: { width: 390, height: 844 } });
    const page = await context.newPage();
    const pageErrors = [];
    page.on('pageerror', (err) => pageErrors.push(err.message));

    try {
        await page.goto(`${baseUrl}/auth?ref=DEMO2026`, { waitUntil: 'networkidle' });
        await page.locator('#registro-container').waitFor({ state: 'visible' });
        assert.equal(await page.locator('#auth-reg-ref').inputValue(), 'DEMO2026');

        await ensureTheme(page, 'dark');
        await assertNoHorizontalOverflow(page, 'Celular /auth oscuro');
        await maybeScreenshot(page, 'visual-auth-mobile.png');

        await page.locator('.auth-preview-btn').click();
        await page.locator('.preview-mode-banner').waitFor({ state: 'visible' });

        await page.locator('button.mobile-menu-btn').click();
        await page.locator('.sidebar.open .channel', { hasText: 'Referidos' }).click();
        await page.locator('.referidos-container').waitFor({ state: 'visible' });

        await ensureTheme(page, 'dark');
        await assertNoHorizontalOverflow(page, 'Celular /referidos oscuro');
        await maybeScreenshot(page, 'visual-referidos-mobile-dark.png');

        await scrollChatAreaToBottom(page);
        await maybeScreenshot(page, 'visual-referidos-mobile-list-dark.png');

        await page.evaluate(() => {
            const area = document.querySelector('.chat-area');
            if (area) area.scrollTop = 0;
        });
        await ensureTheme(page, 'light');
        await assertNoHorizontalOverflow(page, 'Celular /referidos claro');
        await maybeScreenshot(page, 'visual-referidos-mobile-light.png');

        assert.deepEqual(pageErrors, [], `Errores JS inesperados: ${pageErrors.join('; ')}`);
    } finally {
        await context.close();
    }
});
