import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import DocumentoLegal from './DocumentoLegal';
import './AceptacionLegal.css';

/**
 * Casilla obligatoria «Acepto los Términos y la Política» del registro (roadmap 1.6).
 * Los enlaces abren el documento en un visor sobre la misma pantalla, así no se pierde lo que ya escribió
 * el usuario en el formulario (y funciona igual dentro de la app Capacitor). El visor va en un portal
 * porque el contenedor del formulario tiene backdrop-filter y rompería el position: fixed.
 *
 * @param {{ acepta: boolean, onChange: (valor: boolean) => void }} props
 */
export default function AceptacionLegal({ acepta, onChange }) {
    const [docAbierto, setDocAbierto] = useState(null); // null | 'terminos' | 'privacidad'

    useEffect(() => {
        if (!docAbierto) return;
        const alPulsar = (e) => { if (e.key === 'Escape') setDocAbierto(null); };
        window.addEventListener('keydown', alPulsar);
        return () => window.removeEventListener('keydown', alPulsar);
    }, [docAbierto]);

    const abrir = (e, tipo) => {
        e.preventDefault();
        setDocAbierto(tipo);
    };

    return (
        <>
            <label className="legal-check">
                <input
                    type="checkbox"
                    checked={acepta}
                    onChange={e => onChange(e.target.checked)}
                    required
                />
                <span>
                    He leído y acepto los{' '}
                    <a href="/terminos" onClick={e => abrir(e, 'terminos')}>Términos de Servicio</a>
                    {' '}y la{' '}
                    <a href="/privacidad" onClick={e => abrir(e, 'privacidad')}>Política de Privacidad</a>.
                </span>
            </label>

            {docAbierto && createPortal(
                <div className="legal-modal-overlay" onClick={() => setDocAbierto(null)}>
                    <div
                        className="legal-modal"
                        role="dialog"
                        aria-modal="true"
                        onClick={e => e.stopPropagation()}
                    >
                        <div className="legal-modal-header">
                            <button
                                type="button"
                                className="legal-modal-close"
                                aria-label="Cerrar"
                                onClick={() => setDocAbierto(null)}
                            >
                                &times;
                            </button>
                        </div>
                        <div className="legal-modal-body">
                            <DocumentoLegal tipo={docAbierto} />
                        </div>
                    </div>
                </div>,
                document.body
            )}
        </>
    );
}
