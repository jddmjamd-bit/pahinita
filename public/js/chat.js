    // --- RENDERIZADO CHAT ---
    function renderizarChat(canal) {
        const lista = chatLists[canal];
        if (!lista) return;
        lista.innerHTML = '';
        lastDatePainted[canal] = null;
        if (chatStorage[canal]) chatStorage[canal].forEach(msg => agregarBurbuja(msg, lista, canal));
        // Auto-scroll al final para mostrar mensajes más recientes
        // Usamos setTimeout para asegurar que se ejecute después del renderizado DOM
        setTimeout(() => {
            lista.scrollTop = lista.scrollHeight;
        }, 100);
    }
    // --- FUNCIÓN PARA DETECTAR LINKS ---
    function convertirLinks(texto) {
        // Busca cualquier cosa que empiece por http:// o https://
        const urlRegex = /(https?:\/\/[^\s]+)/g;
        return texto.replace(urlRegex, function (url) {
            return `<a href="${url}" target="_blank" class="chat-link">${url}</a>`;
        });
    }
    function agregarBurbuja(data, contenedor, canal) {
        if (canal === 'clash_logs') { const d = document.createElement('div'); d.classList.add('log-msg'); const f = new Date(data.fecha); d.innerHTML = `<span>${data.texto}</span><span class="log-time">${f.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</span>`; contenedor.appendChild(d); contenedor.scrollTop = contenedor.scrollHeight; return; }
        const fechaMsg = data.fecha ? new Date(data.fecha) : new Date(); const diaMsg = fechaMsg.toDateString();
        if (diaMsg !== lastDatePainted[canal]) { const sep = document.createElement('div'); sep.classList.add('date-separator'); sep.textContent = (diaMsg === new Date().toDateString()) ? "Hoy" : fechaMsg.toLocaleDateString(); contenedor.appendChild(sep); lastDatePainted[canal] = diaMsg; }
        const div = document.createElement('div'); div.classList.add('msg'); div.classList.add((currentUser && data.usuario === currentUser.username) ? 'own' : 'other');
        let content = ''; if (data.tipo === 'imagen') content = `<img src="${data.texto}" class="chat-image" onclick="window.abrirMediaModal(this.src, 'imagen')">`; else if (data.tipo === 'video') content = `<video src="${data.texto}" class="chat-video" controls onclick="window.abrirMediaModal(this.src, 'video')"></video>`; else {
            // AQUÍ ESTÁ EL CAMBIO: Usamos la función convertirLinks
            content = `<span class="msg-text">${convertirLinks(data.texto)}</span>`;
        }

        let userHtml = data.usuario; let styleName = ""; if (canal === 'anuncios') { userHtml = "📢 " + data.usuario; styleName = "color:#e94560;font-weight:bold;"; }
        const hora = data.fecha ? new Date(data.fecha).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : '';
        div.innerHTML = `<span class="msg-user" style="${styleName}">${userHtml}</span>${content}<span class="msg-time">${hora}</span>`;
        contenedor.appendChild(div); contenedor.scrollTop = contenedor.scrollHeight;

    }

    function setupChatForm(formId, inputId, canal) { const f = chatElements[canal].form; const i = chatElements[canal].input; if (f && i) { f.addEventListener('submit', (e) => { e.preventDefault(); if (i.value && currentUser) { socket.emit('mensaje_chat', { canal, usuario: currentUser.username, texto: i.value, tipo: 'texto' }); i.value = ''; } }); } }
    setupChatForm(null, null, 'general'); setupChatForm(null, null, 'clash');

    const anuForm = chatElements.anuncios.form; if (anuForm) { anuForm.addEventListener('submit', async (e) => { e.preventDefault(); const i = chatElements.anuncios.input; const fi = chatElements.anuncios.fileInput; const f = fi.files[0]; if (f && currentUser) { if (f.type.startsWith('video')) { /* === VIDEO: Subir por HTTP === */ const btn = anuForm.querySelector('button[type="submit"]'); const originalText = btn.textContent; btn.disabled = true; btn.textContent = '⏳ Subiendo...'; try { const formData = new FormData(); formData.append('file', f); const res = await fetch(API_BASE_URL + '/api/upload', { method: 'POST', body: formData, credentials: 'include' }); if (!res.ok) { const err = await res.json().catch(() => ({})); throw new Error(err.error || 'Error al subir video'); } const data = await res.json(); socket.emit('mensaje_chat', { canal: 'anuncios', usuario: currentUser.username, texto: data.url, tipo: 'video' }); i.value = ''; fi.value = ''; const fn = chatElements.anuncios.fileName; if (fn) { fn.classList.add('hidden'); fn.textContent = ''; } } catch (err) { alert('Error subiendo video: ' + err.message); } finally { btn.disabled = false; btn.textContent = originalText; } } else { /* === IMAGEN: Flujo actual Base64 === */ const r = new FileReader(); r.onload = (ev) => { socket.emit('mensaje_chat', { canal: 'anuncios', usuario: currentUser.username, texto: ev.target.result, tipo: 'imagen' }); i.value = ''; fi.value = ''; const fn = chatElements.anuncios.fileName; if (fn) { fn.classList.add('hidden'); fn.textContent = ''; } }; r.readAsDataURL(f); } } else if (i.value) { socket.emit('mensaje_chat', { canal: 'anuncios', usuario: currentUser.username, texto: i.value, tipo: 'texto' }); i.value = ''; } }); }
    // clash_pics UI was removed - this line cleaned up to prevent errors

    if (socket) {
        socket.on('historial_chat', (data) => {
            if (data.canal && chatStorage[data.canal] !== undefined) {
                chatStorage[data.canal] = data.mensajes;
                // Render chat if user is logged in, otherwise it will be rendered in enterLobby
                if (currentUser) renderizarChat(data.canal);
            }
        });
        socket.on('mensaje_chat', (data) => {
            const canal = data.canal || 'general';
            if (chatStorage[canal] !== undefined) {
                chatStorage[canal].push(data);
                if (currentUser && chatLists[canal]) agregarBurbuja(data, chatLists[canal], canal);
            }
        });
        socket.on('error_busqueda', (m) => { alert(m); actualizarEstadoVisual('normal'); });


        socket.on('partida_encontrada', (data) => {
            alert(`¡RIVAL ENCONTRADO!`);
            currentRoomId = data.salaId;
            maxBetAllowed = data.maxApuesta;

            // Limpieza
            const privateMsgs = document.getElementById('private-messages');
            if (privateMsgs) privateMsgs.innerHTML = '';

            document.getElementById('max-bet-info').textContent = `Tope: $${maxBetAllowed.toLocaleString()}`;

            inputGameMode.value = '';
            inputBetAmount.value = '';
            inputGameMode.disabled = false;
            inputBetAmount.disabled = false;

            btnStartGame.textContent = "🎮 COMENZAR PARTIDA";
            btnStartGame.disabled = true;
            btnStartGame.classList.remove('enabled');
            validationMsg.textContent = "";

            actualizarEstadoVisual('partida_encontrada');
            ejecutarCambioVista('private', null);

            // --- LÓGICA DE ESTADÍSTICAS RIVAL ---
            // 1. Identificar cuál objeto es el rival
            const soyP1 = (data.p1.username === currentUser.username);
            const rivalData = soyP1 ? data.p2 : data.p1;

            // 2. Calcular Win Rate (Evitar división por cero)
            let winRate = 0;
            if (rivalData.total_partidas > 0) {
                winRate = Math.round((rivalData.total_victorias / rivalData.total_partidas) * 100);
            }

            // 3. Calcular Huidas Totales
            const huidas = (rivalData.salidas_chat || 0);

            // 4. Pintar en pantalla
            document.getElementById('rival-name').textContent = `VS ${rivalData.username}`;

            const statsBox = document.getElementById('rival-stats');
            if (statsBox) {
                // Colores dinámicos según qué tan buen jugador sea
                const colorWin = winRate >= 50 ? '#43b581' : '#ed4245';
                const colorFaltas = rivalData.faltas > 0 ? '#ed4245' : '#bbb';

                statsBox.innerHTML = `
                    <span style="color:${colorWin}" title="Win Rate">🏆 ${winRate}%</span>
                    <span style="color:${colorFaltas}" title="Culpable en Disputas">💀 ${rivalData.faltas || 0}</span>
                    <span title="Huidas">🏃 ${huidas}</span>
                `;
            }
        });

        socket.on('juego_iniciado', (data) => {
            const confirmModal = document.getElementById('match-confirm-modal');
            if (confirmModal) confirmModal.classList.add('hidden');
            
            currentUser.estado = 'jugando';
            currentUser.paso_juego = 0;

            actualizarEstadoVisual('jugando');
            ejecutarCambioVista('game_result', null);
            alert("¡JUEGO INICIADO! La API detectará automáticamente el resultado.");
        });

        // Nuevo: Resultado detectado por API
        socket.on('resultado_api', (data) => {
            const statusText = document.getElementById('api-status-text');
            const resultDisplay = document.getElementById('api-result-display');
            const winnerText = document.getElementById('result-winner');
            const crownsText = document.getElementById('result-crowns');

            if (statusText) statusText.textContent = '¡Resultado detectado!';
            if (resultDisplay) resultDisplay.style.display = 'block';

            // Mensaje diferenciado según si ganó o perdió
            if (data.esGanador) {
                if (winnerText) {
                    winnerText.textContent = `🏆 ¡GANASTE! +$${data.premio.toLocaleString()}`;
                    winnerText.style.color = '#43b581';
                }
            } else {
                if (winnerText) {
                    winnerText.textContent = `💀 Perdiste. ${data.ganador} ganó.`;
                    winnerText.style.color = '#ed4245';
                }
            }
            if (crownsText) crownsText.textContent = `Coronas: ${data.crowns}`;

            // Mostrar alert con el mensaje - el usuario debe presionar OK para continuar
            alert(data.mensaje);

            // Después de que el usuario presione OK, redirigir al chat
            // Tanto ganador como perdedor van al chat de Clash
            actualizarEstadoVisual('normal', true);
            ejecutarCambioVista('clash_chat', null);
        });

        // Nuevo: Disputa por timeout
        socket.on('disputa_timeout', (data) => {
            const statusText = document.getElementById('api-status-text');
            if (statusText) statusText.textContent = '⏰ Tiempo agotado';
            alert("⚠️ " + data.mensaje);
        });

        // Nuevo: Disputa creada (empate o error)
        socket.on('disputa_creada', (data) => {
            const statusText = document.getElementById('api-status-text');
            if (statusText) statusText.textContent = '🚨 Disputa creada';
            alert("⚠️ " + data.mensaje);
        });

        socket.on('error_disputa', (msg) => {
            alert("⛔ " + msg);
        });

        socket.on('flujo_completado', () => {
            currentUser.estado = 'normal';
            currentUser.paso_juego = 0;
            actualizarEstadoVisual('normal', true);
            ejecutarCambioVista('clash_chat', null);
        });
        socket.on('match_cancelado', (data) => { alert("⚠️ " + data.motivo); const pm = document.getElementById('private-messages'); if (pm) pm.innerHTML = ''; actualizarEstadoVisual('normal', true); ejecutarCambioVista('clash_chat', null); });
        socket.on('actualizar_negociacion', (data) => { inputGameMode.value = data.modo; inputBetAmount.value = data.dinero; validarNegociacion(); });
        socket.on('mensaje_privado', (data) => agregarBurbuja(data, document.getElementById('private-messages')));
        // --- NOTIFICACIÓN DE PAGOS (NEQUI) ---
        socket.on('transaccion_completada', (data) => {
            // Esto le saldrá solo al usuario que recargó
            alert(data.mensaje);
        });

        // --- PROTECCIÓN CONTRA SESIONES DUPLICADAS ---
        socket.on('sesion_duplicada', async (data) => {
            alert("⚠️ " + data.mensaje + "\n\nSerás redirigido al login.");
            currentUser = null;
            sessionUserId = null;
            // Importante: Borrar cookie antes de redirigir para evitar loop de auto-login
            try {
                await fetch(API_BASE_URL + '/api/logout', { method: 'POST' });
            } catch (e) { console.error(e); }
            // Redirigir al login
            window.location.href = window.location.origin + window.location.pathname + '?kicked=' + Date.now();
        });
    }


    // Game Interactions
    if (btnBuscar) btnBuscar.addEventListener('click', () => { if (!currentUser) return; if (currentUser.saldo < 1000) { alert("Saldo insuficiente"); return; } if (currentUser.estado === 'normal') { actualizarEstadoVisual('buscando_partida'); socket.emit('buscar_partida', currentUser); } else if (currentUser.estado === 'buscando_partida') { actualizarEstadoVisual('normal'); socket.emit('cancelar_busqueda'); } });
    if (btnCancelMatch) btnCancelMatch.addEventListener('click', () => { if (confirm("¿Cancelar?")) socket.emit('cancelar_match', { motivo: 'Oprimió X' }); });

    function validarNegociacion() {
        // 1. Buscar elementos frescos (Para asegurar que no se pierdan)
        const elTexto = document.getElementById('win-text');
        const elInputModo = document.getElementById('input-game-mode');
        const elInputDinero = document.getElementById('input-bet-amount');

        if (!elInputModo || !elInputDinero) return; // Protección

        const modo = elInputModo.value.trim();
        const valorRaw = elInputDinero.value;
        const dinero = parseInt(valorRaw);

        let error = "";

        // 2. Validaciones
        if (modo.length < 3) { }
        else if (!valorRaw) { } // Si está vacío
        else if (isNaN(dinero)) { }
        else if (dinero < 1000) { error = "Mínimo $1.000"; }
        else if (dinero > 10000) { error = "Máximo $10.000"; }
        else if (dinero > maxBetAllowed) { error = `Tope saldos: $${maxBetAllowed}`; }

        // Mostrar error si existe
        const elMsg = document.getElementById('validation-msg');
        if (elMsg) elMsg.textContent = error;

        // 3. CÁLCULO DE GANANCIA (Aquí estaba el problema)
        if (elTexto) {
            if (!isNaN(dinero) && dinero >= 1000) {
                // Hacemos la matemática explícita
                // Hacemos la matemática explícita
                const totalMesa = dinero * 2;
                let porcentajeComision = 0.25 - ((totalMesa - 2000) / 18000) * 0.15;
                if (porcentajeComision > 0.25) porcentajeComision = 0.25;
                if (porcentajeComision < 0.10) porcentajeComision = 0.10;
                const comision = totalMesa * porcentajeComision;
                const ganancia = Math.floor(totalMesa - comision);

                console.log(`Calculando: Apuesta ${dinero} -> Gana ${ganancia}`); // MIRA LA CONSOLA SI FALLA

                elTexto.textContent = `Si ganas recibes: $${ganancia}`;
                elTexto.style.color = "#4ecca3"; // Verde
            } else {
                elTexto.textContent = "Ganancia: $0";
                elTexto.style.color = "#bbb"; // Gris
            }
        }

        // 4. Activar botón
        if (btnStartGame) {
            // No tocar el botón si estamos esperando confirmación del rival
            if (btnStartGame.classList.contains('waiting-cancel')) return;

            if (error === "" && modo.length >= 3 && !isNaN(dinero)) {
                btnStartGame.disabled = false;
                btnStartGame.classList.add('enabled');
            } else {
                btnStartGame.disabled = true;
                btnStartGame.classList.remove('enabled');
            }
        }
    }

    const enviarNegociacion = () => { validarNegociacion(); socket.emit('negociacion_live', { salaId: currentRoomId, modo: inputGameMode.value, dinero: inputBetAmount.value }); };
    if (inputGameMode) inputGameMode.addEventListener('input', enviarNegociacion); if (inputBetAmount) inputBetAmount.addEventListener('input', enviarNegociacion);
