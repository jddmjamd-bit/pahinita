// Piezas compartidas por la página de soporte del usuario (Soporte.jsx) y la bandeja del admin (AdminSoporte.jsx).

// Debe coincidir con las categorías que acepta SoporteService en el servidor
export const CATEGORIAS_SOPORTE = [
    { id: 'pregunta', etiqueta: '❓ Pregunta' },
    { id: 'sugerencia', etiqueta: '💡 Sugerencia' },
    { id: 'bug', etiqueta: '🐛 Bug / error de la página' },
    { id: 'cuenta', etiqueta: '👤 Problema con mi cuenta' },
    { id: 'otro', etiqueta: '📝 Otro' }
];

export const etiquetaCategoria = (id) => CATEGORIAS_SOPORTE.find(c => c.id === id)?.etiqueta || id;

export const formatearFecha = (iso) => {
    if (!iso) return '';
    const fecha = new Date(iso);
    return Number.isNaN(fecha.getTime()) ? '' : fecha.toLocaleString('es-CO');
};
