import { useEffect, useState } from 'react';
import './DocumentoLegal.css';

// El texto vive en frontend/public/legal/contenido-legal.json (única fuente de los Términos y la Política).
// Ruta relativa a propósito: en la web se sirve del mismo origen y en la app Capacitor va dentro del paquete.
const URL_CONTENIDO = '/legal/contenido-legal.json';

/** Un elemento de `parrafos` es un texto o un objeto { lista: [...] }. React escapa todo: nada de innerHTML. */
function Parrafo({ item }) {
    if (typeof item === 'string') return <p>{item}</p>;
    if (item && Array.isArray(item.lista)) {
        return (
            <ul>
                {item.lista.map((texto, i) => <li key={i}>{texto}</li>)}
            </ul>
        );
    }
    return null;
}

/**
 * Muestra un documento legal.
 * @param {{ tipo: 'terminos' | 'privacidad' }} props
 */
export default function DocumentoLegal({ tipo }) {
    const [documento, setDocumento] = useState(null);
    const [error, setError] = useState(false);

    useEffect(() => {
        let cancelado = false;
        setDocumento(null);
        setError(false);

        fetch(URL_CONTENIDO, { cache: 'no-cache' })
            .then(res => {
                if (!res.ok) throw new Error('No se pudo cargar el documento');
                return res.json();
            })
            .then(data => {
                if (cancelado) return;
                if (data && data[tipo]) setDocumento(data[tipo]);
                else setError(true);
            })
            .catch(() => { if (!cancelado) setError(true); });

        return () => { cancelado = true; };
    }, [tipo]);

    if (error) {
        return <p className="documento-legal doc-error">No pudimos cargar el documento. Revisa tu conexión e inténtalo de nuevo.</p>;
    }
    if (!documento) {
        return <p className="documento-legal doc-estado">Cargando…</p>;
    }

    return (
        <article className="documento-legal">
            <h1>{documento.titulo}</h1>
            <p className="doc-meta">Versión {documento.version} · Vigente desde el {documento.vigenteDesde}</p>
            {documento.intro && <p className="doc-intro">{documento.intro}</p>}
            {documento.secciones.map((seccion, i) => (
                <section key={i}>
                    <h2>{i + 1}. {seccion.titulo}</h2>
                    {seccion.parrafos.map((item, j) => <Parrafo key={j} item={item} />)}
                </section>
            ))}
        </article>
    );
}
