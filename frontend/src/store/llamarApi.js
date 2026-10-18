// Cliente mínimo para la API del backend. Siempre envía la cookie de sesión (JWT httpOnly) y nunca lanza:
// devuelve { ok, status, data } y, si no hay conexión, data.error trae un mensaje listo para mostrar.

const resolverUrlBase = () => {
    if (import.meta.env?.VITE_API_BASE_URL) {
        return import.meta.env.VITE_API_BASE_URL.replace(/\/+$/, '');
    }
    if (typeof window !== 'undefined') {
        const esCapacitor = Boolean(
            window.Capacitor?.isNativePlatform?.() ||
            window.location?.protocol === 'capacitor:'
        );
        const puerto = window.location?.port;
        if (!esCapacitor && (puerto === '5173' || puerto === '5000')) {
            return 'http://localhost:5000';
        }
    }
    return 'https://torneos-beta.onrender.com';
};

export const API_BASE_URL = resolverUrlBase();

export async function llamarApi(ruta, opciones = {}) {
    try {
        const res = await fetch(`${API_BASE_URL}${ruta}`, {
            credentials: 'include',
            ...opciones,
            headers: opciones.body
                ? { 'Content-Type': 'application/json', ...(opciones.headers || {}) }
                : opciones.headers
        });
        let data = null;
        try {
            data = await res.json();
        } catch {
            // respuesta sin cuerpo JSON
        }
        return { ok: res.ok, status: res.status, data };
    } catch {
        return { ok: false, status: 0, data: { error: 'Error de conexión. Revisa tu internet e inténtalo de nuevo.' } };
    }
}
