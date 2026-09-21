// Firebase Messaging Service Worker
// Este archivo DEBE estar en la raíz del sitio (/firebase-messaging-sw.js)
// para que Firebase Cloud Messaging pueda recibir push en segundo plano.

importScripts('https://www.gstatic.com/firebasejs/10.12.0/firebase-app-compat.js');
importScripts('https://www.gstatic.com/firebasejs/10.12.0/firebase-messaging-compat.js');

// Configuración de Firebase (debe coincidir con la del frontend)
firebase.initializeApp({
    apiKey: "AIzaSyBBodVPEhPdol4VZSpoSniJsKqDqMI72JA",
    authDomain: "partidas-torneos.firebaseapp.com",
    projectId: "partidas-torneos",
    storageBucket: "partidas-torneos.firebasestorage.app",
    messagingSenderId: "406556638556",
    appId: "1:406556638556:web:4b3f9249c649b5e5681599"
});

const messaging = firebase.messaging();

// Manejar notificaciones en segundo plano (navegador cerrado o pestaña no activa)
messaging.onBackgroundMessage((payload) => {
    console.log('🔔 [SW] Push recibido en background:', payload);

    // Si viene con notification payload, el navegador lo muestra automáticamente
    // Este handler es para data-only messages (por si se usan en el futuro)
    if (payload.data && !payload.notification) {
        const title = payload.data.title || 'Torneos Flash';
        const body = payload.data.body || '';
        
        return self.registration.showNotification(title, {
            body: body,
            icon: '/icon-192.png',
            badge: '/icon-192.png',
            data: payload.data,
            tag: 'torneos-flash-' + Date.now(),
            requireInteraction: false
        });
    }
});

// Cuando el usuario hace click en la notificación
self.addEventListener('notificationclick', (event) => {
    console.log('🔔 [SW] Notificación clickeada');
    event.notification.close();

    // Buscar si ya hay una pestaña abierta con TorneosFlash
    event.waitUntil(
        clients.matchAll({ type: 'window', includeUncontrolled: true }).then((clientList) => {
            // Si ya hay una pestaña abierta, enfocarla
            for (const client of clientList) {
                if (client.url.includes(self.registration.scope) && 'focus' in client) {
                    return client.focus();
                }
            }
            // Si no hay pestaña abierta, abrir una nueva
            return clients.openWindow(self.registration.scope);
        })
    );
});

// Limpiar todas las notificaciones cuando se abre/enfoca la página
self.addEventListener('message', (event) => {
    if (event.data && event.data.type === 'CLEAR_NOTIFICATIONS') {
        self.registration.getNotifications().then((notifications) => {
            notifications.forEach((notification) => notification.close());
            console.log('🔔 [SW] Notificaciones limpiadas:', notifications.length);
        });
    }
});
