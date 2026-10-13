// Cliente mínimo para la API del backend. Siempre envía la cookie de sesión (JWT httpOnly) y nunca lanza:
// devuelve { ok, status, data } y, si no hay conexión, data.error trae un mensaje listo para mostrar.
export const API_BASE_URL = 'https://torneos-beta.onrender.com'; // TODO: use env variables (igual que el resto de páginas)

export async function llamarApi(ruta, opciones = {}) {
    try {
        const res = await fetch(`${API_BASE_URL}${ruta}`, {
            credentials: 'include',
            ...opciones,
            headers: opciones.body ? { 'Content-Type': 'application/json' } : undefined
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
