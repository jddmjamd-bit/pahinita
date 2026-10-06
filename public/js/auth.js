    // --- AUTH ---
    if (linkToLogin) linkToLogin.addEventListener('click', (e) => { e.preventDefault(); registroContainer.classList.add('hidden'); loginContainer.classList.remove('hidden'); });
    if (linkToRegister) linkToRegister.addEventListener('click', (e) => { e.preventDefault(); loginContainer.classList.add('hidden'); registroContainer.classList.remove('hidden'); });

    // --- VERIFICACIÓN EN TIEMPO REAL DEL REGISTRO ---
    const usernameInput = document.getElementById('username-input');
    const usernameStatus = document.getElementById('username-status');
    const emailInput = document.getElementById('email-input');
    const emailStatus = document.getElementById('email-status');
    const playerTagInput = document.getElementById('playerTag-input');
    const playerTagStatus = document.getElementById('playerTag-status');

    // Estados de validación
    let usernameValid = false;
    let emailValid = false;
    let playerTagValid = false;

    let usernameTimeout = null;
    let emailTimeout = null;
    let playerTagTimeout = null;

    // Verificación de USERNAME
    if (usernameInput && usernameStatus) {
        usernameInput.addEventListener('input', () => {
            const username = usernameInput.value.trim();
            if (usernameTimeout) clearTimeout(usernameTimeout);
            usernameValid = false;

            if (!username || username.length < 3) {
                usernameStatus.className = 'tag-status visible error';
                usernameStatus.textContent = '⚠️ Mínimo 3 caracteres';
                return;
            }

            usernameStatus.className = 'tag-status visible searching';
            usernameStatus.textContent = '🔍 Verificando...';

            usernameTimeout = setTimeout(async () => {
                try {
                    const res = await fetch(API_BASE_URL + '/api/check-username/' + encodeURIComponent(username));
                    const data = await res.json();
                    if (data.available) {
                        usernameStatus.className = 'tag-status visible found';
                        usernameValid = true;
                    } else {
                        usernameStatus.className = 'tag-status visible not-found';
                        usernameValid = false;
                    }
                    usernameStatus.textContent = data.message;
                } catch (e) {
                    console.error(e);
                    usernameStatus.className = 'tag-status visible error';
                    usernameStatus.textContent = '⚠️ Error de conexión';
                    usernameValid = false;
                }
            }, 500);
        });
    }

    // Verificación de EMAIL
    if (emailInput && emailStatus) {
        emailInput.addEventListener('input', () => {
            const email = emailInput.value.trim();
            if (emailTimeout) clearTimeout(emailTimeout);
            emailValid = false;

            if (!email || !email.match(/^[^\s@]+@[^\s@]+\.[^\s@]+$/)) {
                emailStatus.className = 'tag-status visible error';
                emailStatus.textContent = '⚠️ Formato de correo inválido';
                return;
            }

            emailStatus.className = 'tag-status visible searching';
            emailStatus.textContent = '🔍 Verificando...';

            emailTimeout = setTimeout(async () => {
                try {
                    const res = await fetch(API_BASE_URL + '/api/check-email/' + encodeURIComponent(email));
                    const data = await res.json();
                    if (data.available) {
                        emailStatus.className = 'tag-status visible found';
                        emailValid = true;
                    } else {
                        emailStatus.className = 'tag-status visible not-found';
                        emailValid = false;
                    }
                    emailStatus.textContent = data.message;
                } catch (e) {
                    console.error(e);
                    emailStatus.className = 'tag-status visible error';
                    emailStatus.textContent = '⚠️ Error de conexión';
                    emailValid = false;
                }
            }, 500);
        });
    }

    // Verificación de PLAYER TAG
    if (playerTagInput && playerTagStatus) {
        playerTagInput.addEventListener('input', () => {
            const tag = playerTagInput.value.trim();
            if (playerTagTimeout) clearTimeout(playerTagTimeout);
            playerTagValid = false;

            if (!tag || !tag.startsWith('#')) {
                playerTagStatus.className = 'tag-status';
                return;
            }

            if (!tag.match(/^#[0289PYLQGRJCUV]{3,}$/i)) {
                playerTagStatus.className = 'tag-status visible error';
                playerTagStatus.textContent = '⚠️ Formato inválido';
                return;
            }

            playerTagStatus.className = 'tag-status visible searching';
            playerTagStatus.textContent = '🔍 Verificando...';

            playerTagTimeout = setTimeout(async () => {
                try {
                    const res = await fetch(API_BASE_URL + '/api/verify-tag/' + encodeURIComponent(tag));
                    const data = await res.json();

                    if (data.found) {
                        playerTagStatus.className = 'tag-status visible found';
                        playerTagStatus.innerHTML = `✅ ¿Tu nombre es <strong>${data.name}</strong>? (${data.trophies} 🏆)`;
                        playerTagValid = true;
                    } else {
                        playerTagStatus.className = 'tag-status visible not-found';
                        playerTagStatus.textContent = data.message || '❌ Usuario no encontrado';
                        playerTagValid = false;
                    }
                } catch (e) {
                    console.error(e);
                    playerTagStatus.className = 'tag-status visible error';
                    playerTagStatus.textContent = '⚠️ Error de conexión';
                    playerTagValid = false;
                }
            }, 500);
        });
    }

    if (loginForm) loginForm.addEventListener('submit', async (e) => { e.preventDefault(); try { const res = await fetch(API_BASE_URL + '/api/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(Object.fromEntries(new FormData(loginForm))) }); const r = await res.json(); if (res.ok) { if (!r.user.tipo_suscripcion) r.user.tipo_suscripcion = 'free'; enterLobby(r.user); } else alert(r.error); } catch (e) { console.error(e); } });

    if (registroForm) registroForm.addEventListener('submit', async (e) => {
        e.preventDefault();

        // Validar en orden: username, email, playerTag
        if (!usernameValid) {
            alert('❌ El nombre de usuario no es válido o ya está en uso');
            usernameInput.focus();
            return;
        }
        if (!emailValid) {
            alert('❌ El correo no es válido o ya está registrado');
            emailInput.focus();
            return;
        }
        if (!playerTagValid) {
            alert('❌ El Player Tag no es válido. Debe mostrar tu nombre de Clash Royale');
            playerTagInput.focus();
            return;
        }

        try {
            const res = await fetch(API_BASE_URL + '/api/register', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(Object.fromEntries(new FormData(registroForm)))
            });
            const data = await res.json();
            if (res.ok) {
                alert('¡Cuenta creada! Ahora inicia sesión.');
                registroContainer.classList.add('hidden');
                loginContainer.classList.remove('hidden');
            } else {
                alert('Error: ' + (data.error || 'Revisa los datos'));
            }
        } catch (e) {
            console.error(e);
            alert('Error de conexión');
        }
    });

    function enterLobby(user) {
        currentUser = user;
        sessionUserId = user.id; // Set sessionUserId when entering lobby
        // --- REGISTRAR SOCKET: Siempre emitir (Socket.IO encola si no está conectado) ---
        if (socket) socket.emit('registrar_socket', user);

        // --- REGISTRAR TOKEN FCM SI EXISTE (NATIVO o WEB) ---
        const tokenToRegister = window.fcmToken || window.fcmWebToken;
        if (tokenToRegister && user.id) {
            fetch(API_BASE_URL + '/api/register-token', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ userId: user.id, token: tokenToRegister })
            }).then(() => console.log("🔔 Token FCM registrado al login"))
                .catch(e => console.error("Error registrando token:", e));
        }

        // Recuperamos el ID de la sala si venimos de un recarga
        if (user.sala_actual) {
            currentRoomId = user.sala_actual;
            console.log("Sala recuperada:", currentRoomId);
        }
        authFlow.classList.add('hidden'); discordLobby.classList.remove('hidden');
        if (user.tipo_suscripcion === 'admin') {
            userNameDisplay.innerHTML = `👑 ${user.username} <span style="font-size:0.7rem; color:#e94560;">(ADMIN)</span>`;
            if (btnAdminStats) btnAdminStats.classList.remove('hidden');
            if (chatElements.anuncios.form) chatElements.anuncios.form.classList.remove('hidden'); if (btnAdminPanel) btnAdminPanel.classList.remove('hidden');
        } else userNameDisplay.textContent = user.username;
        userBalanceDisplay.textContent = '$' + user.saldo;

        // --- RESTAURAR ESTADO Y VISTA SEGÚN LA BD (Prioridad absoluta) ---
        console.log("🔄 enterLobby - Estado desde BD:", user.estado, "paso_juego:", user.paso_juego);

        if (user.estado === 'jugando') {
            currentUser.estado = 'jugando';
            currentUser.paso_juego = 0;
            actualizarEstadoVisual('jugando');
            ejecutarCambioVista('game_result', null);
            console.log("➡️ Navegando a game_result (jugando)");
        }
        else if (user.estado === 'partida_encontrada') {
            currentUser.estado = 'partida_encontrada';
            actualizarEstadoVisual('partida_encontrada');
            ejecutarCambioVista('private', null);
            console.log("➡️ Navegando a private (partida_encontrada)");
        }
        else if (user.estado === 'buscando_partida') {
            currentUser.estado = 'buscando_partida';
            actualizarEstadoVisual('buscando_partida');
            console.log("➡️ Restaurado estado visual: buscando_partida");
        }
        else {
            actualizarEstadoVisual('normal');
        }

        ['anuncios', 'general', 'clash', 'clash_logs'].forEach(renderizarChat);
    }

    // Flag para proteger estados activos de ser reseteados accidentalmente
    let estadoProtegido = false;

    function actualizarEstadoVisual(estado, forzar = false) {
        // Protección: No permitir reset a 'normal' si estamos en un estado protegido
        // a menos que sea forzado (por eventos legítimos del servidor)
        if (estado === 'normal' && estadoProtegido && !forzar) {
            console.log("⚠️ Bloqueado reset a 'normal' - estado protegido activo");
            return;
        }

        if (currentUser) currentUser.estado = estado;

        // Activar/desactivar protección según el estado
        estadoProtegido = (estado === 'partida_encontrada' || estado === 'jugando');

        const badge = document.getElementById('user-status-badge');
        const text = document.getElementById('status-text');

        // CONTROL DEL FORMULARIO DE FOTOS (CLASH PICS)
        const picsForm = document.getElementById('clash-pics-form');
        // Mensaje opcional para espectadores
        const picsContainer = document.getElementById('view-clash_pics');

        if (picsForm) {
            // ¿Tiene permiso? (Es Admin O está en el Paso 2)
            const tienePermiso = (estado === 'subiendo_evidencia') || (currentUser && currentUser.tipo_suscripcion === 'admin');

            if (tienePermiso) {
                picsForm.classList.remove('hidden'); // Mostrar botón de enviar
            } else {
                picsForm.classList.add('hidden'); // Ocultar botón de enviar
            }
        }

        // CONTROL DE ETIQUETAS Y BOTÓN JUGAR (Igual que antes)
        if (badge && text) {
            badge.className = 'status-indicator';
            switch (estado) {
                case 'normal':
                    badge.classList.add('status-normal');
                    text.textContent = "🟢 Libre";
                    if (btnBuscar) {
                        btnBuscar.textContent = "⚔️ JUGAR";
                        btnBuscar.disabled = false;
                        btnBuscar.classList.remove('btn-cancelar');
                        btnBuscar.style.opacity = "1";
                        btnBuscar.style.cursor = "pointer";
                    }
                    break;
                case 'buscando_partida':
                    badge.classList.add('status-buscando');
                    text.textContent = "🔍 Buscando...";
                    if (btnBuscar) {
                        btnBuscar.textContent = "❌ CANCELAR";
                        btnBuscar.disabled = false;
                        btnBuscar.classList.add('btn-cancelar');
                        btnBuscar.style.opacity = "1";
                        btnBuscar.style.cursor = "pointer";
                    }
                    break;
                case 'partida_encontrada':
                    badge.classList.add('status-jugando');
                    text.textContent = "⚠️ Encontrada";
                    if (btnBuscar) {
                        btnBuscar.textContent = "🚫 EN JUEGO";
                        btnBuscar.disabled = true;
                        btnBuscar.classList.remove('btn-cancelar');
                        btnBuscar.style.opacity = "0.5";
                        btnBuscar.style.cursor = "not-allowed";
                    }
                    break;
                case 'jugando':
                    badge.classList.add('status-jugando');
                    text.textContent = "🔍 Esperando resultado...";
                    if (btnBuscar) {
                        btnBuscar.textContent = "🚫 JUGANDO";
                        btnBuscar.disabled = true;
                        btnBuscar.style.opacity = "0.5";
                    }
                    break;
                default: text.textContent = estado;
            }
        }
    }
