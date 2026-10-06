let socket;
let sessionUserId = null; // ID del usuario de la sesión actual para detectar cambios

// --- CONFIGURACIÓN PARA APP MÓVIL CAPACITOR ---
const isNativeApp = typeof window.Capacitor !== 'undefined';
const API_BASE_URL = isNativeApp ? 'https://torneos-beta.onrender.com' : '';
console.log(`📱 Modo: ${isNativeApp ? 'APP NATIVA' : 'WEB'}, API: ${API_BASE_URL || 'local'}`);

window.abrirMediaModal = function(src, tipo) {
    let lb = document.getElementById('media-lightbox');
    if (!lb) {
        lb = document.createElement('div');
        lb.id = 'media-lightbox';
        lb.className = 'media-lightbox';
        lb.innerHTML = '<button class="lightbox-close" onclick="document.getElementById(\'media-lightbox\').style.display=\'none\'; document.body.style.overflow=\'\';">&times;</button><div class="lightbox-content" id="lightbox-content"></div>';
        lb.onclick = function(e) {
            if (e.target === lb) { lb.style.display = 'none'; document.body.style.overflow = ''; document.getElementById('lightbox-content').innerHTML = ''; }
        };
        document.body.appendChild(lb);
        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape' && lb.style.display === 'flex') { lb.style.display = 'none'; document.body.style.overflow = ''; document.getElementById('lightbox-content').innerHTML = ''; }
        });
    }
    const cont = document.getElementById('lightbox-content');
    if (tipo === 'video') {
        cont.innerHTML = `<video src="${src}" class="lightbox-video" controls autoplay></video>`;
    } else {
        cont.innerHTML = `<img src="${src}" class="lightbox-img">`;
    }
    lb.style.display = 'flex';
    document.body.style.overflow = 'hidden';
};

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
        console.error(e);
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

        // --- FIREBASE WEB PUSH NOTIFICATIONS ---
        if (typeof firebase !== 'undefined' && firebase.messaging) {
            try {
                // Inicializar Firebase
                const firebaseApp = firebase.initializeApp({
                    apiKey: "AIzaSyBBodVPEhPdol4VZSpoSniJsKqDqMI72JA",
                    authDomain: "partidas-torneos.firebaseapp.com",
                    projectId: "partidas-torneos",
                    storageBucket: "partidas-torneos.firebasestorage.app",
                    messagingSenderId: "406556638556",
                    appId: "1:406556638556:web:4b3f9249c649b5e5681599"
                });

                const messaging = firebase.messaging();

                // Registrar Service Worker
                navigator.serviceWorker.register('/firebase-messaging-sw.js')
                    .then((registration) => {
                        console.log("🔔 Service Worker registrado");

                        // Solicitar permiso de notificaciones
                        Notification.requestPermission().then((permission) => {
                            console.log("🔔 Permiso de notificaciones:", permission);
                            if (permission === 'granted') {
                                // Obtener FCM token
                                messaging.getToken({
                                    vapidKey: 'BFXm9jDYCe9fBFivXLCWNf9EqP1zCUno4VXewyBXiULOaqqCZd-B5l1agc_8fiGjovXI39BFsFsGQ-Mpdl8Ou60',
                                    serviceWorkerRegistration: registration
                                }).then((token) => {
                                    if (token) {
                                        console.log("🔔 FCM Token web obtenido:", token.substring(0, 20) + "...");
                                        window.fcmWebToken = token;
                                        // Si ya tenemos usuario logueado, registrar token
                                        if (sessionUserId) {
                                            fetch(API_BASE_URL + '/api/register-token', {
                                                method: 'POST',
                                                headers: { 'Content-Type': 'application/json' },
                                                body: JSON.stringify({ userId: sessionUserId, token: token })
                                            }).then(() => console.log("🔔 Token web registrado en servidor"))
                                              .catch(e => console.error("Error registrando token web:", e));
                                        }
                                    }
                                }).catch(e => console.error("🔔 Error obteniendo FCM token:", e));
                            }
                        });
                    })
                    .catch(e => console.error("🔔 Error registrando SW:", e));

                // Manejar mensajes en foreground (NO mostrar notificación del sistema, el toast ya lo hace)
                messaging.onMessage((payload) => {
                    console.log("🔔 Push recibido en foreground (ignorado, toast maneja):", payload.notification?.title);
                });

                // Limpiar notificaciones al volver a la pestaña
                document.addEventListener('visibilitychange', () => {
                    if (document.visibilityState === 'visible') {
                        // Limpiar notificaciones del service worker
                        if (navigator.serviceWorker.controller) {
                            navigator.serviceWorker.controller.postMessage({ type: 'CLEAR_NOTIFICATIONS' });
                        }
                        // También intentar limpiar directamente
                        navigator.serviceWorker.ready.then(reg => {
                            reg.getNotifications().then(notifications => {
                                notifications.forEach(n => n.close());
                                if (notifications.length > 0) console.log("🔔 Notificaciones limpiadas:", notifications.length);
                            });
                        });
                    }
                });

                // Limpiar al cargar la página
                navigator.serviceWorker.ready.then(reg => {
                    reg.getNotifications().then(notifications => {
                        notifications.forEach(n => n.close());
                    });
                });

            } catch (e) {
                console.error("🔔 Error inicializando Firebase Messaging:", e);
            }
        } else {
            console.log("⚠️ Firebase SDK no disponible");
        }
    }

    // --- AUTO-LOGIN CON COOKIES ---
    verificarSesion(true).then(user => {
        if (user) enterLobby(user);
    });

    // --- TOAST NOTIFICATION IN-APP ---
    window.mostrarToast = function(mensaje, duracion = 6000) {
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
    };

    // Socket.IO - Conexión remota para app móvil, local para web
    try {
        socket = isNativeApp
            ? io(API_BASE_URL, { transports: ['websocket'], withCredentials: true })
            : io({ transports: ['websocket'], withCredentials: true });

        // Auto-registrar al reconectar
        socket.on('connect', () => {
            console.log("🔌 Socket conectado/reconectado");
            if (currentUser) {
                console.log("🔄 Re-registrando usuario en el socket");
                socket.emit('registrar_socket', currentUser);
            }
        });

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
                actualizarEstadoVisual('buscando_partida', true);
            }
        });

    } catch (e) { console.error(e); }

    // --- FIX MAESTRO: AUTO-RECARGA POR SUSPENSIÓN (REMOVIDO) ---

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
