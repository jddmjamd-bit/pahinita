let socket;
let sessionUserId = null; // ID del usuario de la sesión actual para detectar cambios

// --- CONFIGURACIÓN PARA APP MÓVIL CAPACITOR ---
const isNativeApp = typeof window.Capacitor !== 'undefined';
const API_BASE_URL = isNativeApp ? 'https://torneos-beta.onrender.com' : '';
console.log(`📱 Modo: ${isNativeApp ? 'APP NATIVA' : 'WEB'}, API: ${API_BASE_URL || 'local'}`);

// --- FUNCIÓN GLOBAL DE VERIFICACIÓN DE SESIÓN (Accesible desde visibilitychange) ---
async function verificarSesion(enterIfValid = true) {
    try {
        const res = await fetch(API_BASE_URL + '/api/session', {
            headers: { 'Cache-Control': 'no-cache, no-store, must-revalidate' },
            credentials: 'include'
        });
        if (res.ok) {
            const data = await res.json();
            console.log("🍪 Sesión verificada:", data.user.username);
            // Detectar si cambió el usuario (otra cuenta)
            if (sessionUserId && sessionUserId !== data.user.id) {
                console.warn("⚠️ Usuario diferente detectado. Recargando...");
                window.location.reload(true);
                return null;
            }
            sessionUserId = data.user.id;
            return data.user;
        }
    } catch (e) {
        console.log("No hay sesión activa.");
    }
    return null;
}

document.addEventListener('DOMContentLoaded', () => {
    console.log("✅ SISTEMA V8 - CLASH ROYALE API READY");

    // --- SOLICITAR PERMISOS EN APP NATIVA ---
    if (isNativeApp) {
        console.log("📱 App nativa detectada - Solicitando permisos...");

        // --- PUSH NOTIFICATIONS - Solicitar permiso al inicio ---
        if (window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.PushNotifications) {
            const PushNotifications = window.Capacitor.Plugins.PushNotifications;
            const LocalNotifications = window.Capacitor.Plugins.LocalNotifications;

            // Crear canal de notificaciones de alta prioridad (Android)
            if (LocalNotifications && LocalNotifications.createChannel) {
                LocalNotifications.createChannel({
                    id: 'torneos_high_priority',
                    name: 'Torneos Flash',
                    description: 'Notificaciones de partidas y mensajes',
                    importance: 5, // IMPORTANCE_HIGH - muestra heads-up
                    visibility: 1, // VISIBILITY_PUBLIC
                    sound: 'default',
                    vibration: true,
                    lights: true
                }).then(() => console.log("🔔 Canal de notificaciones creado"))
                    .catch(e => console.log("🔔 Error creando canal:", e));
            }

            // Solicitar permisos INMEDIATAMENTE al inicio (dispara diálogo nativo de Android)
            PushNotifications.requestPermissions().then(result => {
                console.log("🔔 Permisos push solicitados:", result);
                if (result.receive === 'granted') {
                    PushNotifications.register();
                }
            }).catch(e => console.log("🔔 Error pidiendo permisos push:", e));

            // Cuando se registra exitosamente, guardar token
            PushNotifications.addListener('registration', async (token) => {
                console.log("🔔 Token FCM recibido:", token.value);
                window.fcmToken = token.value;

                // Si ya tenemos usuario logueado, registrar token
                if (sessionUserId) {
                    try {
                        await fetch(API_BASE_URL + '/api/register-token', {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({ userId: sessionUserId, token: token.value })
                        });
                        console.log("🔔 Token registrado en servidor");
                    } catch (e) {
                        console.error("Error registrando token:", e);
                    }
                }
            });

            // Error de registro
            PushNotifications.addListener('registrationError', (error) => {
                console.error("🔔 Error registrando push:", error);
            });

            // Limpiar notificaciones cuando la app está en foreground
            PushNotifications.removeAllDeliveredNotifications()
                .then(() => console.log("🔔 Notificaciones limpiadas al iniciar"))
                .catch(() => { });

            // Limpiar notificaciones cuando la app vuelve a foreground
            document.addEventListener('visibilitychange', () => {
                if (!document.hidden) {
                    PushNotifications.removeAllDeliveredNotifications()
                        .then(() => console.log("🔔 Notificaciones limpiadas al volver a foreground"))
                        .catch(() => { });
                }
            });

            // Almacenar IDs de notificaciones de búsqueda para poder eliminarlas individualmente
            const notificacionesBusqueda = {};

            // Push recibida (cuando la app está abierta)
            PushNotifications.addListener('pushNotificationReceived', (notification) => {
                console.log("🔔 Push recibida en app abierta:", notification);
                const data = notification.data || {};

                // Manejar eliminación de notificación específica
                if (data.action === 'remove_notification' && data.notificationId) {
                    console.log("🔔 Solicitud de eliminar notificación:", data.notificationId);
                    // En Android, la notificación ya debería estar en la barra
                    // Usamos el tag/notificationId para eliminarla
                    PushNotifications.removeAllDeliveredNotifications()
                        .then(() => console.log("🔔 Notificaciones eliminadas por solicitud"))
                        .catch(() => { });
                    return;
                }

                // Guardar referencia si es notificación de búsqueda
                if (data.tipo === 'busqueda' && data.oderId) {
                    notificacionesBusqueda[data.oderId] = data.notificationId;
                }
            });

            // Usuario tocó la notificación (desde el sistema)
            PushNotifications.addListener('pushNotificationActionPerformed', (notification) => {
                console.log("🔔 Push tocada:", notification);
                const data = notification.notification?.data || {};

                // Navegar según el tipo de notificación
                if (data.tipo === 'match_found') {
                    ejecutarCambioVista('private', null);
                } else if (data.tipo === 'chat') {
                    const vista = data.canal === 'general' ? 'general' : 'clash_chat';
                    ejecutarCambioVista(vista, null);
                }

                // Limpiar todas las notificaciones después de tocar una
                PushNotifications.removeAllDeliveredNotifications().catch(() => { });
            });
        } else {
            console.log("⚠️ Plugin PushNotifications no disponible");
        }
    } else {
        // En web, pedir permiso de micrófono via API web
        navigator.mediaDevices.getUserMedia({ audio: true })
            .then(() => console.log("🎤 Permiso de micrófono concedido"))
            .catch(e => console.log("🎤 Permiso de micrófono denegado:", e.message));
    }

    // --- AUTO-LOGIN CON COOKIES ---
    verificarSesion(true).then(user => {
        if (user) enterLobby(user);
    });

    // Socket.IO - Conexión remota para app móvil, local para web
    try {
        socket = isNativeApp
            ? io(API_BASE_URL, { transports: ['websocket', 'polling'], withCredentials: true })
            : io({ transports: ['websocket', 'polling'], withCredentials: true });

        // --- TOAST NOTIFICATION IN-APP ---
        function mostrarToast(mensaje, duracion = 600000) {
            // Crear o reusar contenedor de toasts
            let container = document.getElementById('toast-container');
            if (!container) {
                container = document.createElement('div');
                container.id = 'toast-container';
                container.style.cssText = 'position:fixed;top:70px;right:10px;z-index:9999;display:flex;flex-direction:column;gap:8px;';
                document.body.appendChild(container);
            }

            const toast = document.createElement('div');
            toast.style.cssText = 'background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);color:#fff;padding:12px 20px;border-radius:10px;box-shadow:0 4px 15px rgba(0,0,0,0.3);font-size:14px;animation:slideIn 0.3s ease;max-width:280px;';
            toast.innerHTML = mensaje;
            container.appendChild(toast);

            // Agregar animación si no existe
            if (!document.getElementById('toast-styles')) {
                const style = document.createElement('style');
                style.id = 'toast-styles';
                style.textContent = '@keyframes slideIn{from{transform:translateX(100%);opacity:0}to{transform:translateX(0);opacity:1}}@keyframes slideOut{from{transform:translateX(0);opacity:1}to{transform:translateX(100%);opacity:0}}';
                document.head.appendChild(style);
            }

            setTimeout(() => {
                toast.style.animation = 'slideOut 0.3s ease';
                setTimeout(() => toast.remove(), 300);
            }, duracion);

            return toast; // Retornar referencia al toast
        }

        // Almacenar toasts de búsqueda por userId para poder eliminarlos
        const toastsBusqueda = {};

        // Listener: Alguien está buscando partida
        socket.on('alguien_buscando', (data) => {
            // No mostrar si soy yo quien busca
            if (currentUser && data.oderId === currentUser.id) return;

            const toast = mostrarToast(`🔍 <strong>${data.username}</strong> está buscando partida!`);
            toastsBusqueda[data.oderId] = toast;
        });

        // Listener: Alguien canceló la búsqueda - quitar su toast
        socket.on('busqueda_cancelada', (data) => {
            // Eliminar el toast de búsqueda de este usuario si existe
            if (toastsBusqueda[data.oderId]) {
                const toast = toastsBusqueda[data.oderId];
                toast.style.animation = 'slideOut 0.3s ease';
                setTimeout(() => toast.remove(), 300);
                delete toastsBusqueda[data.oderId];
            }
        });

        // Listener: Mi búsqueda fue cancelada por timeout
        socket.on('busqueda_timeout', (data) => {
            alert(data.mensaje);
            // Actualizar estado visual a normal
            if (typeof actualizarEstadoVisual === 'function') {
                actualizarEstadoVisual('normal', true);
            }
        });

        // Listener: Reconecté y sigo buscando partida
        socket.on('buscando_activo', (data) => {
            console.log("🔄 Reconectado con búsqueda activa");
            if (typeof actualizarEstadoVisual === 'function') {
                actualizarEstadoVisual('buscando', true);
            }
        });

    } catch (e) { console.error(e); }

    // --- FIX MAESTRO: AUTO-RECARGA POR SUSPENSIÓN ---

    // 1. Detectar si Chrome restauró la página desde la memoria (La "Foto")
    window.addEventListener('pageshow', (event) => {
        // 'persisted' es true si la página no se cargó de la red, sino del caché
        if (event.persisted) {
            console.log("♻️ Página restaurada de caché. Forzando recarga...");
            window.location.reload();
        }
    });

    // 2. Detectar si el celular "durmió" la aplicación (Suspensión)
    let lastTime = Date.now();

    setInterval(() => {
        const currentTime = Date.now();
        // Si han pasado más de 4 segundos entre un tic y otro (y el intervalo es de 2s),
        // significa que el sistema operativo congeló la app en medio.
        if (currentTime > (lastTime + 4000)) {
            console.log("⏰ ¡El celular se durmió! Recargando para sincronizar...");
            window.location.reload();
        }
        lastTime = currentTime;
    }, 2000);

    let currentUser = null;
    let currentRoomId = null;
    let maxBetAllowed = 0;
    let chatStorage = { anuncios: [], general: [], clash: [], clash_logs: [] };
    let lastDatePainted = { anuncios: null, general: null, clash: null, clash_logs: null };
    let resultadoSeleccionado = null;

    // REFERENCIAS DOM
    const authFlow = document.getElementById('auth-flow');
    const discordLobby = document.getElementById('discord-lobby');
    const loginForm = document.getElementById('login-form');
    const registroForm = document.getElementById('registro-form');
    const loginContainer = document.getElementById('login-container');
    const registroContainer = document.getElementById('registro-container');
    const linkToLogin = document.getElementById('ir-a-login');
    const linkToRegister = document.getElementById('ir-a-registro');
    const userNameDisplay = document.getElementById('user-name-display');
    const userBalanceDisplay = document.getElementById('user-balance');
    const btnOpenDeposit = document.getElementById('btn-open-deposit');
    const btnAdminPanel = document.getElementById('btn-admin-panel');
    const btnLogout = document.getElementById('btn-logout');
    const mobileMenuBtn = document.getElementById('mobile-menu-btn');
    const sidebar = document.getElementById('sidebar');
    const mobileOverlay = document.getElementById('mobile-overlay');
    const depositModal = document.getElementById('deposit-modal');
    const closeDepositModal = document.getElementById('close-deposit-modal');
    const btnManualDeposit = document.getElementById('btn-manual-deposit');
    const btnAutoDeposit = document.getElementById('btn-auto-deposit');
    const autoInput = document.getElementById('auto-amount-input');
    const feeDisplay = document.getElementById('fee-display');
    const totalDisplay = document.getElementById('total-pay-display');
    const costBreakdown = document.getElementById('cost-breakdown');
    const adminPanelOverlay = document.getElementById('admin-panel-overlay');
    const btnBuscar = document.getElementById('btn-buscar-partida');
    const btnCancelMatch = document.getElementById('btn-cancel-match');
    const btnStartGame = document.getElementById('btn-start-game');
    const inputGameMode = document.getElementById('input-game-mode');
    const inputBetAmount = document.getElementById('input-bet-amount');
    const validationMsg = document.getElementById('validation-msg');
    const maxBetInfo = document.getElementById('max-bet-info');
    const btnWin = document.getElementById('btn-win');
    const btnLose = document.getElementById('btn-lose');
    const btnConfirmResult = document.getElementById('btn-confirm-result');
    const resultText = document.getElementById('result-selection-text');
    const privateChatForm = document.getElementById('private-chat-form');
    const btnAdminStats = document.getElementById('btn-admin-stats');
    const adminStatsOverlay = document.getElementById('admin-stats-overlay');
    // RETIROS UI
    const btnOpenWithdraw = document.getElementById('btn-open-withdraw');
    const withdrawModal = document.getElementById('withdraw-modal');
    const closeWithdrawModal = document.getElementById('close-withdraw-modal');
    const btnSubmitWithdraw = document.getElementById('btn-submit-withdraw');

    // --- VISTAS (IDs CON GUION MEDIO) ---
    const views = {
        anuncios: document.getElementById('view-anuncios'),
        general: document.getElementById('view-general'),
        clash_chat: document.getElementById('view-clash-chat'),
        clash_logs: document.getElementById('view-clash-logs'),
        sorteos: document.getElementById('view-sorteos'),
        private: document.getElementById('view-private'),
        game_result: document.getElementById('view-game-result'),
        leaderboard: document.getElementById('view-leaderboard')
    };

    // --- LISTAS DE CHAT (IDs EXPLICITOS) ---
    const chatLists = {
        anuncios: document.getElementById('anuncios-messages-list'),
        general: document.getElementById('general-messages-list'),
        clash: document.getElementById('clash-messages-list'), // General Clash
        clash_logs: document.getElementById('logs-messages-list') // Registro
    };

    const chatElements = {
        anuncios: { form: document.getElementById('anuncios-chat-form'), input: document.getElementById('anuncios-msg-input'), fileInput: document.getElementById('anuncios-file-input'), fileName: document.getElementById('anuncios-file-name') },
        general: { form: document.getElementById('general-chat-form'), input: document.getElementById('general-msg-input') },
        clash: { form: document.getElementById('clash-chat-form'), input: document.getElementById('clash-msg-input') }
    };

    // --- FUNCIONES GLOBALES ---
    window.toggleDropdown = function (id) { const m = document.getElementById(id); if (m) m.classList.toggle('hidden'); };

    // --- LEADERBOARD (RANKINGS) ---
    window.cargarLeaderboard = async function (periodo) {
        const table = document.getElementById('leaderboard-table');
        const prizesDiv = document.getElementById('leaderboard-prizes');
        if (!table) return;

        // Actualizar tabs activos (safe for programmatic calls)
        const tabs = document.querySelectorAll('.lb-tab');
        const periodoIdx = { dia: 0, semana: 1, mes: 2, ano: 3, global: 4, apostado: 5, ganado: 6 };
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

            const periodoLabel = { dia: 'Hoy', semana: 'Esta semana', mes: 'Este mes', ano: 'Este año', global: 'Histórico', apostado: 'Más Apostado (Total)', ganado: 'Más Ganado (Total)' };
            const isMoneyTab = (periodo === 'apostado' || periodo === 'ganado');

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
                const badgeClass = isMoneyTab ? (periodo === 'apostado' ? 'lb-money-apostado' : 'lb-money-ganado') : 'lb-wins';

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
                    playerTagStatus.className = 'tag-status visible error';
                    playerTagStatus.textContent = '⚠️ Error de conexión';
                    playerTagValid = false;
                }
            }, 500);
        });
    }

    if (loginForm) loginForm.addEventListener('submit', async (e) => { e.preventDefault(); try { const res = await fetch(API_BASE_URL + '/api/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(Object.fromEntries(new FormData(loginForm))) }); const r = await res.json(); if (res.ok) { if (!r.user.tipo_suscripcion) r.user.tipo_suscripcion = 'free'; enterLobby(r.user); } else alert(r.error); } catch (e) { } });

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
            alert('Error de conexión');
        }
    });

    function enterLobby(user) {
        currentUser = user;
        sessionUserId = user.id; // Set sessionUserId when entering lobby
        // --- REGISTRAR SOCKET: Siempre emitir (Socket.IO encola si no está conectado) ---
        if (socket) socket.emit('registrar_socket', user);

        // --- REGISTRAR TOKEN FCM SI EXISTE ---
        if (window.fcmToken && user.id) {
            fetch(API_BASE_URL + '/api/register-token', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ userId: user.id, token: window.fcmToken })
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

    // --- PAGOS ---
    if (btnOpenDeposit) btnOpenDeposit.addEventListener('click', () => depositModal.classList.remove('hidden'));
    if (closeDepositModal) closeDepositModal.addEventListener('click', () => depositModal.classList.add('hidden'));
    if (autoInput) {
        autoInput.addEventListener('input', () => {
            const val = parseInt(autoInput.value);

            // Validación mínima
            if (!val || val < 1000) {
                costBreakdown.classList.add('hidden');
                btnAutoDeposit.disabled = true;
                btnAutoDeposit.textContent = "Pagar con Tarjeta";
                return;
            }

            // --- FÓRMULA DE COMISIÓN (TARIFA CARA) ---
            // Fórmula: ((Valor * 1.3) + 700) * 1.2
            const baseConMargen = val + 840;
            const totalPagar = Math.ceil(baseConMargen / 0.964);

            const comisionTotal = totalPagar - val;

            // Mostrar resultados con separadores de miles (ej: 10.000)
            feeDisplay.textContent = `+ $${comisionTotal.toLocaleString()}`;
            totalDisplay.textContent = `$${totalPagar.toLocaleString()}`;

            costBreakdown.classList.remove('hidden');

            btnAutoDeposit.disabled = false;
            btnAutoDeposit.textContent = `Pagar $${totalPagar.toLocaleString()}`;
        });
    }
    if (btnManualDeposit) btnManualDeposit.addEventListener('click', async () => { const m = document.getElementById('manual-amount').value; const r = document.getElementById('manual-ref').value; if (!m || !r) return alert("Datos?"); const res = await fetch(API_BASE_URL + '/api/transaction/create', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ userId: currentUser.id, username: currentUser.username, tipo: 'deposito', metodo: 'manual_nequi', monto: m, referencia: r }) }); const d = await res.json(); alert(d.message); depositModal.classList.add('hidden'); });
    if (btnAutoDeposit) {
        btnAutoDeposit.addEventListener('click', async () => {
            const monto = autoInput.value;
            if (!monto || !currentUser) return;

            btnAutoDeposit.disabled = true;
            btnAutoDeposit.textContent = "Cargando Wompi...";

            try {
                // 1. Pedir datos de transacción al servidor
                const res = await fetch(API_BASE_URL + '/api/wompi/init', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        userId: currentUser.id,
                        username: currentUser.username,
                        montoBase: monto
                    })
                });

                const datos = await res.json();

                // 2. Configurar Widget
                const checkout = new WidgetCheckout({
                    currency: datos.moneda,
                    amountInCents: datos.montoCentavos,
                    reference: datos.referencia,
                    publicKey: datos.llavePublica,
                    signature: { integrity: datos.firma }, // ¡Seguridad!
                    redirectUrl: window.location.href, // Opcional: A dónde vuelve al terminar
                });

                // 3. Abrir Widget
                checkout.open(function (result) {
                    const transaction = result.transaction;
                    console.log('Transaction ID: ', transaction.id);
                    console.log('Transaction object: ', transaction);
                    // Aquí solo cerramos el modal, la confirmación real llega por Socket desde el Webhook
                    depositModal.classList.add('hidden');
                    btnAutoDeposit.disabled = false;
                    btnAutoDeposit.textContent = "Pagar con Wompi";
                });

            } catch (error) {
                console.error(error);
                alert("Error iniciando Wompi");
                btnAutoDeposit.disabled = false;
            }
        });
    }

    // --- LÓGICA DE RETIROS ---
    if (btnOpenWithdraw) btnOpenWithdraw.addEventListener('click', () => withdrawModal.classList.remove('hidden'));
    if (closeWithdrawModal) closeWithdrawModal.addEventListener('click', () => withdrawModal.classList.add('hidden'));

    if (btnSubmitWithdraw) {
        btnSubmitWithdraw.addEventListener('click', async () => {
            const monto = document.getElementById('withdraw-amount').value;
            const cuenta = document.getElementById('withdraw-account').value;
            const nombre = document.getElementById('withdraw-name').value;

            if (!monto || !cuenta || !nombre) return alert("Por favor completa todos los datos.");
            if (parseInt(monto) > currentUser.saldo) return alert("Saldo insuficiente.");

            const datosCuenta = `${cuenta} - ${nombre}`;

            const res = await fetch(API_BASE_URL + '/api/transaction/withdraw', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    userId: currentUser.id,
                    username: currentUser.username,
                    monto: monto,
                    datosCuenta: datosCuenta
                })
            });

            const data = await res.json();
            if (data.success) {
                alert(data.message);
                userBalanceDisplay.textContent = '$' + data.newBalance;
                currentUser.saldo = data.newBalance;
                withdrawModal.classList.add('hidden');
            } else {
                alert("Error: " + data.error);
            }
        });
    }

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
    window.procesarTransaccionAdmin = async (id, act) => { if (!confirm(`¿${act}?`)) return; const res = await fetch(API_BASE_URL + '/api/admin/transaction/process', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ transId: id, action: act }) }); const d = await res.json(); alert(d.message); cargarTransaccionesAdmin(); };
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
            `;
            lista.appendChild(div);
        });
    };

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
        let content = ''; if (data.tipo === 'imagen') content = `<img src="${data.texto}" class="chat-image" onclick="window.open(this.src)">`; else if (data.tipo === 'video') content = `<video src="${data.texto}" class="chat-video" controls></video>`; else {
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

    const anuForm = chatElements.anuncios.form; if (anuForm) { anuForm.addEventListener('submit', (e) => { e.preventDefault(); const i = chatElements.anuncios.input; const fi = chatElements.anuncios.fileInput; const f = fi.files[0]; if (f && currentUser) { const r = new FileReader(); r.onload = (ev) => { const t = f.type.startsWith('video') ? 'video' : 'imagen'; socket.emit('mensaje_chat', { canal: 'anuncios', usuario: currentUser.username, texto: ev.target.result, tipo: t }); i.value = ''; fi.value = ''; }; r.readAsDataURL(f); } else if (i.value) { socket.emit('mensaje_chat', { canal: 'anuncios', usuario: currentUser.username, texto: i.value, tipo: 'texto' }); i.value = ''; } }); }
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

        // Handler para reconexión con partida activa - navegar automáticamente al chat privado
        socket.on('restaurar_partida', (data) => {
            console.log("🔄 Restaurando partida:", data);
            currentRoomId = data.salaId;
            maxBetAllowed = data.maxApuesta;

            // Limpieza
            const privateMsgs = document.getElementById('private-messages');
            if (privateMsgs) privateMsgs.innerHTML = '';

            // Mostrar datos del rival
            document.getElementById('rival-name').textContent = `VS ${data.rival.username}`;
            document.getElementById('max-bet-info').textContent = `Tope: $${maxBetAllowed.toLocaleString()}`;

            // Restaurar historial de chat privado
            if (data.historial && data.historial.length > 0) {
                data.historial.forEach(msg => {
                    agregarBurbujaPrivada(msg);
                });
            }

            // Configurar UI según estado
            if (data.iniciado) {
                // Partida ya iniciada - ir a resultado
                actualizarEstadoVisual('jugando', true);
                ejecutarCambioVista('game_result', null);
            } else {
                // En negociación - ir a chat privado
                inputGameMode.value = '';
                inputBetAmount.value = '';
                inputGameMode.disabled = false;
                inputBetAmount.disabled = false;
                btnStartGame.textContent = "🎮 COMENZAR PARTIDA";
                btnStartGame.disabled = true;
                btnStartGame.classList.remove('enabled');

                actualizarEstadoVisual('partida_encontrada', true);
                ejecutarCambioVista('private', null);
            }
        });

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
            } catch (e) { }
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
        else if (dinero > 25000) { error = "Máximo $25.000"; }
        else if (dinero > maxBetAllowed) { error = `Tope saldos: $${maxBetAllowed}`; }

        // Mostrar error si existe
        const elMsg = document.getElementById('validation-msg');
        if (elMsg) elMsg.textContent = error;

        // 3. CÁLCULO DE GANANCIA (Aquí estaba el problema)
        if (elTexto) {
            if (!isNaN(dinero) && dinero >= 1000) {
                // Hacemos la matemática explícita
                const totalMesa = dinero * 2;
                const comision = totalMesa * 0.20;
                const ganancia = totalMesa - comision;

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

    // --- ACTUALIZACIÓN DE SALDO EN VIVO ---
    socket.on('actualizar_saldo', (nuevoSaldo) => {
        if (currentUser) currentUser.saldo = nuevoSaldo;
        if (userBalanceDisplay) userBalanceDisplay.textContent = '$' + nuevoSaldo;
    });

    // --- LÓGICA DOBLE CONFIRMACIÓN ---
    if (btnStartGame) {
        btnStartGame.addEventListener('click', () => {
            if (!currentUser) return;
            // Cambiar texto visualmente
            btnStartGame.textContent = "⏳ ESPERANDO AL RIVAL...";
            btnStartGame.disabled = true;
            btnStartGame.classList.remove('enabled');
            btnStartGame.style.backgroundColor = "#faa61a"; // Amarillo

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
            btnStartGame.textContent = "⏳ ESPERANDO AL RIVAL...";
            btnStartGame.disabled = true;
            btnStartGame.style.backgroundColor = "#faa61a"; // Amarillo
            btnStartGame.classList.remove('enabled');
        }
    });

    socket.on('rival_listo_inicio', () => {
        // Si yo aún no he dado listo, me avisa
        if (btnStartGame && btnStartGame.textContent !== "⏳ ESPERANDO AL RIVAL...") {
            alert("¡Tu rival está listo! Dale a COMENZAR para iniciar.");
        }
    });

    socket.on('error_negociacion', (msg) => {
        alert("⛔ " + msg);
        // Resetear botones
        if (btnStartGame) {
            btnStartGame.textContent = "🎮 COMENZAR PARTIDA";
            btnStartGame.disabled = false;
            btnStartGame.classList.add('enabled');
            btnStartGame.style.backgroundColor = "#43b581";
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
            maxBetAllowed = data.maxApuesta;

            // 2. Llenar datos visuales
            document.getElementById('max-bet-info').textContent = `Tope: $${maxBetAllowed.toLocaleString()}`;

            // Nombre del Rival
            const rivalObj = data.rival;
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

            // 4. Restaurar Estado de la UI
            // Si la partida ya inició, bloqueamos los inputs de apuesta
            if (data.iniciado) {
                inputGameMode.disabled = true;
                inputBetAmount.disabled = true;
                btnStartGame.textContent = "🎮 PARTIDA EN CURSO...";
                btnStartGame.disabled = true;
                btnStartGame.classList.remove('enabled');
            } else {
                // Si estamos negociando, desbloqueamos
                inputGameMode.disabled = false;
                inputBetAmount.disabled = false;
                btnStartGame.textContent = "🎮 COMENZAR PARTIDA";
                // (La validación normal se encargará de habilitarlo si hay datos)
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

    // --- SISTEMA DE SORTEOS ---

    let userTickets = 0;
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
                document.getElementById('user-tickets-count').textContent = userTickets;
            }

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
            alert("Error eliminando sorteo");
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

    socket.on('sorteo_expirado', (data) => {
        console.log("⏰ Sorteo expirado:", data.nombre);
        if (typeof mostrarToast === 'function') {
            mostrarToast(`⏰ Sorteo expirado: "${data.nombre}". Tickets devueltos.`, 5000);
        }
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

    // Listener para tickets ganados en partida
    socket.on('tickets_ganados', (data) => {
        console.log(`🎟️ Ganaste ${data.cantidad} ticket(s)`);
        if (typeof mostrarToast === 'function') {
            mostrarToast(`🎟️ ¡Ganaste <strong>${data.cantidad}</strong> ticket(s) para sorteos!`, 5000);
        }
        userTickets += data.cantidad;
        const ticketDisplay = document.getElementById('user-tickets-count');
        if (ticketDisplay) ticketDisplay.textContent = userTickets;
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

            // Si estuvo inactivo más de 5 segundos, forzamos recarga total
            if (inactiveDuration > 5000) {
                console.warn(`⏰ Inactividad detectada (${Math.round(inactiveDuration / 1000)}s), recargando...`);
                window.location.reload(true);
                return;
            }

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
