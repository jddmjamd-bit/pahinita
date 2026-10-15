import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { CATEGORIAS, PREGUNTAS } from '../data/faq';
import './Faq.css';

// Minúsculas y sin tildes, para que "recargar" encuentre "Recárgar" y "sesion" encuentre "sesión".
const normalizar = (t) => String(t ?? '').toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '');

// Solo se aceptan IDs de YouTube de 11 caracteres y archivos propios de /videos/: nada de URLs arbitrarias en el iframe.
const ID_YOUTUBE = /^[\w-]{11}$/;
const ARCHIVO_VIDEO = /^\/videos\/[\w.-]+\.(mp4|webm)$/;

function VideoPregunta({ video }) {
    const titulo = video.titulo || 'Video tutorial';
    const youtube = ID_YOUTUBE.test(video.youtube || '') ? video.youtube : null;
    const archivo = !youtube && ARCHIVO_VIDEO.test(video.archivo || '') ? video.archivo : null;

    if (!youtube && !archivo) {
        return (
            <div className="faq-video faq-video-pendiente">
                <span className="faq-video-icono">🎬</span>
                <div>
                    <strong>{titulo}</strong>
                    <span>Video próximamente</span>
                </div>
            </div>
        );
    }

    return (
        <div className="faq-video">
            <div className="faq-video-titulo">🎬 {titulo}</div>
            <div className="faq-video-marco">
                {youtube ? (
                    <iframe
                        src={`https://www.youtube-nocookie.com/embed/${youtube}`}
                        title={titulo}
                        loading="lazy"
                        allow="accelerometer; encrypted-media; picture-in-picture; fullscreen"
                        allowFullScreen
                        referrerPolicy="strict-origin-when-cross-origin"
                    />
                ) : (
                    <video src={archivo} controls preload="metadata" playsInline />
                )}
            </div>
        </div>
    );
}

export default function Faq() {
    const { hash } = useLocation();
    const [busqueda, setBusqueda] = useState('');
    const [categoria, setCategoria] = useState('todas');
    const [soloVideo, setSoloVideo] = useState(false);
    const [abiertas, setAbiertas] = useState(() => new Set());
    const refs = useRef({});

    // Enlace directo (/faq#recargar-nequi): abre esa pregunta y la deja a la vista.
    useEffect(() => {
        const id = decodeURIComponent((hash || '').replace('#', ''));
        if (!id || !PREGUNTAS.some(p => p.id === id)) return;
        setBusqueda('');
        setCategoria('todas');
        setSoloVideo(false);
        setAbiertas(prev => new Set(prev).add(id));
        const t = setTimeout(() => refs.current[id]?.scrollIntoView({ behavior: 'smooth', block: 'center' }), 60);
        return () => clearTimeout(t);
    }, [hash]);

    const filtradas = useMemo(() => {
        const q = normalizar(busqueda).trim();
        const nombres = Object.fromEntries(CATEGORIAS.map(c => [c.id, c.nombre]));
        return PREGUNTAS.filter(p => {
            if (categoria !== 'todas' && p.categoria !== categoria) return false;
            if (soloVideo && !p.video) return false;
            if (!q) return true;
            const texto = normalizar([p.pregunta, ...p.respuesta, ...(p.pasos || []), nombres[p.categoria]].join(' '));
            return q.split(/\s+/).every(palabra => texto.includes(palabra));
        });
    }, [busqueda, categoria, soloVideo]);

    const conteo = useMemo(() => {
        const c = { todas: PREGUNTAS.length };
        PREGUNTAS.forEach(p => { c[p.categoria] = (c[p.categoria] || 0) + 1; });
        return c;
    }, []);

    const alternar = (id) => {
        setAbiertas(prev => {
            const nuevo = new Set(prev);
            if (nuevo.has(id)) nuevo.delete(id); else nuevo.add(id);
            return nuevo;
        });
    };

    const limpiarFiltros = () => {
        setBusqueda('');
        setCategoria('todas');
        setSoloVideo(false);
    };

    const hayFiltros = busqueda || categoria !== 'todas' || soloVideo;
    const idsFiltradas = filtradas.map(p => p.id);
    const todasAbiertas = idsFiltradas.length > 0 && idsFiltradas.every(id => abiertas.has(id));

    const alternarTodas = () => {
        setAbiertas(prev => {
            const nuevo = new Set(prev);
            idsFiltradas.forEach(id => (todasAbiertas ? nuevo.delete(id) : nuevo.add(id)));
            return nuevo;
        });
    };

    return (
        <div className="faq-container">
            <header className="faq-header">
                <h2>❓ Preguntas frecuentes y ayuda</h2>
                <p>Aquí encuentras cómo hacer cada cosa en la plataforma. Las preguntas con 🎬 incluyen un video para que lo veas paso a paso.</p>
            </header>

            <div className="faq-buscador">
                <input
                    type="search"
                    placeholder="🔍 Busca tu duda (ej: retirar, sorteo, disputa...)"
                    value={busqueda}
                    onChange={e => setBusqueda(e.target.value)}
                    aria-label="Buscar en las preguntas frecuentes"
                />
            </div>

            <div className="faq-filtros">
                <button
                    type="button"
                    className={`faq-chip ${categoria === 'todas' ? 'activo' : ''}`}
                    onClick={() => setCategoria('todas')}
                >
                    Todas <span className="faq-chip-n">{conteo.todas}</span>
                </button>
                {CATEGORIAS.map(c => (
                    <button
                        key={c.id}
                        type="button"
                        className={`faq-chip ${categoria === c.id ? 'activo' : ''}`}
                        onClick={() => setCategoria(c.id)}
                    >
                        {c.icono} {c.nombre} <span className="faq-chip-n">{conteo[c.id] || 0}</span>
                    </button>
                ))}
                <button
                    type="button"
                    className={`faq-chip faq-chip-video ${soloVideo ? 'activo' : ''}`}
                    onClick={() => setSoloVideo(v => !v)}
                    aria-pressed={soloVideo}
                >
                    🎬 Solo con video
                </button>
            </div>

            <div className="faq-barra">
                <span>{filtradas.length === 1 ? '1 pregunta' : `${filtradas.length} preguntas`}</span>
                {filtradas.length > 0 && (
                    <button type="button" className="faq-link-btn" onClick={alternarTodas}>
                        {todasAbiertas ? 'Contraer todas' : 'Expandir todas'}
                    </button>
                )}
            </div>

            {filtradas.length === 0 ? (
                <div className="faq-vacio">
                    <div className="faq-vacio-emoji">🔎</div>
                    <h3>No encontramos nada con eso</h3>
                    <p>Prueba con otras palabras, o pregúntale directamente al Asistente.</p>
                    <div className="faq-vacio-acciones">
                        {hayFiltros && <button type="button" className="faq-btn-sec" onClick={limpiarFiltros}>Quitar filtros</button>}
                        <Link to="/chatbot" className="faq-btn-link">🤖 Preguntar al Asistente</Link>
                    </div>
                </div>
            ) : (
                <div className="faq-lista">
                    {filtradas.map(p => {
                        const abierta = abiertas.has(p.id);
                        const cat = CATEGORIAS.find(c => c.id === p.categoria);
                        return (
                            <article
                                key={p.id}
                                id={p.id}
                                ref={el => { refs.current[p.id] = el; }}
                                className={`faq-item ${abierta ? 'abierta' : ''}`}
                            >
                                <button
                                    type="button"
                                    className="faq-pregunta"
                                    onClick={() => alternar(p.id)}
                                    aria-expanded={abierta}
                                    aria-controls={`faq-resp-${p.id}`}
                                >
                                    <span className="faq-pregunta-texto">
                                        <span className="faq-cat-icono" title={cat?.nombre}>{cat?.icono}</span>
                                        {p.pregunta}
                                    </span>
                                    <span className="faq-pregunta-extra">
                                        {p.video && <span className="faq-tag-video" title="Incluye video">🎬</span>}
                                        <span className="faq-flecha">{abierta ? '▲' : '▼'}</span>
                                    </span>
                                </button>

                                {abierta && (
                                    <div className="faq-respuesta" id={`faq-resp-${p.id}`}>
                                        {p.respuesta.map((parrafo, i) => <p key={i}>{parrafo}</p>)}
                                        {p.pasos && (
                                            <ol className="faq-pasos">
                                                {p.pasos.map((paso, i) => <li key={i}>{paso}</li>)}
                                            </ol>
                                        )}
                                        {p.video && <VideoPregunta video={p.video} />}
                                        {p.enlace && (
                                            <Link to={p.enlace.to} className="faq-btn-link">{p.enlace.texto} →</Link>
                                        )}
                                    </div>
                                )}
                            </article>
                        );
                    })}
                </div>
            )}

            <footer className="faq-pie">
                ¿No encontraste lo que buscabas? <Link to="/chatbot">Pregúntale al Asistente</Link> · <Link to="/terminos">Términos de Servicio</Link> · <Link to="/privacidad">Política de Privacidad</Link>
            </footer>
        </div>
    );
}
