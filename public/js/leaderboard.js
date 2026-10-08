    // --- LEADERBOARD (RANKINGS) ---
    window.cargarLeaderboard = async function (periodo) {
        const table = document.getElementById('leaderboard-table');
        const prizesDiv = document.getElementById('leaderboard-prizes');
        if (!table) return;

        // Actualizar tabs activos (safe for programmatic calls)
        const tabs = document.querySelectorAll('.lb-tab');
        const periodoIdx = { dia: 0, semana: 1, mes: 2, ano: 3, global: 4, monto_torneos: 5, ganado: 6 };
        tabs.forEach((t, i) => {
            t.classList.toggle('active', i === periodoIdx[periodo]);
        });

        table.innerHTML = '<p style="text-align:center; color:#bbb; padding:20px;">⏳ Cargando...</p>';

        try {
            const res = await fetch(API_BASE_URL + '/api/leaderboard/' + periodo);
            const data = await res.json();

            // Mostrar premios del periodo
            if (data.premios && data.premios.length > 0) {
                const medalIcons = ['🥇', '🥈', '🥉', '4️⃣', '5️⃣'];
                prizesDiv.innerHTML = '<div class="prizes-row">' +
                    data.premios.map((p, i) => `<span class="prize-badge">${medalIcons[i] || '🏅'} #${p.posicion}: $${p.premio.toLocaleString()}</span>`).join('') +
                    '</div>';
            } else {
                prizesDiv.innerHTML = '';
            }

            // Renderizar tabla
            if (data.ranking.length === 0) {
                table.innerHTML = '<p style="text-align:center; color:#888; padding:40px;">🏜️ Nadie ha ganado partidas en este periodo aún</p>';
                return;
            }

            const periodoLabel = { dia: 'Hoy', semana: 'Esta semana', mes: 'Este mes', ano: 'Este año', global: 'Histórico', monto_torneos: 'Más Monto en Torneos (Total)', ganado: 'Más Ganado (Total)' };
            const isMoneyTab = (periodo === 'monto_torneos' || periodo === 'ganado');

            let html = `<div class="lb-period-label">${periodoLabel[periodo] || periodo}</div>`;
            html += '<div class="lb-list">';

            data.ranking.forEach((player, idx) => {
                const pos = idx + 1;
                let medalClass = '';
                let medal = `<span class="lb-pos">${pos}</span>`;

                if (pos === 1) { medalClass = 'lb-gold'; medal = '<span class="lb-medal">🥇</span>'; }
                else if (pos === 2) { medalClass = 'lb-silver'; medal = '<span class="lb-medal">🥈</span>'; }
                else if (pos === 3) { medalClass = 'lb-bronze'; medal = '<span class="lb-medal">🥉</span>'; }

                const isMe = currentUser && player.id === currentUser.id;
                const displayValue = isMoneyTab ? player.monto : player.victorias;
                const displayLabel = isMoneyTab ? `$${Number(displayValue).toLocaleString()}` : `${displayValue} victorias`;
                const badgeText = isMoneyTab ? `$${Number(displayValue).toLocaleString()}` : `${displayValue}W`;
                const badgeClass = isMoneyTab ? (periodo === 'monto_torneos' ? 'lb-money-torneos' : 'lb-money-ganado') : 'lb-wins';

                html += `<div class="lb-row ${medalClass} ${isMe ? 'lb-me' : ''}">
                    ${medal}
                    <div class="lb-info">
                        <span class="lb-name">${isMe ? '⭐ ' : ''}${player.username}</span>
                        <span class="lb-stats">${displayLabel} · ${player.total_partidas} partidas</span>
                    </div>
                    <span class="${badgeClass}">${badgeText}</span>
                </div>`;
            });

            html += '</div>';
            table.innerHTML = html;

        } catch (e) {
            console.error('Error cargando leaderboard:', e);
            table.innerHTML = '<p style="text-align:center; color:#ed4245; padding:20px;">❌ Error cargando rankings</p>';
        }
    };

    // Socket: Notificación de premio de leaderboard
    if (socket) {
        socket.on('premio_leaderboard', (data) => {
            mostrarToast(data.mensaje, 10000);
        });

        socket.on('leaderboard_reset', (data) => {
            console.log(`🏆 Leaderboard ${data.periodo} reseteado`);
        });
    }

    window.switchDepositTab = function (tab) {
        document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
        document.querySelectorAll('.deposit-section').forEach(s => s.classList.add('hidden'));
        if (tab === 'manual') {
            const btns = document.querySelectorAll('.tab-btn'); if (btns[0]) btns[0].classList.add('active');
            document.getElementById('tab-manual').classList.remove('hidden');
        } else {
            const btns = document.querySelectorAll('.tab-btn'); if (btns[1]) btns[1].classList.add('active');
            document.getElementById('tab-auto').classList.remove('hidden');
        }
    };

    window.cambiarCanal = function (vista, btn) {
        if (currentUser) {
            if (currentUser.estado === 'jugando' && vista !== 'game_result') { alert("⛔ Espera el resultado de la API"); ejecutarCambioVista('game_result', null); return; }
            if (currentUser.estado === 'partida_encontrada' && vista !== 'private') { if (!confirm("⚠️ ¿SALIR? Se cancelará.")) return; socket.emit('cancelar_match', { motivo: 'Salió del chat' }); return; }
        }
        ejecutarCambioVista(vista, btn);
    };

    function ejecutarCambioVista(vistaName, btn) {
        Object.values(views).forEach(v => { if (v) v.classList.add('hidden'); });

        // Mapeo directo
        let target = views[vistaName];
        if (target) target.classList.remove('hidden');

        if (btn) { document.querySelectorAll('.channel').forEach(c => c.classList.remove('active')); btn.classList.add('active'); }
        if (window.innerWidth <= 768) { if (sidebar) sidebar.classList.remove('open'); if (mobileOverlay) mobileOverlay.classList.remove('open'); }

        // Cargar sorteos cuando se navega a esa vista
        if (vistaName === 'sorteos' && currentUser) {
            cargarSorteos();
            // Mostrar controles admin si es admin
            const adminControls = document.getElementById('sorteos-admin-controls');
            if (adminControls) {
                if (currentUser.tipo_suscripcion === 'admin') {
                    adminControls.classList.remove('hidden');
                } else {
                    adminControls.classList.add('hidden');
                }
            }
        }

        // Cargar leaderboard cuando se navega a esa vista
        if (vistaName === 'leaderboard' && currentUser) {
            cargarLeaderboard('dia');
        }

        // Auto-scroll al final del chat cuando se muestra un canal
        // Usamos setTimeout para dar tiempo al DOM de renderizar
        setTimeout(() => {
            // Mapeo de vista a canal de chat
            const vistaACanalChat = {
                'anuncios': 'anuncios',
                'general': 'general',
                'clash_chat': 'clash',
                'clash_logs': 'clash_logs'
            };
            const canalChat = vistaACanalChat[vistaName];
            if (canalChat && chatLists[canalChat]) {
                chatLists[canalChat].scrollTop = chatLists[canalChat].scrollHeight;
            }
        }, 50);
    }
