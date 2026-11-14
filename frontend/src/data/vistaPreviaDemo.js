/**
 * Datos sintéticos exclusivos para el Modo Vista Previa (visitantes sin sesión).
 * Nunca incluye información real de usuarios ni realiza conexiones al backend.
 */

const AHORA_ISO = new Date(Date.now() - 12 * 60 * 1000).toISOString();
const HACE_5_MIN_ISO = new Date(Date.now() - 5 * 60 * 1000).toISOString();
const HACE_2_HORAS_ISO = new Date(Date.now() - 2 * 60 * 60 * 1000).toISOString();
const EN_4_HORAS_ISO = new Date(Date.now() + 4 * 60 * 60 * 1000).toISOString();

// Imagen PNG 1x1 transparente válida para mostrar el diseño de comprobantes de ejemplo sin archivos externos.
const PNG_DEMO_DATA_URI =
    'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4////fwAJ+wP9KobjigAAAABJRU5ErkJggg==';

export const DEMO_CHAT_MENSAJES = {
    general: [
        {
            canal: 'general',
            usuario: 'ReyAzul_Demo',
            texto: '¡Buenas! ¿Alguien para un torneo 1v1 en modo Elección?',
            tipo: 'texto',
            fecha: AHORA_ISO
        },
        {
            canal: 'general',
            usuario: 'Valquiria_Demo',
            texto: 'Yo estoy libre, ya puse búsqueda en la pestaña Jugar.',
            tipo: 'texto',
            fecha: HACE_5_MIN_ISO
        }
    ],
    anuncios: [
        {
            canal: 'anuncios',
            usuario: 'Administrador',
            texto: 'Bienvenidos a la vista previa. Al iniciar sesión verás aquí los anuncios oficiales, nuevos sorteos y cortes de ranking.',
            tipo: 'texto',
            fecha: HACE_2_HORAS_ISO
        }
    ],
    clash: [
        {
            canal: 'clash',
            usuario: 'MagoElectrico_Demo',
            texto: 'Recuerden acordar el modo (Elección o Triple elección) en el chat de la sala privada antes de confirmar.',
            tipo: 'texto',
            fecha: AHORA_ISO
        }
    ],
    clash_logs: [
        {
            canal: 'clash_logs',
            usuario: 'Sistema',
            texto: 'ReyAzul_Demo está buscando partida 1v1 (ejemplo de registro en vivo).',
            tipo: 'texto',
            fecha: AHORA_ISO
        },
        {
            canal: 'clash_logs',
            usuario: 'Sistema',
            texto: 'Valquiria_Demo ganó el torneo contra Montapuercos_Demo · Premio acreditado automáticamente.',
            tipo: 'texto',
            fecha: HACE_5_MIN_ISO
        }
    ],
    pagos: [
        {
            canal: 'pagos',
            usuario: 'Administrador',
            texto: PNG_DEMO_DATA_URI,
            tipo: 'imagen',
            fecha: HACE_2_HORAS_ISO,
            evidencia: {
                id: 'demo-evidencia-1',
                para: 'JugadorDemo',
                monto: 25000,
                metodo: 'Nequi',
                nota: 'Comprobante de demostración (datos de ejemplo en modo vista previa)'
            }
        }
    ]
};

export const DEMO_MATCHMAKING_CONFIG = {
    perfilCualitativo: 'Competitivo (Ejemplo)',
    variables: {
        winRate: true,
        clash: true,
        liga: false
    }
};

export const DEMO_BUSQUEDAS_MATCHMAKING = [
    {
        buscadorId: 9001,
        username: 'ReyAzul_Demo',
        etiquetaNivel: 'Nivel similar al tuyo (ejemplo)',
        habilitada: true,
        segundosRestantesAceptar: 420,
        recibidoEn: Date.now()
    },
    {
        buscadorId: 9002,
        username: 'CampeonArena_Demo',
        etiquetaNivel: 'Rival superior · Oleada progresiva (ejemplo)',
        esBuscadorMejor: true,
        habilitada: false,
        segundosParaHabilitar: 75,
        segundosRestantesAceptar: 360,
        recibidoEn: Date.now()
    }
];

export const DEMO_SALA_PRIVADA = {
    rival: {
        id: 9001,
        username: 'ReyAzul_Demo',
        total_partidas: 28,
        total_victorias: 18,
        faltas: 0,
        salidas_chat: 1
    },
    maxMonto: 10000,
    modoEjemplo: 'Elección',
    montoEjemplo: '5000',
    gananciaEjemplo: 'Si ganas recibes: $9.000 (ejemplo)',
    mensajes: [
        {
            usuario: 'ReyAzul_Demo',
            texto: '¡Hola! ¿Jugamos en modo Elección por $5.000?',
            fecha: AHORA_ISO
        },
        {
            usuario: 'Visitante',
            texto: '¡Listo! Acordemos Elección y $5.000 en la configuración de abajo.',
            fecha: HACE_5_MIN_ISO
        }
    ]
};

export const DEMO_SORTEOS = {
    poolAmount: 45000,
    userTickets: 3,
    userAcumulado: 600,
    sorteos: [
        {
            id: 501,
            nombre: 'Pass Royale Diamante (Ejemplo)',
            categoria: 'pass',
            precio: 30000,
            tickets_actuales: 18,
            tickets_necesarios: 30,
            mis_tickets: 1,
            estado: 'activo',
            fecha_limite: EN_4_HORAS_ISO
        },
        {
            id: 502,
            nombre: '500 Gemas (Ejemplo)',
            categoria: 'gemas',
            precio: 15000,
            tickets_actuales: 15,
            tickets_necesarios: 15,
            mis_tickets: 0,
            estado: 'completado',
            ganador_nombre: 'Valquiria_Demo',
            fecha_limite: HACE_2_HORAS_ISO
        }
    ],
    encuesta: [
        { categoria: 'pass', votos: 14 },
        { categoria: 'gemas', votos: 9 },
        { categoria: 'evoluciones', votos: 6 },
        { categoria: 'especial', votos: 4 }
    ]
};

export const DEMO_LEADERBOARD = {
    premios: [
        { posicion: 1, premio: 25000 },
        { posicion: 2, premio: 15000 },
        { posicion: 3, premio: 10000 }
    ],
    miPosicion: {
        posicion: 6,
        totalUsuarios: 38,
        cambioPosicion: 2,
        enTop50: true
    },
    ranking: [
        { id: 801, posicion: 1, username: 'ReyAzul_Demo', victorias: 19, monto: 95000, total_partidas: 24, cambio_posicion: 1 },
        { id: 802, posicion: 2, username: 'Valquiria_Demo', victorias: 16, monto: 80000, total_partidas: 22, cambio_posicion: 2 },
        { id: 803, posicion: 3, username: 'MagoElectrico_Demo', victorias: 14, monto: 70000, total_partidas: 20, cambio_posicion: -1 },
        { id: 804, posicion: 4, username: 'Montapuercos_Demo', victorias: 11, monto: 55000, total_partidas: 17, cambio_posicion: 0 }
    ],
    ganadores: [
        {
            corte_id: 12,
            periodo: 'semana',
            posicion: 1,
            user_id: 801,
            username: 'ReyAzul_Demo',
            victorias: 32,
            premio: 25000,
            completado_en: HACE_2_HORAS_ISO
        },
        {
            corte_id: 12,
            periodo: 'semana',
            posicion: 2,
            user_id: 802,
            username: 'Valquiria_Demo',
            victorias: 27,
            premio: 15000,
            completado_en: HACE_2_HORAS_ISO
        }
    ]
};

export const DEMO_CHATBOT_MENSAJES = [
    {
        id: 'demo-bot-1',
        rol: 'bot',
        texto: '¡Hola! Estás viendo una demostración del asistente virtual. Cuando inicies sesión podrás hacerme cualquier pregunta sobre torneos, recargas, retiros o sorteos.'
    },
    {
        id: 'demo-user-1',
        rol: 'usuario',
        texto: '¿Cómo funciona una partida 1 contra 1?'
    },
    {
        id: 'demo-bot-2',
        rol: 'bot',
        texto: 'En la sección Jugar pulsas «Buscar partida» para emparejarte con un rival de nivel justo. En la sala privada acuerdan el modo y el monto, juegan en Clash Royale y la plataforma detecta automáticamente al ganador para acreditar el premio.'
    }
];

export const DEMO_SOPORTE_TICKETS = [
    {
        id: 'demo-ticket-1',
        categoria: 'pregunta',
        asunto: 'Consulta de ejemplo: verificación de cuenta',
        estado: 'resuelto',
        fecha_creacion: HACE_2_HORAS_ISO,
        mensaje: '¿Puedo jugar partidas mientras el administrador revisa mi Token de API?',
        respuesta: '¡Hola! Sí, puedes buscar partidas y participar desde el primer momento. La verificación manual solo se requiere antes de solicitar retiros. (Ejemplo de vista previa)',
        fecha_respuesta: AHORA_ISO
    }
];

export const DEMO_REFERIDOS = {
    success: true,
    codigoReferido: 'DEMO2026',
    porcentajeCompartidoGeneral: 25,
    mensajeCorto: '¡Juega conmigo en UltimateClash y participa en torneos de Clash Royale con premios reales! Regístrate con mi código:',
    mensajeCortoDefecto: '¡Juega conmigo en UltimateClash y participa en torneos de Clash Royale con premios reales! Regístrate con mi código:',
    estadisticas: {
        totalReferidos: 3,
        referidosActivos: 2,
        totalPartidasReferidos: 19,
        totalComisionGenerada: 4800,
        totalGanadoReferidor: 3100,
        totalDevueltoReferidos: 1700
    },
    miReferidor: {
        username: 'CamilaArena_Demo',
        codigoUsado: 'CAMILA10',
        porcentajeCompartido: 20,
        partidasJugadas: 12,
        totalRecibidoDevolucion: 450,
        creadoEn: HACE_2_HORAS_ISO
    },
    referidos: [
        {
            referredUserId: 9101,
            username: 'Valquiria_Demo',
            codigoUsado: 'DEMO2026',
            porcentajeCompartido: 25,
            usaPorcentajePersonalizado: false,
            partidasJugadas: 11,
            totalComisionReferido: 2800,
            totalGanadoReferidor: 2100,
            totalDevueltoReferido: 700,
            creadoEn: HACE_2_HORAS_ISO
        },
        {
            referredUserId: 9102,
            username: 'Montapuercos_Demo',
            codigoUsado: 'DEMO2026',
            porcentajeCompartido: 50,
            usaPorcentajePersonalizado: true,
            partidasJugadas: 8,
            totalComisionReferido: 2000,
            totalGanadoReferidor: 1000,
            totalDevueltoReferido: 1000,
            creadoEn: HACE_5_MIN_ISO
        },
        {
            referredUserId: 9103,
            username: 'Arqueras_Demo',
            codigoUsado: 'DEMO2026',
            porcentajeCompartido: 25,
            usaPorcentajePersonalizado: false,
            partidasJugadas: 0,
            totalComisionReferido: 0,
            totalGanadoReferidor: 0,
            totalDevueltoReferido: 0,
            creadoEn: AHORA_ISO
        }
    ],
    historialPagos: [
        {
            id: 9901,
            matchId: 412,
            rolUsuario: 'referidor',
            contraparte: 'Valquiria_Demo',
            montoBase: 300,
            porcentajeCompartido: 25,
            montoReferidor: 225,
            montoDevuelto: 75,
            montoParaUsuario: 225,
            creadoEn: HACE_5_MIN_ISO
        },
        {
            id: 9902,
            matchId: 408,
            rolUsuario: 'referido',
            contraparte: 'CamilaArena_Demo',
            montoBase: 300,
            porcentajeCompartido: 20,
            montoReferidor: 240,
            montoDevuelto: 60,
            montoParaUsuario: 60,
            creadoEn: AHORA_ISO
        }
    ]
};


