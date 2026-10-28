import React, { useState, useEffect } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { useAppStore } from '../store/useAppStore';
import { API_BASE_URL } from '../store/llamarApi';
import { esperarSolicitudesPush } from '../services/pushTokens';
import AceptacionLegal from '../components/AceptacionLegal';
import './Auth.css';

const excedeLimiteBcrypt = value => new TextEncoder().encode(value).length > 72;

export default function Auth() {
    const navigate = useNavigate();
    const location = useLocation();
    const [isLogin, setIsLogin] = useState(() => location.state?.modo === 'login');
    const [recuperando, setRecuperando] = useState(false);
    const [ayudaAbierta, setAyudaAbierta] = useState(null); // null | 'tag' | 'token'
    const setUser = useAppStore(state => state.setUser);
    // El token va en el fragmento (#), que el navegador no envía en peticiones ni en el Referer.
    // Leer el hash de React Router también actualiza el formulario si se abre otro enlace sin desmontar Auth.
    const tokenRecuperacion = new URLSearchParams(location.hash.slice(1)).get('reset') || '';
    const [estadoTokenRecuperacion, setEstadoTokenRecuperacion] = useState(tokenRecuperacion ? 'validando' : 'inactivo');
    const [tokenVerificado, setTokenVerificado] = useState('');
    const estadoTokenActual = tokenVerificado === tokenRecuperacion
        ? estadoTokenRecuperacion
        : tokenRecuperacion ? 'validando' : 'inactivo';

    useEffect(() => {
        if (location.state?.modo === 'login') setIsLogin(true);
        else if (location.state?.modo === 'registro') setIsLogin(false);
    }, [location.state]);

    useEffect(() => {
        if (!ayudaAbierta) return undefined;
        const cerrarConEscape = (e) => {
            if (e.key === 'Escape') setAyudaAbierta(null);
        };
        window.addEventListener('keydown', cerrarConEscape);
        return () => window.removeEventListener('keydown', cerrarConEscape);
    }, [ayudaAbierta]);

    useEffect(() => {
        if (!tokenRecuperacion) {
            setEstadoTokenRecuperacion('inactivo');
            setTokenVerificado('');
            return undefined;
        }
        let vigente = true;
        setEstadoTokenRecuperacion('validando');
        fetch(`${API_BASE_URL}/api/password/reset/validate`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ token: tokenRecuperacion })
        })
            .then(async res => {
                const data = await res.json();
                if (!res.ok) throw new Error(data.error || 'No se pudo validar el enlace.');
                if (vigente) {
                    setTokenVerificado(tokenRecuperacion);
                    setEstadoTokenRecuperacion(data.valid ? 'valido' : 'invalido');
                }
            })
            .catch(() => {
                if (vigente) {
                    setTokenVerificado(tokenRecuperacion);
                    setEstadoTokenRecuperacion('error');
                }
            });
        return () => { vigente = false; };
    }, [tokenRecuperacion]);

    // Formularios
    const [username, setUsername] = useState('');
    const [email, setEmail] = useState('');
    const [password, setPassword] = useState('');
    const [playerTag, setPlayerTag] = useState('');
    const [playerApiToken, setPlayerApiToken] = useState('');
    const [telefono, setTelefono] = useState('');
    const [acepta, setAcepta] = useState(false);
    const [passwordNueva, setPasswordNueva] = useState('');
    const [confirmarPassword, setConfirmarPassword] = useState('');

    // Estados de validación
    const [usernameStatus, setUsernameStatus] = useState({ text: '', className: 'tag-status' });
    const [emailStatus, setEmailStatus] = useState({ text: '', className: 'tag-status' });
    const [playerTagStatus, setPlayerTagStatus] = useState({ text: '', className: 'tag-status' });

    const [usernameValid, setUsernameValid] = useState(false);
    const [emailValid, setEmailValid] = useState(false);
    const [playerTagValid, setPlayerTagValid] = useState(false);

    const alternarAyuda = (clave) => {
        setAyudaAbierta(actual => (actual === clave ? null : clave));
    };

    // Validación en vivo solo cuando el usuario ya escribió algo
    useEffect(() => {
        if (isLogin) return undefined;
        const limpio = username.trim();
        if (!limpio) {
            setUsernameStatus({ text: '', className: 'tag-status' });
            setUsernameValid(false);
            return undefined;
        }
        const delayDebounceFn = setTimeout(async () => {
            if (limpio.length < 3) {
                setUsernameStatus({ text: '⚠️ Mínimo 3 caracteres', className: 'tag-status visible error' });
                setUsernameValid(false);
                return;
            }
            setUsernameStatus({ text: '🔍 Verificando...', className: 'tag-status visible searching' });
            try {
                const res = await fetch(`${API_BASE_URL}/api/check-username/${encodeURIComponent(limpio)}`);
                if (res.status === 429) {
                    setUsernameStatus({ text: '⏳ Espera unos segundos e intenta de nuevo', className: 'tag-status visible error' });
                    setUsernameValid(false);
                    return;
                }
                const data = await res.json();
                if (data.valid) {
                    setUsernameStatus({ text: data.message, className: 'tag-status visible found' });
                    setUsernameValid(true);
                } else {
                    setUsernameStatus({ text: data.message, className: 'tag-status visible not-found' });
                    setUsernameValid(false);
                }
            } catch {
                setUsernameStatus({ text: '⚠️ Error de conexión', className: 'tag-status visible error' });
                setUsernameValid(false);
            }
        }, 500);

        return () => clearTimeout(delayDebounceFn);
    }, [username, isLogin]);

    useEffect(() => {
        if (isLogin) return undefined;
        const limpio = email.trim();
        if (!limpio) {
            setEmailStatus({ text: '', className: 'tag-status' });
            setEmailValid(false);
            return undefined;
        }
        const delayDebounceFn = setTimeout(async () => {
            if (!limpio.match(/^[^\s@]+@[^\s@]+\.[^\s@]+$/)) {
                setEmailStatus({ text: '⚠️ Ingresa un correo válido', className: 'tag-status visible error' });
                setEmailValid(false);
                return;
            }
            setEmailStatus({ text: '🔍 Verificando...', className: 'tag-status visible searching' });
            try {
                const res = await fetch(`${API_BASE_URL}/api/check-email/${encodeURIComponent(limpio)}`);
                if (res.status === 429) {
                    setEmailStatus({ text: '⏳ Espera unos segundos e intenta de nuevo', className: 'tag-status visible error' });
                    setEmailValid(false);
                    return;
                }
                const data = await res.json();
                if (data.valid) {
                    setEmailStatus({ text: data.message, className: 'tag-status visible found' });
                    setEmailValid(true);
                } else {
                    setEmailStatus({ text: data.message, className: 'tag-status visible not-found' });
                    setEmailValid(false);
                }
            } catch {
                setEmailStatus({ text: '⚠️ Error de conexión', className: 'tag-status visible error' });
                setEmailValid(false);
            }
        }, 500);

        return () => clearTimeout(delayDebounceFn);
    }, [email, isLogin]);

    useEffect(() => {
        if (isLogin) return undefined;
        const tag = playerTag.trim().toUpperCase();
        if (!tag) {
            setPlayerTagStatus({ text: '', className: 'tag-status' });
            setPlayerTagValid(false);
            return undefined;
        }
        const delayDebounceFn = setTimeout(async () => {
            if (!tag.startsWith('#')) {
                setPlayerTagStatus({ text: '⚠️ Escríbelo con # al inicio (ej: #2PP)', className: 'tag-status visible error' });
                setPlayerTagValid(false);
                return;
            }
            if (!tag.match(/^#[0289PYLQGRJCUV]{3,15}$/i)) {
                setPlayerTagStatus({ text: '⚠️ Formato de tag no válido', className: 'tag-status visible error' });
                setPlayerTagValid(false);
                return;
            }
            setPlayerTagStatus({ text: '🔍 Buscando en Clash Royale...', className: 'tag-status visible searching' });
            try {
                const res = await fetch(`${API_BASE_URL}/api/verify-tag/${encodeURIComponent(tag)}`);
                if (res.status === 429) {
                    setPlayerTagStatus({ text: '⏳ Espera unos segundos e intenta de nuevo', className: 'tag-status visible error' });
                    setPlayerTagValid(false);
                    return;
                }
                const data = await res.json();
                if (data.found) {
                    setPlayerTagStatus({ text: `✅ ${data.name} (${data.trophies} 🏆)`, className: 'tag-status visible found' });
                    setPlayerTagValid(true);
                } else {
                    setPlayerTagStatus({ text: data.message || '❌ Tag no encontrado', className: 'tag-status visible not-found' });
                    setPlayerTagValid(false);
                }
            } catch {
                setPlayerTagStatus({ text: '⚠️ Error de conexión', className: 'tag-status visible error' });
                setPlayerTagValid(false);
            }
        }, 500);

        return () => clearTimeout(delayDebounceFn);
    }, [playerTag, isLogin]);

    const handleRegister = async (e) => {
        e.preventDefault();
        if (!usernameValid) return alert('❌ Revisa el nombre de usuario.');
        if (!emailValid) return alert('❌ Ingresa un correo electrónico válido.');
        if (!playerTagValid) return alert('❌ Ingresa un Player Tag válido de Clash Royale (#XXXXXXX).');
        if (!playerApiToken.trim()) return alert('❌ Ingresa el token de API de Clash Royale (pulsa ? para ver dónde está).');
        if (!acepta) return alert('❌ Debes aceptar los Términos de Servicio y la Política de Privacidad.');
        if (excedeLimiteBcrypt(password)) return alert('La contraseña es demasiado larga. Usa una más corta.');

        try {
            await esperarSolicitudesPush();
            const res = await fetch(API_BASE_URL + '/api/register', {
                method: 'POST',
                credentials: 'include',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    username,
                    email,
                    password,
                    playerTag: playerTag.trim().toUpperCase(),
                    playerApiToken,
                    telefono,
                    aceptaTerminos: true
                })
            });
            const data = await res.json();
            setPlayerApiToken('');
            if (res.ok) {
                alert(data.message || 'Solicitud recibida. Ya puedes iniciar sesión; los retiros se habilitan tras verificar tu cuenta.');
                setAyudaAbierta(null);
                setIsLogin(true);
            } else {
                alert('Error: ' + (data.error || 'Revisa los datos'));
            }
        } catch (err) {
            console.error(err);
            setPlayerApiToken('');
            alert('Error de conexión');
        }
    };

    const handleLogin = async (e) => {
        e.preventDefault();
        if (excedeLimiteBcrypt(password)) return alert('La contraseña es demasiado larga. Si olvidaste tu clave, recupérala por correo.');
        try {
            await esperarSolicitudesPush();
            const res = await fetch(API_BASE_URL + '/api/login', {
                method: 'POST',
                credentials: 'include',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ email, password })
            });
            const data = await res.json();
            if (res.ok) {
                setUser(data.user);
                navigate('/chat');
            } else {
                alert(data.error);
            }
        } catch (err) {
            console.error(err);
            alert('Error de conexión');
        }
    };

    const handleSolicitarRecuperacion = async (e) => {
        e.preventDefault();
        try {
            const res = await fetch(`${API_BASE_URL}/api/password/forgot`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ email })
            });
            const data = await res.json();
            if (res.ok) {
                alert(data.message || 'Si existe una cuenta con ese correo, enviaremos un enlace para recuperar la contraseña.');
                setRecuperando(false);
                setIsLogin(true);
            } else {
                alert(data.error || 'No se pudo procesar la solicitud');
            }
        } catch (err) {
            console.error(err);
            alert('Error de conexión');
        }
    };

    const handleRestablecerPassword = async (e) => {
        e.preventDefault();
        if (passwordNueva.length < 6 || passwordNueva.length > 128) {
            return alert('La contraseña debe tener al menos 6 caracteres.');
        }
        if (excedeLimiteBcrypt(passwordNueva)) {
            return alert('La contraseña es demasiado larga. Usa una más corta.');
        }
        if (passwordNueva !== confirmarPassword) return alert('Las contraseñas no coinciden.');
        try {
            const res = await fetch(`${API_BASE_URL}/api/password/reset`, {
                method: 'POST',
                credentials: 'include',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ token: tokenRecuperacion, password: passwordNueva })
            });
            const data = await res.json();
            if (res.ok) {
                setUser(null);
                setPassword('');
                setPasswordNueva('');
                setConfirmarPassword('');
                setIsLogin(true);
                navigate('/auth', { replace: true });
                alert(data.message || 'Contraseña actualizada. Inicia sesión de nuevo.');
            } else {
                alert(data.error || 'El enlace no es válido o ya venció.');
            }
        } catch (err) {
            console.error(err);
            alert('Error de conexión');
        }
    };

    const enlaceFaq = (
        <Link
            to="/faq"
            state={{ modo: isLogin ? 'login' : 'registro' }}
            className="auth-faq-link"
        >
            ❓ Preguntas frecuentes y ayuda (FAQ)
        </Link>
    );

    return (
        <div id="auth-flow" className="center-screen">
            {tokenRecuperacion && estadoTokenActual === 'valido' ? (
                <div className="form-wrapper" id="restablecer-password-container">
                    <div className="auth-header">
                        <h2>Crear contraseña nueva</h2>
                        <p className="auth-subtitle">Elige una clave nueva de al menos 6 caracteres.</p>
                    </div>
                    <form className="auth-form" onSubmit={handleRestablecerPassword}>
                        <div className="auth-field">
                            <label htmlFor="auth-reset-pass">Nueva contraseña</label>
                            <input id="auth-reset-pass" type="password" placeholder="Mínimo 6 caracteres" required minLength="6" maxLength="128"
                                autoComplete="new-password" value={passwordNueva} onChange={e => setPasswordNueva(e.target.value)} />
                        </div>
                        <div className="auth-field">
                            <label htmlFor="auth-reset-confirm">Confirmar contraseña</label>
                            <input id="auth-reset-confirm" type="password" placeholder="Repite la contraseña" required minLength="6" maxLength="128"
                                autoComplete="new-password" value={confirmarPassword} onChange={e => setConfirmarPassword(e.target.value)} />
                        </div>
                        <button type="submit">Guardar contraseña</button>
                    </form>
                    <div className="auth-footer">
                        {enlaceFaq}
                    </div>
                </div>
            ) : tokenRecuperacion ? (
                <div className="form-wrapper" id="restablecer-password-container">
                    <div className="auth-header">
                        <h2>Verificar enlace</h2>
                        <p className="auth-subtitle">{estadoTokenActual === 'validando'
                            ? 'Comprobando el enlace de recuperación…'
                            : estadoTokenActual === 'invalido'
                                ? 'Este enlace ya fue usado o venció. Solicita uno nuevo para continuar.'
                                : 'No se pudo verificar el enlace. Recarga la página e inténtalo de nuevo.'}</p>
                    </div>
                    {estadoTokenActual === 'invalido' && (
                        <button type="button" onClick={() => {
                            setRecuperando(true);
                            setIsLogin(true);
                            navigate('/auth', { replace: true });
                        }}>Solicitar un enlace nuevo</button>
                    )}
                    <div className="auth-footer">
                        {enlaceFaq}
                    </div>
                </div>
            ) : recuperando ? (
                <div className="form-wrapper" id="recuperar-password-container">
                    <div className="auth-header">
                        <h2>Recuperar contraseña</h2>
                        <p className="auth-subtitle">Te enviaremos un enlace de un solo uso a tu correo.</p>
                    </div>
                    <form className="auth-form" onSubmit={handleSolicitarRecuperacion}>
                        <div className="auth-field">
                            <label htmlFor="auth-forgot-email">Correo electrónico</label>
                            <input id="auth-forgot-email" type="email" placeholder="tu@correo.com" required maxLength="120"
                                autoComplete="email" value={email} onChange={e => setEmail(e.target.value)} />
                        </div>
                        <button type="submit">Enviar enlace</button>
                    </form>
                    <div className="auth-footer">
                        <p className="switch-text">
                            <a href="#" onClick={e => { e.preventDefault(); setRecuperando(false); setIsLogin(true); }}>← Volver a iniciar sesión</a>
                        </p>
                        {enlaceFaq}
                    </div>
                </div>
            ) : !isLogin ? (
                <div className="form-wrapper" id="registro-container">
                    <div className="auth-header">
                        <h2>Crear cuenta</h2>
                        <p className="auth-subtitle">Completa tus datos para empezar a jugar.</p>
                    </div>
                    <form id="registro-form" className="auth-form" onSubmit={handleRegister}>
                        <div className="auth-field">
                            <label htmlFor="auth-reg-user">Usuario</label>
                            <input id="auth-reg-user" type="text" placeholder="Ej: ReyAzul" required minLength="3" maxLength="30"
                                autoComplete="username" value={username} onChange={e => setUsername(e.target.value)} />
                            <div className={usernameStatus.className}>{usernameStatus.text}</div>
                        </div>

                        <div className="auth-field">
                            <label htmlFor="auth-reg-email">Correo electrónico</label>
                            <input id="auth-reg-email" type="email" placeholder="tu@correo.com" required maxLength="120"
                                autoComplete="email" value={email} onChange={e => setEmail(e.target.value)} />
                            <div className={emailStatus.className}>{emailStatus.text}</div>
                        </div>

                        <div className="auth-field">
                            <label htmlFor="auth-reg-pass">Contraseña</label>
                            <input id="auth-reg-pass" type="password" placeholder="Mínimo 6 caracteres" required minLength="6" maxLength="128"
                                autoComplete="new-password" value={password} onChange={e => setPassword(e.target.value)} />
                        </div>

                        <div className="auth-field">
                            <div className="auth-label-row">
                                <label htmlFor="auth-reg-tag">Player Tag de Clash Royale</label>
                                <button
                                    type="button"
                                    className={`auth-help-btn ${ayudaAbierta === 'tag' ? 'activo' : ''}`}
                                    aria-expanded={ayudaAbierta === 'tag'}
                                    aria-controls="ayuda-player-tag"
                                    aria-label="¿Dónde está el Player Tag y qué función cumple en la página?"
                                    title="¿Dónde está y para qué sirve?"
                                    onClick={() => alternarAyuda('tag')}
                                >
                                    ?
                                </button>
                            </div>
                            {ayudaAbierta === 'tag' && (
                                <div id="ayuda-player-tag" className="auth-help-box" role="region" aria-label="Ayuda sobre el Player Tag">
                                    <p><strong>📍 ¿Dónde está?</strong> Abre Clash Royale y toca tu perfil (arriba a la izquierda). Está justo debajo de tu nombre y empieza con <code>#</code> (tócalo para copiarlo).</p>
                                    <p><strong>⚙️ ¿Para qué sirve aquí?</strong> Identifica tus partidas automáticamente en Clash Royale para detectar quién ganó y acreditar el premio.</p>
                                    <Link to="/faq#player-tag" state={{ modo: 'registro' }} className="auth-help-link">Ver más en el FAQ →</Link>
                                </div>
                            )}
                            <input id="auth-reg-tag" type="text" placeholder="#2PP..." required
                                pattern="#[0289PYLQGRJCUV]{3,}" title="Debe empezar con # seguido de caracteres válidos de Clash Royale"
                                value={playerTag} onChange={e => setPlayerTag(e.target.value)} />
                            <div className={playerTagStatus.className}>{playerTagStatus.text}</div>
                        </div>

                        <div className="auth-field">
                            <div className="auth-label-row">
                                <label htmlFor="auth-reg-token">Token de API de Clash Royale</label>
                                <button
                                    type="button"
                                    className={`auth-help-btn ${ayudaAbierta === 'token' ? 'activo' : ''}`}
                                    aria-expanded={ayudaAbierta === 'token'}
                                    aria-controls="ayuda-api-token"
                                    aria-label="¿Dónde está el Token de API y qué función cumple en la página?"
                                    title="¿Dónde está y para qué sirve?"
                                    onClick={() => alternarAyuda('token')}
                                >
                                    ?
                                </button>
                            </div>
                            {ayudaAbierta === 'token' && (
                                <div id="ayuda-api-token" className="auth-help-box" role="region" aria-label="Ayuda sobre el Token de API">
                                    <p><strong>📍 ¿Dónde está?</strong> En Clash Royale abre <strong>☰</strong> (arriba a la derecha) → <strong>Ajustes</strong> → <strong>Más ajustes</strong> → baja hasta <strong>Token de API</strong> y pulsa <strong>Mostrar / Copiar</strong>.</p>
                                    <p><strong>⚙️ ¿Para qué sirve aquí?</strong> Confirma que esa cuenta de Clash Royale es tuya. Se guarda cifrado solo mientras verificamos tu cuenta y luego se borra (puedes jugar desde ya; al verificarla se habilitan los retiros).</p>
                                    <Link to="/faq#token-api" state={{ modo: 'registro' }} className="auth-help-link">Ver más en el FAQ →</Link>
                                </div>
                            )}
                            <input id="auth-reg-token" type="password" placeholder="Pega aquí tu Token de API" required autoComplete="off"
                                maxLength="256" value={playerApiToken} onChange={e => setPlayerApiToken(e.target.value)} />
                        </div>

                        <div className="auth-field">
                            <label htmlFor="auth-reg-phone">Celular (WhatsApp)</label>
                            <input id="auth-reg-phone" type="tel" placeholder="Ej: 300 123 4567" required minLength="7" maxLength="30"
                                title="Lo usamos para contactarte por WhatsApp si ganas un sorteo"
                                autoComplete="tel" value={telefono} onChange={e => setTelefono(e.target.value)} />
                        </div>

                        <AceptacionLegal acepta={acepta} onChange={setAcepta} />
                        <button type="submit">Registrarse</button>
                    </form>
                    <div className="auth-footer">
                        <p className="switch-text">
                            ¿Ya tienes cuenta?{' '}
                            <a href="#" onClick={(e) => { e.preventDefault(); setAyudaAbierta(null); setIsLogin(true); }}>Iniciar sesión</a>
                        </p>
                        {enlaceFaq}
                    </div>
                </div>
            ) : (
                <div className="form-wrapper" id="login-container">
                    <div className="auth-header">
                        <h2>Iniciar sesión</h2>
                        <p className="auth-subtitle">Ingresa con tu correo y contraseña.</p>
                    </div>
                    <form id="login-form" className="auth-form" onSubmit={handleLogin}>
                        <div className="auth-field">
                            <label htmlFor="auth-login-email">Correo electrónico</label>
                            <input id="auth-login-email" type="email" placeholder="tu@correo.com" required
                                autoComplete="email" value={email} onChange={e => setEmail(e.target.value)} />
                        </div>
                        <div className="auth-field">
                            <label htmlFor="auth-login-pass">Contraseña</label>
                            <input id="auth-login-pass" type="password" placeholder="Tu contraseña" required maxLength="128"
                                autoComplete="current-password" value={password} onChange={e => setPassword(e.target.value)} />
                        </div>
                        <button type="submit">Entrar</button>
                    </form>
                    <div className="auth-footer">
                        <p className="switch-text">
                            <a href="#" onClick={e => { e.preventDefault(); setRecuperando(true); }}>¿Olvidaste tu contraseña?</a>
                        </p>
                        <p className="switch-text">
                            ¿Nuevo en la plataforma?{' '}
                            <a href="#" onClick={(e) => { e.preventDefault(); setAyudaAbierta(null); setIsLogin(false); }}>Crear cuenta</a>
                        </p>
                        {enlaceFaq}
                    </div>
                </div>
            )}
        </div>
    );
}
