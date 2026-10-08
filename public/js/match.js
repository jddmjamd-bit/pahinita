    // --- ACTUALIZACIÓN DE SALDO EN VIVO ---
    socket.on('actualizar_saldo', (nuevoSaldo) => {
        console.log("Socket event: actualizar_saldo recibida", nuevoSaldo);
        if (currentUser) currentUser.saldo = nuevoSaldo;
        if (userBalanceDisplay) userBalanceDisplay.textContent = '$' + nuevoSaldo;
    });

    socket.on('notificacion', (data) => {
        console.log("Socket event: notificacion recibida", data);
        if (data.mensaje) {
            mostrarToast(data.mensaje, 5000);
        }
    });

    // --- LÓGICA DOBLE CONFIRMACIÓN ---
    if (btnStartGame) {
        btnStartGame.addEventListener('click', () => {
            if (!currentUser) return;
            
            if (btnStartGame.textContent === "⏳ ESPERANDO AL RIVAL... (Click para cancelar)") {
                socket.emit('cancelar_inicio');
                btnStartGame.textContent = "🎮 COMENZAR PARTIDA";
                btnStartGame.disabled = false;
                btnStartGame.classList.remove('waiting-cancel');
                btnStartGame.classList.add('enabled');
                btnStartGame.style.backgroundColor = ""; // reset inline style
                btnStartGame.style.pointerEvents = ""; // reset inline pointer-events
                return;
            }

            // Cambiar texto visualmente
            btnStartGame.textContent = "⏳ ESPERANDO AL RIVAL... (Click para cancelar)";
            btnStartGame.classList.remove('enabled');
            btnStartGame.classList.add('waiting-cancel');
            btnStartGame.style.pointerEvents = "all";

            // Enviar voto
            socket.emit('iniciar_juego', {
                dinero: inputBetAmount.value,
                modo: inputGameMode.value
            });
        });
    }

    // --- EVENTOS DE DOBLE CONFIRMACIÓN ---
    socket.on('esperando_inicio_rival', () => {
        if (btnStartGame) {
            btnStartGame.textContent = "⏳ ESPERANDO AL RIVAL... (Click para cancelar)";
            btnStartGame.classList.remove('enabled');
            btnStartGame.classList.add('waiting-cancel');
            btnStartGame.style.pointerEvents = "all";
        }
    });

    socket.on('rival_listo_inicio', () => {
        // Si yo aún no he dado listo, me avisa
        if (btnStartGame && btnStartGame.textContent !== "⏳ ESPERANDO AL RIVAL... (Click para cancelar)") {
            alert("¡Tu rival está listo! Dale a COMENZAR para iniciar.");
        }
    });

    socket.on('error_negociacion', (msg) => {
        alert("⛔ " + msg);
        // Resetear botones
        if (btnStartGame) {
            btnStartGame.textContent = "🎮 COMENZAR PARTIDA";
            btnStartGame.disabled = false;
            btnStartGame.classList.remove('waiting-cancel');
            btnStartGame.classList.add('enabled');
            btnStartGame.style.backgroundColor = "";
            btnStartGame.style.pointerEvents = "";
        }
    });

    // --- MANEJO DE DESCONEXIÓN RIVAL ---

    socket.on('rival_desconectado', (data) => {
        // Mostramos alerta o cambiamos UI
        const statusBadge = document.getElementById('user-status-badge');
        if (statusBadge) {
            statusBadge.className = 'status-indicator status-buscando'; // Color amarillo
            document.getElementById('status-text').textContent = `⚠️ Rival desconectado (Esperando ${data.tiempo}s)`;
        }
        // Opcional: Bloquear botones
        if (btnConfirmResult) btnConfirmResult.disabled = true;
    });

    socket.on('rival_reconectado', (data) => {
        // Restauramos UI
        const rivalName = data && data.username ? data.username : 'Tu rival';
        console.log(`✅ ${rivalName} ha vuelto a la partida`);

        // Restaurar estado visual según el estado actual
        if (currentUser) {
            if (currentUser.estado === 'jugando') actualizarEstadoVisual('jugando');
            else actualizarEstadoVisual('partida_encontrada');
        }

        // Desbloquear botones según el contexto
        if (btnStartGame && currentUser && currentUser.estado === 'partida_encontrada') {
            btnStartGame.disabled = false;
            btnStartGame.classList.add('enabled');
        }
    });

    // --- RESTAURACIÓN DE DATOS AL VOLVER (CORREGIDO) ---
    if (socket) {
        socket.on('restaurar_partida', (data) => {
            console.log("Restaurando datos de partida...", data);

            // 1. Recuperar variables críticas (Esto arregla el chat)
            currentRoomId = data.salaId;
            maxBetAllowed = data.maxMonto;

            // 2. Llenar datos visuales
            document.getElementById('max-bet-info').textContent = `Tope: $${maxBetAllowed.toLocaleString()}`;

            // Nombre del Rival
            const rivalObj = data.rival;
            if (rivalObj) {
                document.getElementById('rival-name').textContent = `VS ${rivalObj.username}`;

                // 3. Calcular y Mostrar Estadísticas del Rival
                let winRate = 0;
                if (rivalObj.total_partidas > 0) {
                    winRate = Math.round((rivalObj.total_victorias / rivalObj.total_partidas) * 100);
                }
                const huidas = (rivalObj.salidas_chat || 0);

                const statsBox = document.getElementById('rival-stats');
                if (statsBox) {
                    const colorWin = winRate >= 50 ? '#43b581' : '#ed4245';
                    const colorFaltas = rivalObj.faltas > 0 ? '#ed4245' : '#bbb';

                    statsBox.innerHTML = `
                        <span style="color:${colorWin}" title="Win Rate">🏆 ${winRate}%</span>
                        <span style="color:${colorFaltas}" title="Culpable en Disputas">💀 ${rivalObj.faltas || 0}</span>
                        <span title="Huidas">🏃 ${huidas}</span>
                    `;
                }
            }

            // Restaurar chat privado
            const privateMsgs = document.getElementById('private-messages');
            if (privateMsgs) privateMsgs.innerHTML = '';
            if (data.historial && data.historial.length > 0) {
                data.historial.forEach(msg => {
                    agregarBurbuja(msg, privateMsgs, 'privado');
                });
            }

            // 4. Restaurar Estado de la UI
            // Si la partida ya inició, bloqueamos los inputs de monto
            if (data.iniciado) {
                inputGameMode.disabled = true;
                inputBetAmount.disabled = true;
                btnStartGame.textContent = "🎮 PARTIDA EN CURSO...";
                btnStartGame.disabled = true;
                btnStartGame.classList.remove('enabled');
            } else {
                // Si estamos negociando, desbloqueamos y rellenamos
                inputGameMode.value = data.lastModo || '';
                inputBetAmount.value = data.lastDinero || '';
                inputGameMode.disabled = false;
                inputBetAmount.disabled = false;
                btnStartGame.textContent = "🎮 COMENZAR PARTIDA";
                btnStartGame.classList.remove('waiting-cancel');
                btnStartGame.style.backgroundColor = "";
                btnStartGame.style.pointerEvents = "";
                
                // Actualizar validación para ver si el botón debe habilitarse
                validarNegociacion();
            }

            // 5. Ir a la vista correcta según el estado
            console.log("🔄 restaurar_partida - Estado:", data.estado);
            actualizarEstadoVisual(data.estado);

            // Navegar a la vista correcta según el estado
            if (data.estado === 'partida_encontrada') {
                ejecutarCambioVista('private', null);
                console.log("➡️ Restaurando vista: private");
            } else if (data.estado === 'jugando') {
                ejecutarCambioVista('game_result', null);
                console.log("➡️ Restaurando vista: game_result");
            }

            console.log("Conexión recuperada y chat reactivado.");
        });

        // --- EVENTOS DE CONFIRMACIÓN DE PARTIDA ---
        socket.on('confirmar_partida', (data) => {
            document.getElementById('confirm-modo').textContent = data.modo;
            document.getElementById('confirm-monto').textContent = data.monto;
            document.getElementById('match-confirm-modal').classList.remove('hidden');
        });

        socket.on('confirmacion_rechazada', () => {
            document.getElementById('match-confirm-modal').classList.add('hidden');
            alert("⚠️ Alguien rechazó la confirmación de la partida.");
            if (btnStartGame) {
                btnStartGame.textContent = "🎮 COMENZAR PARTIDA";
                btnStartGame.disabled = false;
                btnStartGame.classList.remove('waiting-cancel');
                btnStartGame.classList.add('enabled');
                btnStartGame.style.backgroundColor = "";
                btnStartGame.style.pointerEvents = "";
            }
        });

        socket.on('rival_cancelo_inicio', () => {
            alert("⚠️ El rival canceló su voto para iniciar.");
            if (btnStartGame) {
                btnStartGame.textContent = "🎮 COMENZAR PARTIDA";
                btnStartGame.disabled = false;
                btnStartGame.classList.remove('waiting-cancel');
                btnStartGame.classList.add('enabled');
                btnStartGame.style.backgroundColor = "";
                btnStartGame.style.pointerEvents = "";
            }
        });

        const btnAcceptMatch = document.getElementById('btn-accept-match');
        const btnRejectMatch = document.getElementById('btn-reject-match');
        if (btnAcceptMatch) {
            btnAcceptMatch.addEventListener('click', () => {
                socket.emit('confirmar_partida_resp', { acepta: true });
                btnAcceptMatch.disabled = true;
                btnAcceptMatch.textContent = "Esperando...";
            });
        }
        if (btnRejectMatch) {
            btnRejectMatch.addEventListener('click', () => {
                socket.emit('confirmar_partida_resp', { acepta: false });
                document.getElementById('match-confirm-modal').classList.add('hidden');
            });
        }
    }

    if (btnWin && btnLose && btnConfirmResult) {
        btnWin.addEventListener('click', () => {
            resultadoSeleccionado = 'gane';
            btnWin.classList.add('selected');
            btnLose.classList.remove('selected');
            resultText.textContent = "VICTORIA 👑";
            btnConfirmResult.disabled = false;
        });
        btnLose.addEventListener('click', () => {
            resultadoSeleccionado = 'perdi';
            btnLose.classList.add('selected');
            btnWin.classList.remove('selected');
            resultText.textContent = "DERROTA 💀";
            btnConfirmResult.disabled = false;
        });
        btnConfirmResult.addEventListener('click', () => {
            if (resultadoSeleccionado) {
                socket.emit('reportar_resultado', { resultado: resultadoSeleccionado, usuarioId: currentUser.id });
                btnConfirmResult.textContent = "⏳ Esperando al rival...";
                btnConfirmResult.disabled = true;
                btnConfirmResult.style.background = "#faa61a";
            }
        });
    }

    // --- CHAT PRIVADO ---
    if (privateChatForm) {
        privateChatForm.addEventListener('submit', (e) => {
            e.preventDefault();
            const input = document.getElementById('private-input');
            if (input.value && currentRoomId && currentUser) {
                socket.emit('mensaje_privado', {
                    salaId: currentRoomId,
                    usuario: currentUser.username,
                    texto: input.value
                });
                input.value = '';
            }
        });
    }
