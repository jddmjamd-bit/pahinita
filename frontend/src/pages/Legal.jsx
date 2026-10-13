import { Link } from 'react-router-dom';
import DocumentoLegal from '../components/DocumentoLegal';
import './Legal.css';

/**
 * Página pública (sin sesión) de un documento legal. Rutas: /terminos y /privacidad (roadmap 1.6).
 * @param {{ tipo: 'terminos' | 'privacidad' }} props
 */
export default function Legal({ tipo }) {
    return (
        <div className="legal-page">
            <nav className="legal-page-nav">
                <Link to="/">← Volver</Link>
                <div className="legal-page-links">
                    <Link to="/terminos" className={tipo === 'terminos' ? 'activo' : ''}>Términos de Servicio</Link>
                    <Link to="/privacidad" className={tipo === 'privacidad' ? 'activo' : ''}>Política de Privacidad</Link>
                </div>
            </nav>
            <main className="legal-page-card">
                <DocumentoLegal tipo={tipo} />
            </main>
        </div>
    );
}
