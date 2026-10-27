import React, { useState, useEffect } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useAppStore } from '../store/useAppStore';
import { API_BASE_URL } from '../store/llamarApi';
import { esperarSolicitudesPush } from '../services/pushTokens';
import AceptacionLegal from '../components/AceptacionLegal';
import './Auth.css';

const excedeLimiteBcrypt = value => new TextEncoder().encode(value).length > 72;

export default function Auth() {
    const [isLogin, setIsLogin] = useState(false);
    const [recuperando, setRecuperando] = useState(false);
    const navigate = useNavigate();
    const location = useLocation();
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

    // Debounce effects
    useEffect(() => {
        if (isLogin) return;
        const delayDebounceFn = setTimeout(async () => {
            if (!username || username.length < 3) {
                setUsernameStatus({ text: '⚠️ Mínimo 3 caracteres', className: 'tag-status visible error' });
                setUsernameValid(false);
                return;
            }
            setUsernameStatus({ text: '🔍 Verificando...', className: 'tag-status visible searching' });
            try {
                const res = await fetch(`${API_BASE_URL}/api/check-username/${encodeURIComponent(username)}`);
                if (res.status === 429) {
                    setUsernameStatus({ text: '⏳ Demasiadas solicitudes, espera unos segundos', className: 'tag-status visible error' });
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
            } catch (e) {
                setUsernameStatus({ text: '⚠️ Error de conexión', className: 'tag-status visible error' });
                setUsernameValid(false);
            }
        }, 500);

        return () => clearTimeout(delayDebounceFn);
    }, [username, isLogin]);

    useEffect(() => {
        if (isLogin) return;
        const delayDebounceFn = setTimeout(async () => {
            if (!email || !email.match(/^[^\s@]+@[^\s@]+\.[^\s@]+$/)) {
                setEmailStatus({ text: '⚠️ Formato de correo inválido', className: 'tag-status visible error' });
                setEmailValid(false);
                return;
            }
            setEmailStatus({ text: '🔍 Verificando...', className: 'tag-status visible searching' });
            try {
                const res = await fetch(`${API_BASE_URL}/api/check-email/${encodeURIComponent(email)}`);
                if (res.status === 429) {
                    setEmailStatus({ text: '⏳ Demasiadas solicitudes, espera unos segundos', className: 'tag-status visible error' });
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
            } catch (e) {
                setEmailStatus({ text: '⚠️ Error de conexión', className: 'tag-status visible error' });
                setEmailValid(false);
            }
        }, 500);

        return () => clearTimeout(delayDebounceFn);
    }, [email, isLogin]);

    useEffect(() => {
        if (isLogin) return;
        const delayDebounceFn = setTimeout(async () => {
            const tag = playerTag.trim();
            if (!tag || !tag.startsWith('#')) {
                setPlayerTagStatus({ text: '', className: 'tag-status' });
                setPlayerTagValid(false);
                return;
            }
            if (!tag.match(/^#[0289PYLQGRJCUV]{3,}$/i)) {
                setPlayerTagStatus({ text: '⚠️ Formato inválido', className: 'tag-status visible error' });
                setPlayerTagValid(false);
                return;
            }
            setPlayerTagStatus({ text: '🔍 Verificando...', className: 'tag-status visible searching' });
            try {
                const res = await fetch(`${API_BASE_URL}/api/verify-tag/${encodeURIComponent(tag)}`);
                if (res.status === 429) {
                    setPlayerTagStatus({ text: '⏳ Demasiadas solicitudes, espera unos segundos', className: 'tag-status visible error' });
                    setPlayerTagValid(false);
                    return;
                }
                const data = await res.json();
                if (data.found) {
                    setPlayerTagStatus({ text: `✅ Tag encontrado: ${data.name} (${data.trophies} 🏆)`, className: 'tag-status visible found' });
                    setPlayerTagValid(true);
                } else {
                    setPlayerTagStatus({ text: data.message || '❌ Usuario no encontrado', className: 'tag-status visible not-found' });
                    setPlayerTagValid(false);
                }
            } catch (e) {
                setPlayerTagStatus({ text: '⚠️ Error de conexión', className: 'tag-status visible error' });
                setPlayerTagValid(false);
            }
        }, 500);

        return () => clearTimeout(delayDebounceFn);
    }, [playerTag, isLogin]);

    const handleRegister = async (e) => {
        e.preventDefault();
        if (!usernameValid) return alert('❌ El formato del nombre de usuario no es válido');
        if (!emailValid) return alert('❌ El formato del correo no es válido');
        if (!playerTagValid) return alert('❌ El Player Tag no es válido. Debe mostrar tu nombre de Clash Royale');
        if (!playerApiToken.trim()) return alert('Ingresa el token de API de Clash Royale para la verificación manual de tu cuenta.');
        if (!acepta) return alert('❌ Debes aceptar los Términos de Servicio y la Política de Privacidad');
        if (excedeLimiteBcrypt(password)) return alert('La contraseña no puede superar 72 bytes UTF-8. Los acentos y emojis pueden ocupar más de un byte.');

        try {
            await esperarSolicitudesPush();
            const res = await fetch(API_BASE_URL + '/api/register', {
                method: 'POST',
                credentials: 'include',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ username, email, password, playerTag, playerApiToken, telefono, aceptaTerminos: true })
            });
            const data = await res.json();
            setPlayerApiToken('');
            if (res.ok) {
                alert(data.message || 'Solicitud recibida. Si los datos permiten crear una cuenta, podrás iniciar sesión. Los retiros se habilitan después de la verificación manual del Player Tag.');
                setIsLogin(true);
            } else {
                alert('Error: ' + (data.error || 'Revisa los datos'));
            }
        } catch (e) {
            console.error(e);
            setPlayerApiToken('');
            alert('Error de conexión');
        }
    };

    const handleLogin = async (e) => {
        e.preventDefault();
        if (excedeLimiteBcrypt(password)) return alert('La contraseña no puede superar 72 bytes UTF-8. Si es una contraseña antigua, puedes recuperarla por correo.');
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
        } catch (e) {
            console.error(e);
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
        } catch (e) {
            console.error(e);
            alert('Error de conexión');
        }
    };

    const handleRestablecerPassword = async (e) => {
        e.preventDefault();
        if (passwordNueva.length < 6 || passwordNueva.length > 128) {
            return alert('La contraseña debe tener entre 6 y 128 caracteres.');
        }
        if (excedeLimiteBcrypt(passwordNueva)) {
            return alert('La contraseña no puede superar 72 bytes UTF-8. Los acentos y emojis pueden ocupar más de un byte.');
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
        } catch (e) {
            console.error(e);
            alert('Error de conexión');
        }
    };

    return (
        <div id="auth-flow" className="center-screen">
            {tokenRecuperacion && estadoTokenActual === 'valido' ? (
                <div className="form-wrapper" id="restablecer-password-container">
                    <h2>Crear contraseña nueva</h2>
                    <form onSubmit={handleRestablecerPassword}>
                        <input type="password" placeholder="Nueva contraseña" required minLength="6" maxLength="128"
                            autoComplete="new-password" value={passwordNueva} onChange={e => setPasswordNueva(e.target.value)} />
                        <small>Entre 6 y 128 caracteres, máximo 72 bytes UTF-8.</small>
                        <input type="password" placeholder="Repite la contraseña" required minLength="6" maxLength="128"
                            autoComplete="new-password" value={confirmarPassword} onChange={e => setConfirmarPassword(e.target.value)} />
                        <button type="submit">Guardar contraseña</button>
                    </form>
                </div>
            ) : tokenRecuperacion ? (
                <div className="form-wrapper" id="restablecer-password-container">
                    <h2>Verificar enlace</h2>
                    <p>{estadoTokenActual === 'validando'
                        ? 'Comprobando el enlace de recuperación…'
                        : estadoTokenActual === 'invalido'
                            ? 'Este enlace ya fue usado o venció. Solicita uno nuevo para continuar.'
                            : 'No se pudo verificar el enlace. Recarga la página e inténtalo de nuevo.'}</p>
                    {estadoTokenActual === 'invalido' && (
                        <button type="button" onClick={() => {
                            setRecuperando(true);
                            setIsLogin(true);
                            navigate('/auth', { replace: true });
                        }}>Solicitar un enlace nuevo</button>
                    )}
                </div>
            ) : recuperando ? (
                <div className="form-wrapper" id="recuperar-password-container">
                    <h2>Recuperar contraseña</h2>
                    <form onSubmit={handleSolicitarRecuperacion}>
                        <input type="email" placeholder="Correo de tu cuenta" required maxLength="120"
                            autoComplete="email" value={email} onChange={e => setEmail(e.target.value)} />
                        <button type="submit">Enviar enlace</button>
                    </form>
                    <p className="switch-text"><a href="#" onClick={e => { e.preventDefault(); setRecuperando(false); setIsLogin(true); }}>Volver a entrar</a></p>
                </div>
            ) : !isLogin ? (
                <div className="form-wrapper" id="registro-container">
                    <h2>Crear Cuenta</h2>
                    <form id="registro-form" onSubmit={handleRegister}>
                        <input type="text" placeholder="Usuario" required minLength="3"
                            value={username} onChange={e => setUsername(e.target.value)} />
                        <div className={usernameStatus.className}>{usernameStatus.text}</div>

                        <input type="email" placeholder="Email" required
                            value={email} onChange={e => setEmail(e.target.value)} />
                        <div className={emailStatus.className}>{emailStatus.text}</div>

                        <input type="password" placeholder="Contraseña" required minLength="6" maxLength="128"
                            value={password} onChange={e => setPassword(e.target.value)} />
                        <small>Entre 6 y 128 caracteres, máximo 72 bytes UTF-8.</small>

                        <input type="text" placeholder="Player Tag Clash Royale (#XXXXXXX)" required
                            pattern="#[0289PYLQGRJCUV]{3,}" title="Debe empezar con # seguido de 3+ caracteres válidos"
                            value={playerTag} onChange={e => setPlayerTag(e.target.value)} />
                        <div className={playerTagStatus.className}>{playerTagStatus.text}</div>

                        <input type="password" placeholder="Token de API de Clash Royale" required autoComplete="off"
                            maxLength="256" value={playerApiToken} onChange={e => setPlayerApiToken(e.target.value)} />
                        <small>Genera el token desde los ajustes de Clash Royale. Un administrador lo comprobará manualmente con el bot de Discord. Lo guardamos cifrado mientras tu cuenta esté pendiente y lo eliminamos al verificarla. Hasta entonces podrás usar la plataforma, excepto retirar saldo.</small>

                        <input type="tel" placeholder="Celular (WhatsApp)" required minLength="7" maxLength="30"
                            title="Ingresa un celular único para contactarte si ganas un sorteo"
                            value={telefono} onChange={e => setTelefono(e.target.value)} />

                        <AceptacionLegal acepta={acepta} onChange={setAcepta} />
                        <button type="submit">Registrarse</button>
                    </form>
                    <p className="switch-text">¿Ya tienes cuenta? <a href="#" onClick={(e) => { e.preventDefault(); setIsLogin(true); }}>Entrar</a></p>
                </div>
            ) : (
                <div className="form-wrapper" id="login-container">
                    <h2>Entrar</h2>
                    <form id="login-form" onSubmit={handleLogin}>
                        <input type="email" placeholder="Email" required
                            value={email} onChange={e => setEmail(e.target.value)} />
                        <input type="password" placeholder="Contraseña" required maxLength="128"
                            value={password} onChange={e => setPassword(e.target.value)} />
                        <button type="submit">Entrar</button>
                    </form>
                    <p className="switch-text"><a href="#" onClick={e => { e.preventDefault(); setRecuperando(true); }}>¿Olvidaste tu contraseña?</a></p>
                    <p className="switch-text">¿Nuevo? <a href="#" onClick={(e) => { e.preventDefault(); setIsLogin(false); }}>Crear cuenta</a></p>
                </div>
            )}
        </div>
    );
}
