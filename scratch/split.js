const fs = require('fs');
const path = require('path');

const appJsPath = path.join(__dirname, '../public/app.js');
const outDir = path.join(__dirname, '../public/js');

if (!fs.existsSync(outDir)) {
    fs.mkdirSync(outDir);
}

// app.js is UTF-16 LE usually in PowerShell, but let's read it as utf16le and check
let content = fs.readFileSync(appJsPath, 'utf16le');
if (!content.includes('DOMContentLoaded')) {
    // try utf8
    content = fs.readFileSync(appJsPath, 'utf8');
}

const lines = content.split(/\r?\n/);

const files = {
    'main.js': [],
    'auth.js': [],
    'lobby.js': [],
    'match.js': [],
    'chat.js': [],
    'finance.js': [],
    'leaderboard.js': [],
    'admin.js': [],
    'sorteos.js': []
};

let currentFile = 'main.js';

for (let line of lines) {
    if (line.includes('// --- AUTH ---')) currentFile = 'auth.js';
    else if (line.includes('// --- LEADERBOARD')) currentFile = 'leaderboard.js';
    else if (line.includes('// --- PAGOS ---')) currentFile = 'finance.js';
    else if (line.includes('// --- ADMIN ---')) currentFile = 'admin.js';
    else if (line.includes('// --- RENDERIZADO CHAT ---')) currentFile = 'chat.js';
    else if (line.includes('// --- SISTEMA DE SORTEOS ---')) currentFile = 'sorteos.js';
    else if (line.includes('// --- EVENTOS DE CONFIRMACI')) currentFile = 'match.js';
    else if (line.includes('// --- ACTUALIZACI')) currentFile = 'match.js';
    
    files[currentFile].push(line);
}

for (const [name, linesArr] of Object.entries(files)) {
    fs.writeFileSync(path.join(outDir, name), linesArr.join('\n'), 'utf8');
    console.log(`Wrote ${name}: ${linesArr.length} lines`);
}
