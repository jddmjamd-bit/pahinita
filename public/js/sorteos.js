    // --- SISTEMA DE SORTEOS ---

    let userTickets = 0;
    let userAcumulado = 0;
    let sorteos = [];
    let countdownInterval = null;

    // Cargar sorteos desde el servidor
    async function cargarSorteos() {
        try {
            // Cargar tickets del usuario
            if (currentUser) {
                const ticketsRes = await fetch(API_BASE_URL + `/api/raffle/tickets/${currentUser.id}`, { credentials: 'include' });
                const ticketsData = await ticketsRes.json();
                userTickets = ticketsData.tickets || 0;
                userAcumulado = ticketsData.acumulado || 0;
                document.getElementById('user-tickets-count').textContent = userTickets;
                
                const fillElement = document.getElementById('next-ticket-fill');
                const textElement = document.getElementById('next-ticket-text');
                if (fillElement && textElement) {
                    const progresoSiguiente = Math.min(100, (userAcumulado / 1000) * 100);
                    fillElement.style.width = progresoSiguiente + '%';
                    textElement.textContent = `$${Math.round(userAcumulado)} / $1000`;
                }
            }

            // Cargar pozo de premios
            try {
                const poolRes = await fetch(API_BASE_URL + '/api/raffle/pool', { credentials: 'include' });
                const poolData = await poolRes.json();
                const poolAmount = poolData.pool || 0;
                const poolElement = document.getElementById('raffle-pool-amount');
                if (poolElement) poolElement.textContent = '$' + poolAmount.toLocaleString();
            } catch (e) {
                console.error("Error cargando pool:", e);
            }

            // Cargar Encuesta
            await cargarEncuesta();

            // Cargar sorteos activos
            const res = await fetch(API_BASE_URL + '/api/raffle/offers', { credentials: 'include' });
            sorteos = await res.json();
            renderizarSorteos(sorteos);
            iniciarCountdowns();
        } catch (e) {
            console.error("Error cargando sorteos:", e);
        }
    }

    // Renderizar lista de sorteos
    function renderizarSorteos(lista) {
        const container = document.getElementById('sorteos-list');
        if (!container) return;

        if (lista.length === 0) {
            container.innerHTML = '<p class="no-sorteos">No hay sorteos activos en este momento.<br>¡Juega partidas para ganar tickets!</p>';
            return;
        }

        container.innerHTML = lista.map(sorteo => {
            const progreso = Math.min(100, (sorteo.tickets_actuales / sorteo.tickets_necesarios) * 100);
            const misTickets = sorteo.mis_tickets || 0;
            const fechaLimite = new Date(sorteo.fecha_limite);
            const esAdmin = currentUser && currentUser.tipo_suscripcion === 'admin';
            const esCompletado = sorteo.estado === 'completado';

            // Categoría con emoji
            const categoriasEmoji = {
                'gemas': '💎', 'pass': '👑', 'evoluciones': '✨',
                'emotes': '😄', 'cartas': '🃏', 'comodines': '🎴', 'especial': '🎁'
            };
            const emoji = categoriasEmoji[sorteo.categoria] || '🎁';

            // Si está completado, mostrar diseño de ganador
            if (esCompletado) {
                return `
                    <div class="sorteo-card sorteo-ganador" data-id="${sorteo.id}">
                        <div class="sorteo-header">
                            <div>
                                <span class="sorteo-nombre">${emoji} ${sorteo.nombre}</span>
                                <span class="sorteo-categoria">${sorteo.categoria}</span>
                            </div>
                            ${esAdmin ? `<span class="sorteo-precio">$${sorteo.precio.toLocaleString()}</span>` : ''}
                        </div>

                        <div class="sorteo-ganador-info">
                            🏆 GANADOR: <strong>${sorteo.ganador_nombre}</strong>
                        </div>

                        <div class="ticket-progress">
                            <div class="ticket-progress-bar">
                                <div class="ticket-progress-fill" style="width: 100%"></div>
                                <span class="ticket-progress-text">✅ ${sorteo.tickets_necesarios} / ${sorteo.tickets_necesarios} tickets</span>
                            </div>
                        </div>

                        ${esAdmin ? `<button class="btn-eliminar-sorteo" onclick="eliminarSorteo(${sorteo.id})">🗑️ Eliminar</button>` : ''}
                    </div>
                `;
            }

            // Sorteo activo normal
            return `
                <div class="sorteo-card nuevo" data-id="${sorteo.id}">
                    <div class="sorteo-header">
                        <div>
                            <span class="sorteo-nombre">${emoji} ${sorteo.nombre}</span>
                            <span class="sorteo-categoria">${sorteo.categoria}</span>
                        </div>
                        ${esAdmin ? `<span class="sorteo-precio">$${sorteo.precio.toLocaleString()}</span>` : ''}
                    </div>

                    <div class="ticket-progress">
                        <div class="ticket-progress-bar">
                            <div class="ticket-progress-fill" style="width: ${progreso}%"></div>
                            <span class="ticket-progress-text">${sorteo.tickets_actuales} / ${sorteo.tickets_necesarios} tickets</span>
                        </div>
                        <div class="ticket-info">
                            <span class="sorteo-countdown" data-fecha="${sorteo.fecha_limite}">⏰ Cargando...</span>
                            <span>Mis tickets: ${misTickets}</span>
                        </div>
                    </div>

                    <div class="ticket-controls">
                        <button class="ticket-btn minus" onclick="ajustarTickets(${sorteo.id}, -1)" ${misTickets <= 0 ? 'disabled' : ''}>−</button>
                        <span class="my-tickets-count">${misTickets} 🎟️</span>
                        <button class="ticket-btn plus" onclick="ajustarTickets(${sorteo.id}, 1)" ${userTickets <= 0 || sorteo.tickets_actuales >= sorteo.tickets_necesarios ? 'disabled' : ''}>+</button>
                    </div>

                    ${esAdmin ? `<button class="btn-eliminar-sorteo" onclick="eliminarSorteo(${sorteo.id})">🗑️ Eliminar</button>` : ''}
                </div>
            `;
        }).join('');
    }

    // Iniciar countdowns
    function iniciarCountdowns() {
        if (countdownInterval) clearInterval(countdownInterval);
        countdownInterval = setInterval(actualizarCountdowns, 1000);
        actualizarCountdowns();
    }

    function actualizarCountdowns() {
        document.querySelectorAll('.sorteo-countdown').forEach(el => {
            const fecha = new Date(el.dataset.fecha);
            const ahora = new Date();
            const diff = fecha - ahora;

            if (diff <= 0) {
                el.textContent = '⏰ ¡Expirado!';
                el.classList.add('urgente');
                return;
            }

            const horas = Math.floor(diff / (1000 * 60 * 60));
            const minutos = Math.floor((diff % (1000 * 60 * 60)) / (1000 * 60));
            const segundos = Math.floor((diff % (1000 * 60)) / 1000);

            if (horas > 0) {
                el.textContent = `⏰ ${horas}h ${minutos}m`;
            } else if (minutos > 0) {
                el.textContent = `⏰ ${minutos}m ${segundos}s`;
                if (minutos < 5) el.classList.add('urgente');
            } else {
                el.textContent = `⏰ ${segundos}s`;
                el.classList.add('urgente');
            }
        });
    }

    // Ajustar tickets en un sorteo
    window.ajustarTickets = async function (raffleId, delta) {
        if (!currentUser) return alert("Debes iniciar sesión");

        try {
            const res = await fetch(API_BASE_URL + '/api/raffle/participate', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                credentials: 'include',
                body: JSON.stringify({
                    userId: currentUser.id,
                    raffleId: raffleId,
                    ticketsDelta: delta
                })
            });

            const data = await res.json();
            if (!res.ok) {
                return alert(data.error || 'Error al participar');
            }

            // Actualizar UI
            userTickets = data.ticketsUsuario;
            document.getElementById('user-tickets-count').textContent = userTickets;

            // Recargar sorteos para reflejar cambios
            cargarSorteos();
        } catch (e) {
            console.error("Error participando en sorteo:", e);
            alert("Error de conexión");
        }
    };

    // Abrir modal crear sorteo (admin)
    window.abrirModalCrearSorteo = function () {
        document.getElementById('create-raffle-modal').classList.remove('hidden');
    };

    // Preview de tickets al cambiar precio
    const rafflePrecioInput = document.getElementById('raffle-precio');
    if (rafflePrecioInput) {
        rafflePrecioInput.addEventListener('input', () => {
            const precio = parseInt(rafflePrecioInput.value) || 0;
            const tickets = Math.ceil(precio / 1000);
            document.getElementById('raffle-tickets-preview').textContent = `Tickets necesarios: ${tickets}`;
        });
    }

    // Formulario crear sorteo
    const createRaffleForm = document.getElementById('create-raffle-form');
    if (createRaffleForm) {
        createRaffleForm.addEventListener('submit', async (e) => {
            e.preventDefault();

            const nombre = document.getElementById('raffle-nombre').value;
            const categoria = document.getElementById('raffle-categoria').value;
            const precio = parseInt(document.getElementById('raffle-precio').value);
            const duracion = parseInt(document.getElementById('raffle-duracion').value);

            if (!nombre || !precio || !duracion) return alert("Completa todos los campos");

            try {
                const res = await fetch(API_BASE_URL + '/api/admin/raffle/create', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    credentials: 'include',
                    body: JSON.stringify({ nombre, categoria, precio, duracionMinutos: duracion })
                });

                const data = await res.json();
                if (data.success) {
                    alert(`✅ Sorteo "${nombre}" creado con ${data.sorteo.tickets_necesarios} tickets necesarios`);
                    document.getElementById('create-raffle-modal').classList.add('hidden');
                    createRaffleForm.reset();
                    document.getElementById('raffle-tickets-preview').textContent = 'Tickets necesarios: 0';
                    cargarSorteos();
                } else {
                    alert(data.error || 'Error creando sorteo');
                }
            } catch (e) {
                console.error(e);
                alert("Error de conexión");
            }
        });
    }

    // Eliminar sorteo (admin)
    window.eliminarSorteo = async function (id) {
        if (!confirm("¿Eliminar este sorteo? Los tickets serán devueltos a los usuarios.")) return;

        try {
            const res = await fetch(API_BASE_URL + `/api/admin/raffle/${id}`, {
                method: 'DELETE',
                credentials: 'include'
            });
            const data = await res.json();
            if (data.success) {
                alert(data.message);
                cargarSorteos();
            } else {
                alert(data.error);
            }
        } catch (e) {
            console.error(e);
            alert("Error eliminando sorteo");
        }
    };

    // --- ENCUESTA DE PREMIOS ---
    const categoriasEncuesta = {
        'gemas': '💎 Gemas',
        'pass': '👑 Pass Royale',
        'evoluciones': '✨ Evoluciones',
        'emotes': '😄 Emotes',
        'cartas': '🃏 Cartas (Legendarias/Campeones)',
        'comodines': '🎴 Comodines',
        'especial': '🎁 Oferta Especial'
    };

    window.cargarEncuesta = async function() {
        if (!currentUser) return;
        try {
            const res = await fetch(API_BASE_URL + '/api/raffle/poll', { credentials: 'include' });
            const data = await res.json();
            
            // Mostrar botón de reset para admins
            const btnReset = document.getElementById('btn-reset-poll');
            if (btnReset) {
                if (currentUser.tipo_suscripcion === 'admin') btnReset.classList.remove('hidden');
                else btnReset.classList.add('hidden');
            }

            renderizarEncuesta(data.resultados || [], data.miVoto);
        } catch (e) {
            console.error("Error cargando encuesta:", e);
        }
    }

    function renderizarEncuesta(resultados, miVoto) {
        const container = document.getElementById('poll-list');
        if (!container) return;

        let totalVotos = 0;
        const votosPorCat = {};
        
        resultados.forEach(r => {
            totalVotos += parseInt(r.votos) || 0;
            votosPorCat[r.categoria] = parseInt(r.votos) || 0;
        });

        const keys = Object.keys(categoriasEncuesta);
        let html = '';

        keys.forEach(cat => {
            const votos = votosPorCat[cat] || 0;
            const porcentaje = totalVotos > 0 ? Math.round((votos / totalVotos) * 100) : 0;
            const isVoted = miVoto === cat;
            
            html += `
                <div class="poll-item ${isVoted ? 'voted' : ''}" onclick="votarSorteo('${cat}')">
                    <div class="poll-fill" style="width: ${porcentaje}%"></div>
                    <div class="poll-content">
                        <span class="poll-title">${categoriasEncuesta[cat]}</span>
                        <span class="poll-stats">${porcentaje}% (${votos} votos)</span>
                    </div>
                </div>
            `;
        });

        container.innerHTML = html;
    }

    window.votarSorteo = async function(categoria) {
        if (!currentUser) return;
        try {
            const res = await fetch(API_BASE_URL + '/api/raffle/vote', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                credentials: 'include',
                body: JSON.stringify({ userId: currentUser.id, categoria })
            });
            const data = await res.json();
            if (data.error) {
                if (typeof mostrarToast === 'function') mostrarToast(data.error);
                else alert(data.error);
            } else {
                cargarEncuesta();
            }
        } catch (e) {
            console.error("Error votando:", e);
        }
    };

    window.reiniciarEncuesta = async function() {
        if (!confirm("¿Estás seguro de reiniciar la encuesta? Todos los votos se borrarán.")) return;
        try {
            const res = await fetch(API_BASE_URL + '/api/admin/raffle/poll', {
                method: 'DELETE',
                credentials: 'include'
            });
            const data = await res.json();
            if (data.error) alert(data.error);
            else cargarEncuesta();
        } catch (e) {
            console.error("Error reiniciando encuesta:", e);
        }
    };

    // Socket listeners para sorteos
    socket.on('nuevo_sorteo', (sorteo) => {
        console.log("🆕 Nuevo sorteo:", sorteo.nombre);
        // Mostrar notificación toast
        if (typeof mostrarToast === 'function') {
            mostrarToast(`🎁 ¡Nuevo sorteo! <strong>${sorteo.nombre}</strong>`, 5000);
        }
        // Si está en la vista de sorteos, recargar
        if (!views.sorteos.classList.contains('hidden')) {
            cargarSorteos();
        }
    });

    socket.on('sorteo_actualizado', (data) => {
        console.log("📝 Sorteo actualizado:", data.raffleId);
        // Si está en la vista de sorteos, recargar
        if (!views.sorteos.classList.contains('hidden')) {
            cargarSorteos();
        }
    });

    socket.on('sorteo_ganador', (data) => {
        console.log("🏆 Sorteo ganador:", data);
        const esMio = currentUser && data.ganadorId === currentUser.id;

        if (esMio) {
            alert(`🏆 ¡FELICIDADES! ¡GANASTE "${data.nombre}"!\n\nTe contactaremos pronto para entregar tu premio.`);
        } else {
            if (typeof mostrarToast === 'function') {
                mostrarToast(`🏆 <strong>${data.ganadorNombre}</strong> ganó "${data.nombre}"`, 8000);
            }
        }

        // Recargar sorteos
        if (!views.sorteos.classList.contains('hidden')) {
            cargarSorteos();
        }
    });

    socket.on('sorteo_eliminado', (data) => {
        console.log("🗑️ Sorteo eliminado:", data.raffleId);
        if (!views.sorteos.classList.contains('hidden')) {
            cargarSorteos();
        }
    });

    socket.on('poll_reset', () => {
        if (!views.sorteos.classList.contains('hidden')) {
            cargarEncuesta();
        }
    });

    socket.on('poll_updated', () => {
        if (!views.sorteos.classList.contains('hidden')) {
            cargarEncuesta();
        }
    });

    // Listener para tickets ganados y progreso de tickets
    socket.on('tickets_ganados', (data) => {
        if (data.cantidad > 0) {
            console.log(`🎟️ Ganaste ${data.cantidad} ticket(s)`);
            if (typeof mostrarToast === 'function') {
                mostrarToast(`🎟️ ¡Ganaste <strong>${data.cantidad}</strong> ticket(s) para sorteos!`, 5000);
            }
            userTickets += data.cantidad;
            const ticketDisplay = document.getElementById('user-tickets-count');
            if (ticketDisplay) ticketDisplay.textContent = userTickets;
        }

        if (data.acumulado !== undefined) {
            userAcumulado = data.acumulado;
            const fillElement = document.getElementById('next-ticket-fill');
            const textElement = document.getElementById('next-ticket-text');
            if (fillElement && textElement) {
                const progresoSiguiente = Math.min(100, (userAcumulado / 1000) * 100);
                fillElement.style.width = progresoSiguiente + '%';
                textElement.textContent = `$${Math.round(userAcumulado)} / $1000`;
            }
        }
    });

    // EXTRAS
    if (mobileMenuBtn) mobileMenuBtn.addEventListener('click', () => { sidebar.classList.toggle('open'); mobileOverlay.classList.toggle('open'); });
    if (mobileOverlay) mobileOverlay.addEventListener('click', () => { sidebar.classList.remove('open'); mobileOverlay.classList.remove('open'); });
    if (btnLogout) btnLogout.addEventListener('click', async () => {
        // --- LOGOUT MEJORADO: Desconectar y limpiar caché ---
        console.log("🚪 Cerrando sesión...");

        // 1. Desconectar socket primero
        if (socket) socket.disconnect();

        // 2. Limpiar estado local
        currentUser = null;
        sessionUserId = null; // Clear sessionUserId on logout

        // 3. Llamar al servidor para borrar cookie
        await fetch(API_BASE_URL + '/api/logout', { method: 'POST' });

        // 4. Forzar recarga sin caché (evita bfcache) agregando parámetro único
        window.location.href = window.location.origin + window.location.pathname + '?logout=' + Date.now();
    });
    // --- ESCUDO CONTRA RECARGAS ACCIDENTALES ---
    window.addEventListener('beforeunload', (e) => {
        // Solo activamos el escudo si el usuario está en algo importante
        if (currentUser && currentUser.estado !== 'normal') {
            // Mensaje estándar (Los navegadores modernos ignoran el texto personalizado y ponen el suyo propio)
            e.preventDefault();
            e.returnValue = '';
            return '';
        }
        // Si está en estado 'normal' (Libre), dejamos que recargue sin molestar.
    });
    // --- WAKE LOCK (MANTENER PANTALLA ENCENDIDA) ---
    let wakeLock = null;

    async function activarPantalla() {
        try {
            if ('wakeLock' in navigator) {
                wakeLock = await navigator.wakeLock.request('screen');
                console.log('💡 Pantalla mantenida encendida (Wake Lock activo)');
            }
        } catch (err) {
            console.error(`Error al activar Wake Lock: ${err.name}, ${err.message}`);
        }
    }

    // Intentar activar al entrar y al volver a la pestaña
    activarPantalla();

    // --- DETECTAR REAPERTURA DEL NAVEGADOR / PÉRDIDA DE FOCO (CORREGIDO) ---
    let lastVisibleTime = Date.now();

    document.addEventListener('visibilitychange', async () => {
        if (document.visibilityState === 'visible') {
            console.log("👁️ Volviendo al foco — verificando sesión y sincronización...");
            const now = Date.now();
            const inactiveDuration = now - lastVisibleTime;

            // Si estuvo inactivo, evitamos recargar la página (REMOVIDO)

            // Verificar que la sesión sigue siendo del mismo usuario
            const user = await verificarSesion(false);
            if (user && currentUser && user.id !== currentUser.id) {
                console.warn("⚠️ Sesión diferente detectada. Recargando...");
                window.location.reload(true);
                return;
            }

            // Reconectar socket si es necesario
            if (socket && socket.disconnected) {
                console.log("🔌 Reconectando socket...");
                socket.connect();
                // Re-registrar el socket con los datos del usuario
                if (currentUser) socket.emit('registrar_socket', currentUser);
            }
        } else {
            lastVisibleTime = Date.now();
        }
    });
});
