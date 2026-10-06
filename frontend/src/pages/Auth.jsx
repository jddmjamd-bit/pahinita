import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import './Auth.css';

const API_BASE_URL = 'https://torneos-beta.onrender.com'; // TODO: use env variables later

export default function Auth() {
    const [isLogin, setIsLogin] = useState(false);
    const navigate = useNavigate();

    // Formularios
    const [username, setUsername] = useState('');
    const [email, setEmail] = useState('');
    const [password, setPassword] = useState('');
    const [playerTag, setPlayerTag] = useState('');
    const [telefono, setTelefono] = useState('');

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
                const data = await res.json();
                if (data.available) {
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
                const data = await res.json();
                if (data.available) {
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
                const data = await res.json();
                if (data.found) {
                    setPlayerTagStatus({ text: `✅ ¿Tu nombre es ${data.name}? (${data.trophies} 🏆)`, className: 'tag-status visible found' });
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
        if (!usernameValid) return alert('❌ El nombre de usuario no es válido o ya está en uso');
        if (!emailValid) return alert('❌ El correo no es válido o ya está registrado');
        if (!playerTagValid) return alert('❌ El Player Tag no es válido. Debe mostrar tu nombre de Clash Royale');

        try {
            const res = await fetch(API_BASE_URL + '/api/register', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ username, email, password, playerTag, telefono })
            });
            const data = await res.json();
            if (res.ok) {
                alert('¡Cuenta creada! Ahora inicia sesión.');
                setIsLogin(true);
            } else {
                alert('Error: ' + (data.error || 'Revisa los datos'));
            }
        } catch (e) {
            console.error(e);
            alert('Error de conexión');
        }
    };

    const handleLogin = async (e) => {
        e.preventDefault();
        try {
            const res = await fetch(API_BASE_URL + '/api/login', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ email, password })
            });
            const data = await res.json();
            if (res.ok) {
                // Guardar usuario en localStorage temporalmente hasta F3
                localStorage.setItem('currentUser', JSON.stringify(data.user));
                navigate('/lobby');
            } else {
                alert(data.error);
            }
        } catch (e) {
            console.error(e);
            alert('Error de conexión');
        }
    };

    return (
        <div id="auth-flow" className="center-screen">
            {!isLogin ? (
                <div className="form-wrapper" id="registro-container">
                    <h2>Crear Cuenta</h2>
                    <form id="registro-form" onSubmit={handleRegister}>
                        <input type="text" placeholder="Usuario" required minLength="3"
                               value={username} onChange={e => setUsername(e.target.value)} />
                        <div className={usernameStatus.className}>{usernameStatus.text}</div>
                        
                        <input type="email" placeholder="Email" required
                               value={email} onChange={e => setEmail(e.target.value)} />
                        <div className={emailStatus.className}>{emailStatus.text}</div>
                        
                        <input type="password" placeholder="Contraseña" required
                               value={password} onChange={e => setPassword(e.target.value)} />
                        
                        <input type="text" placeholder="Player Tag Clash Royale (#XXXXXXX)" required
                               pattern="#[0289PYLQGRJCUV]{3,}" title="Debe empezar con # seguido de 3+ caracteres válidos"
                               value={playerTag} onChange={e => setPlayerTag(e.target.value)} />
                        <div className={playerTagStatus.className}>{playerTagStatus.text}</div>
                        
                        <input type="tel" placeholder="Teléfono (WhatsApp)" required minLength="7"
                               title="Ingresa tu número para contactarte si ganas un sorteo"
                               value={telefono} onChange={e => setTelefono(e.target.value)} />
                        
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
                        <input type="password" placeholder="Contraseña" required
                               value={password} onChange={e => setPassword(e.target.value)} />
                        <button type="submit">Entrar</button>
                    </form>
                    <p className="switch-text">¿Nuevo? <a href="#" onClick={(e) => { e.preventDefault(); setIsLogin(false); }}>Crear cuenta</a></p>
                </div>
            )}
        </div>
    );
}
