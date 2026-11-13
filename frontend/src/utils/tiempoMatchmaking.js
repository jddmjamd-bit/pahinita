/** Utilidades compartidas de formato de tiempos y metadatos de notificaciones de matchmaking. */

export function parsearDatosAviso(datos) {
    if (!datos) return {};
    if (typeof datos === 'object') return datos;
    if (typeof datos === 'string') {
        try {
            const parsed = JSON.parse(datos);
            return parsed && typeof parsed === 'object' ? parsed : {};
        } catch {
            return {};
        }
    }
    return {};
}

export function formatearTiempoReloj(segundosTotal) {
    const segs = Math.max(0, Math.floor(Number(segundosTotal) || 0));
    const min = Math.floor(segs / 60);
    const resto = segs % 60;
    return `${min}:${String(resto).padStart(2, '0')}`;
}

export function formatearTiempoTexto(segundosTotal) {
    const segs = Math.max(0, Math.floor(Number(segundosTotal) || 0));
    const min = Math.floor(segs / 60);
    const resto = segs % 60;
    if (min > 0 && resto > 0) return `${min} min ${resto} s`;
    if (min > 0) return `${min} min`;
    return `${resto} s`;
}

export function calcularRestanteSegundos(duracionSeg, recibidoEn, ahoraMs, expiraEnEpochMs) {
    const baseDuracion = Number(duracionSeg);
    if (Number.isFinite(baseDuracion) && baseDuracion >= 0 && recibidoEn) {
        const transcurrido = Math.max(0, Math.floor((ahoraMs - recibidoEn) / 1000));
        return Math.max(0, Math.floor(baseDuracion) - transcurrido);
    }
    const epoch = Number(expiraEnEpochMs);
    if (Number.isFinite(epoch) && epoch > 0) {
        return Math.max(0, Math.ceil((epoch - ahoraMs) / 1000));
    }
    return Number.isFinite(baseDuracion) ? Math.max(0, Math.floor(baseDuracion)) : 0;
}
