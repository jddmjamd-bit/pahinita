/* La configuración pública llega en la URL al registrar el worker; no contiene secretos. */
const mensajeConfig = new URL(self.location.href).searchParams.get('config');
let firebaseConfig = null;
try {
    firebaseConfig = mensajeConfig ? JSON.parse(mensajeConfig) : null;
} catch {
    firebaseConfig = null;
}

self.addEventListener('message', event => {
    if (event.data?.tipo !== 'limpiar-notificaciones') return;
    event.waitUntil(self.registration.getNotifications().then(notificaciones => {
        notificaciones.forEach(notificacion => notificacion.close());
    }));
});

// Handle clicks for notifications displayed by the foreground page. This listener
// must be registered before Firebase's service worker code is loaded.
self.addEventListener('notificationclick', event => {
    event.notification.close();
    event.stopImmediatePropagation();
    event.waitUntil((async () => {
        const mensajeFcm = event.notification.data?.FCM_MSG;
        const rawLink = event.notification.data?.link
            || mensajeFcm?.data?.link
            || mensajeFcm?.fcmOptions?.link
            || '/';
        let destination;
        try {
            destination = new URL(rawLink, self.location.origin);
        } catch {
            destination = new URL('/', self.location.origin);
        }
        if (destination.origin !== self.location.origin) destination = new URL('/', self.location.origin);

        const clients = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
        for (const client of clients) {
            if (new URL(client.url).origin !== self.location.origin) continue;
            await client.navigate(destination.href);
            return client.focus();
        }
        return self.clients.openWindow(destination.href);
    })());
});

importScripts('https://www.gstatic.com/firebasejs/13.1.0/firebase-app-compat.js');
importScripts('https://www.gstatic.com/firebasejs/13.1.0/firebase-messaging-compat.js');

if (firebaseConfig?.projectId && firebaseConfig?.messagingSenderId && firebaseConfig?.appId) {
    firebase.initializeApp(firebaseConfig);
    firebase.messaging().onBackgroundMessage(async payload => {
        const datos = payload?.data || {};
        if (datos.accion !== 'cerrar' || !datos.tag) return;
        const notificaciones = await self.registration.getNotifications({ tag: datos.tag });
        notificaciones.forEach(notificacion => notificacion.close());
    });
}
