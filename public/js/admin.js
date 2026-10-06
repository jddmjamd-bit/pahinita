    // --- ADMIN ---
    if (btnAdminPanel) btnAdminPanel.addEventListener('click', () => { adminPanelOverlay.classList.remove('hidden'); cargarTransaccionesAdmin(); });
    // --- ADMIN PANEL MEJORADO (COLORES) ---
    window.cargarTransaccionesAdmin = async () => {
        const res = await fetch(API_BASE_URL + '/api/admin/transactions');
        const list = await res.json();
        const c = document.getElementById('admin-transactions-list');
        c.innerHTML = '';

        if (list.length === 0) c.innerHTML = '<p style="text-align:center;color:#bbb">Nada pendiente.</p>';

        list.forEach(t => {
            const div = document.createElement('div');
            div.className = 'trans-item';
            div.setAttribute('data-trans-id', t.id);

            // Definir color y tipo
            let colorMonto = t.tipo === 'retiro' ? '#ed4245' : '#43b581'; // Rojo si sale, Verde si entra
            let icono = t.tipo === 'retiro' ? '💸 RETIRO' : '💰 RECARGA';

            div.innerHTML = `
                <div class="trans-info">
                    <strong style="color:${colorMonto}">${icono}</strong><br>
                    Usuario: <strong>${t.usuario_nombre}</strong><br>
                    Monto: <span style="color:${colorMonto}; font-size:1.1em;">$${t.monto}</span>
                    <br><span style="font-size:0.8em; color:#bbb;">${t.referencia}</span>
                </div>
                <div class="trans-actions">
                    <button class="btn-approve" onclick="procesarTransaccionAdmin(${t.id},'approve')">✅</button>
                    <button class="btn-reject" onclick="procesarTransaccionAdmin(${t.id},'reject')">❌</button>
                </div>
            `;
            c.appendChild(div);
        });
    };
    window.procesarTransaccionAdmin = async (id, act) => {
        if (!confirm(`¿${act === 'approve' ? 'Aprobar' : 'Rechazar'} esta solicitud?`)) return;
        // Deshabilitar botones de esta transacción mientras se procesa
        const transItem = document.querySelector(`.trans-item[data-trans-id="${id}"]`);
        if (transItem) {
            transItem.querySelectorAll('button').forEach(b => b.disabled = true);
        }
        try {
            const res = await fetch(API_BASE_URL + '/api/admin/transaction/process', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ transId: id, action: act })
            });
            const d = await res.json();
            if (d.error) {
                alert('Error: ' + d.error);
                if (transItem) transItem.querySelectorAll('button').forEach(b => b.disabled = false);
                return;
            }
            // Eliminar la solicitud del DOM con animación
            if (transItem) {
                transItem.style.transition = 'opacity 0.3s, transform 0.3s';
                transItem.style.opacity = '0';
                transItem.style.transform = 'translateX(30px)';
                setTimeout(() => {
                    transItem.remove();
                    // Si no quedan más solicitudes, mostrar mensaje vacío
                    const container = document.getElementById('admin-transactions-list');
                    if (container && container.children.length === 0) {
                        container.innerHTML = '<p style="text-align:center;color:#bbb">Nada pendiente.</p>';
                    }
                }, 300);
            }
        } catch (e) {
            console.error(e);
            alert('Error de conexión');
            if (transItem) transItem.querySelectorAll('button').forEach(b => b.disabled = false);
        }
    };
    // --- LÓGICA DISPUTAS ADMIN (CON CULPABLE) ---
    window.cargarDisputasAdmin = async () => {
        const res = await fetch(API_BASE_URL + '/api/admin/disputes');
        const list = await res.json();
        const c = document.getElementById('admin-disputes-list');
        c.innerHTML = '';
        if (list.length === 0) c.innerHTML = '<p style="color:#bbb">Sin disputas.</p>';

        list.forEach(m => {
            const div = document.createElement('div');
            div.className = 'trans-item';
            div.style.flexDirection = "column"; // Para que quepan los controles
            div.style.alignItems = "flex-start";

            div.innerHTML = `
                <div class="trans-info" style="width:100%; margin-bottom:10px;">
                    <strong>Partida #${m.id}</strong>: <span style="color:#4ecca3">${m.jugador1}</span> vs <span style="color:#ed4245">${m.jugador2}</span>
                    <br>Apuesta: $${m.apuesta}
                </div>

                <div style="width:100%; display:flex; gap:10px; align-items:center; margin-bottom:10px;">
                    <div style="flex:1">
                        <label style="font-size:0.7rem; color:#bbb">GANADOR (Recibe $):</label>
                        <select id="ganador-${m.id}" style="width:100%; padding:5px; background:#202225; color:white; border:1px solid #43b581;">
                            <option value="${m.jugador1}">${m.jugador1}</option>
                            <option value="${m.jugador2}">${m.jugador2}</option>
                        </select>
                    </div>
                    <div style="flex:1">
                        <label style="font-size:0.7rem; color:#bbb">CULPABLE (Falta):</label>
                        <select id="culpable-${m.id}" style="width:100%; padding:5px; background:#202225; color:white; border:1px solid #ed4245;">
                            <option value="nadie">-- Nadie --</option>
                            <option value="${m.jugador1}">${m.jugador1}</option>
                            <option value="${m.jugador2}">${m.jugador2}</option>
                        </select>
                    </div>
                </div>

                <button class="btn-approve" style="width:100%;" onclick="resolverDisputa(${m.id})">
                    ⚖️ DICTAR SENTENCIA
                </button>
            `;
            c.appendChild(div);
        });
    };

    window.resolverDisputa = async (id) => {
        // Obtener valores de los selectores por ID único
        const ganador = document.getElementById(`ganador-${id}`).value;
        const culpable = document.getElementById(`culpable-${id}`).value;

        if (!confirm(`SENTENCIA:\n\n🏆 Gana: ${ganador}\n💀 Culpable: ${culpable}\n\n¿Confirmar?`)) return;

        await fetch(API_BASE_URL + '/api/admin/resolve-dispute', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                matchId: id,
                ganadorNombre: ganador,
                culpableNombre: culpable // <--- Dato Nuevo
            })
        });

        alert("Sentencia aplicada.");
        cargarDisputasAdmin();
    };

    // Modifica el botón de abrir panel para cargar ambas listas
    if (btnAdminPanel) btnAdminPanel.addEventListener('click', () => {
        adminPanelOverlay.classList.remove('hidden');
        cargarTransaccionesAdmin();
        cargarDisputasAdmin(); // <--- NUEVO
    });
    // --- PANEL FINANCIERO ---
    if (btnAdminStats) btnAdminStats.addEventListener('click', () => {
        adminStatsOverlay.classList.remove('hidden');
        cargarEstadisticasAdmin();
    });

    window.cargarEstadisticasAdmin = async () => {
        const res = await fetch(API_BASE_URL + '/api/admin/stats');
        const data = await res.json();

        // Llenar cuadros grandes
        document.getElementById('stat-users-money').textContent = '$' + data.totalUsuarios.toLocaleString();
        document.getElementById('stat-admin-money').textContent = '$' + data.totalGanancias.toLocaleString();

        // Llenar desglose de categorías
        const breakdownContainer = document.getElementById('admin-category-breakdown');
        if (breakdownContainer && data.desglose) {
            breakdownContainer.innerHTML = '';
            const iconos = {
                sorteos: '🎰', misiones: '📋', logros: '🏅', 
                leaderboard: '🏆', devolucion: '🔄', ganancia: '💰', referidos: '👥'
            };
            const nombres = {
                sorteos: 'Sorteos (20%)', misiones: 'Misiones (10%)', logros: 'Logros (5%)', 
                leaderboard: 'Leaderboard (15%)', devolucion: 'Devolución (15%)', ganancia: 'Ganancia (25%)', referidos: 'Referidos (10%)'
            };
            
            for (const [cat, monto] of Object.entries(data.desglose)) {
                const div = document.createElement('div');
                div.style = "background:#202225; padding:10px; border-radius:5px; text-align:center; border: 1px solid #2f3136;";
                div.innerHTML = `
                    <div style="font-size: 1.5rem; margin-bottom: 5px;">${iconos[cat] || '📌'}</div>
                    <div style="font-size: 0.75rem; color: #bbb; margin-bottom: 5px;">${nombres[cat] || cat}</div>
                    <div style="color: #4ecca3; font-weight: bold;">Actual: $${monto.toLocaleString()}</div>
                    <div style="color: #faa61a; font-size: 0.8rem; margin-top: 3px;">Histórico: $${monto.toLocaleString()}</div>
                `;
                breakdownContainer.appendChild(div);
            }
        }

        // Llenar lista usuarios
        const lista = document.getElementById('admin-users-list');
        lista.innerHTML = '';

        data.listaUsuarios.forEach(u => {
            const div = document.createElement('div');
            div.className = 'user-card'; // Clase nueva del CSS

            const rol = u.tipo_suscripcion === 'admin' ? '👑' : '👤';

            // Construimos el HTML detallado
            div.innerHTML = `
                <div class="user-header-row">
                    <div class="user-basic">
                        <span style="font-size:1.1rem;">${rol} <strong>${u.username}</strong></span>
                        <br><span style="color:#bbb; font-size:0.8rem;">${u.email}</span>
                    </div>
                    <div class="user-financials">
                        <div style="color:#fff;">Saldo: <span style="color:#4ecca3;">$${u.saldo.toLocaleString()}</span></div>
                        <div style="font-size:0.8rem;">Generado: <span style="color:#faa61a;">+$${(u.ganancia_generada || 0).toLocaleString()}</span></div>
                    </div>
                </div>

                <div class="stats-grid">
                    <!-- FILA 1: GENERAL -->
                    <div class="stat-item"><span class="stat-label">PARTIDAS</span><span class="stat-val">${u.total_partidas || 0}</span></div>
                    <div class="stat-item"><span class="stat-label">VICTORIAS</span><span class="stat-val val-green">${u.total_victorias || 0}</span> <span style="font-size:0.6em">(${u.victorias_normales}/${u.victorias_disputa})</span></div>
                    <div class="stat-item"><span class="stat-label">DERROTAS</span><span class="stat-val val-red">${u.total_derrotas || 0}</span> <span style="font-size:0.6em">(${u.derrotas_normales}/${u.derrotas_disputa})</span></div>

                    <!-- FILA 2: COMPORTAMIENTO -->
                    <div class="stat-item"><span class="stat-label">FALTAS (JUEZ)</span><span class="stat-val val-red">${u.faltas || 0}</span></div>
                    <div class="stat-item"><span class="stat-label">HUIDAS TOTALES</span><span class="stat-val val-gold">${u.salidas_chat || 0}</span></div>
                    <div class="stat-item"><span class="stat-label">DETALLE HUIDAS</span><span class="stat-val" style="font-size:0.65em">X:${u.salidas_x} | Nav:${u.salidas_canal} | Desc:${u.salidas_desconexion}</span></div>
                </div>

                <div style="margin-top: 10px; background: #2f3136; padding: 8px; border-radius: 5px;">
                    <div style="font-size: 0.75rem; color: #bbb; margin-bottom: 5px; text-transform: uppercase;">Aportes del usuario a las categorías</div>
                    <div style="display: grid; grid-template-columns: repeat(auto-fit, minmax(100px, 1fr)); gap: 5px;">
                        <div class="stat-item" style="background:#202225; padding:5px; flex-direction: column; align-items: center;">
                            <span class="stat-label">🎰 Sorteos</span>
                            <span style="color:#4ecca3; font-size:0.75rem; font-weight:bold; margin-top:2px;">Act: $${(u.gen_sorteos || 0).toLocaleString()}</span>
                            <span style="color:#faa61a; font-size:0.65rem;">Hist: $${(u.gen_sorteos || 0).toLocaleString()}</span>
                        </div>
                        <div class="stat-item" style="background:#202225; padding:5px; flex-direction: column; align-items: center;">
                            <span class="stat-label">📋 Misiones</span>
                            <span style="color:#4ecca3; font-size:0.75rem; font-weight:bold; margin-top:2px;">Act: $${(u.gen_misiones || 0).toLocaleString()}</span>
                            <span style="color:#faa61a; font-size:0.65rem;">Hist: $${(u.gen_misiones || 0).toLocaleString()}</span>
                        </div>
                        <div class="stat-item" style="background:#202225; padding:5px; flex-direction: column; align-items: center;">
                            <span class="stat-label">🏅 Logros</span>
                            <span style="color:#4ecca3; font-size:0.75rem; font-weight:bold; margin-top:2px;">Act: $${(u.gen_logros || 0).toLocaleString()}</span>
                            <span style="color:#faa61a; font-size:0.65rem;">Hist: $${(u.gen_logros || 0).toLocaleString()}</span>
                        </div>
                        <div class="stat-item" style="background:#202225; padding:5px; flex-direction: column; align-items: center;">
                            <span class="stat-label">🏆 Leaderboard</span>
                            <span style="color:#4ecca3; font-size:0.75rem; font-weight:bold; margin-top:2px;">Act: $${(u.gen_leaderboard || 0).toLocaleString()}</span>
                            <span style="color:#faa61a; font-size:0.65rem;">Hist: $${(u.gen_leaderboard || 0).toLocaleString()}</span>
                        </div>
                        <div class="stat-item" style="background:#202225; padding:5px; flex-direction: column; align-items: center;">
                            <span class="stat-label">🔄 Devolución</span>
                            <span style="color:#4ecca3; font-size:0.75rem; font-weight:bold; margin-top:2px;">Act: $${(u.gen_devolucion || 0).toLocaleString()}</span>
                            <span style="color:#faa61a; font-size:0.65rem;">Hist: $${(u.gen_devolucion || 0).toLocaleString()}</span>
                        </div>
                        <div class="stat-item" style="background:#202225; padding:5px; flex-direction: column; align-items: center;">
                            <span class="stat-label">👥 Referidos</span>
                            <span style="color:#4ecca3; font-size:0.75rem; font-weight:bold; margin-top:2px;">Act: $${(u.gen_referidos || 0).toLocaleString()}</span>
                            <span style="color:#faa61a; font-size:0.65rem;">Hist: $${(u.gen_referidos || 0).toLocaleString()}</span>
                        </div>
                        <div class="stat-item" style="background:#202225; padding:5px; border: 1px solid #faa61a; flex-direction: column; align-items: center;">
                            <span class="stat-label">💰 Ganancia Neta</span>
                            <span style="color:#4ecca3; font-size:0.75rem; font-weight:bold; margin-top:2px;">Act: $${(u.ganancia_generada || 0).toLocaleString()}</span>
                            <span style="color:#faa61a; font-size:0.65rem;">Hist: $${(u.ganancia_generada || 0).toLocaleString()}</span>
                        </div>
                    </div>
                </div>
            `;
            lista.appendChild(div);
        });
    };
