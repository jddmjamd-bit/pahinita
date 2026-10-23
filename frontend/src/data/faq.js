/**
 * Contenido de la página de ayuda /faq (roadmap 21: preguntas frecuentes + video).
 *
 * Aquí se escriben TODAS las preguntas; la página (`pages/Faq.jsx`) solo las pinta. Para agregar o editar una
 * pregunta no hay que tocar el componente: basta con editar este archivo.
 *
 * Forma de una pregunta:
 *   id         string único y estable (sirve de enlace directo: /faq#recargar-nequi).
 *   categoria  id de una de las CATEGORIAS de abajo.
 *   pregunta   string.
 *   respuesta  string[] — un párrafo por elemento. Es texto plano: no se interpreta HTML.
 *   pasos      string[] (opcional) — se muestran como lista numerada después de la respuesta.
 *   enlace     { texto, to } (opcional) — botón hacia una pantalla de la app (ruta interna, ej. '/finance').
 *   video      (opcional) — solo para las preguntas que se entienden mejor viéndolas:
 *                { titulo, youtube: 'ID de 11 caracteres', archivo: '/videos/nombre.mp4' }
 *                Se usa `youtube` O `archivo` (si vienen los dos, gana `youtube`). Si ambos están vacíos la página
 *                muestra "Video próximamente", así que se puede dejar el hueco listo y llenarlo después.
 *                - youtube: solo el ID (lo que va después de `v=` en el enlace). Se incrusta con youtube-nocookie.
 *                - archivo: un .mp4 o .webm guardado en `frontend/public/videos/` (se sirve como /videos/...).
 *
 * Nota de vocabulario (tarea F5): no usar "apuesta". El dinero de una partida es el "monto" y jugar es
 * "realizar un torneo".
 */

export const CATEGORIAS = [
    { id: 'inicio', icono: '🚀', nombre: 'Primeros pasos' },
    { id: 'dinero', icono: '💰', nombre: 'Recargas y retiros' },
    { id: 'partidas', icono: '⚔️', nombre: 'Partidas' },
    { id: 'sorteos', icono: '🎁', nombre: 'Sorteos y tickets' },
    { id: 'rankings', icono: '🏆', nombre: 'Rankings y premios' },
    { id: 'chats', icono: '💬', nombre: 'Chats y asistente' },
    { id: 'cuenta', icono: '🛡️', nombre: 'Cuenta y seguridad' }
];

export const PREGUNTAS = [
    // ───────────────────────── Primeros pasos ─────────────────────────
    {
        id: 'que-es',
        categoria: 'inicio',
        pregunta: '¿Qué es esta plataforma y cómo funciona?',
        respuesta: [
            'Es un sitio para jugar partidas de Clash Royale contra otros jugadores poniendo un monto de dinero. Te emparejamos con un rival, acuerdan el modo y el monto en una sala privada y juegan en Clash Royale.',
            'Cuando la partida termina, la plataforma consulta la información de Clash Royale, detecta quién ganó y acredita el premio automáticamente a la cuenta del ganador.'
        ],
        pasos: [
            'Crea tu cuenta con tu Player Tag de Clash Royale.',
            'Recarga saldo en la sección Finanzas.',
            'Entra a Jugar y pulsa Buscar partida.',
            'Acuerda modo y monto con tu rival, confirmen y jueguen.',
            'El ganador recibe el premio en su saldo.'
        ],
        video: { titulo: 'Recorrido general por la plataforma', youtube: '', archivo: '' }
    },
    {
        id: 'registro',
        categoria: 'inicio',
        pregunta: '¿Cómo creo mi cuenta?',
        respuesta: [
            'En la pantalla de entrada elige «Crear cuenta» y llena usuario, correo, contraseña, Player Tag, token de API de Clash Royale y celular. Mientras escribes, la página revisa los formatos y consulta si el Player Tag existe.',
            'Genera el token desde los ajustes del juego. Un administrador lo comprobará manualmente con el bot de Discord. Se conserva cifrado mientras la cuenta está pendiente y se elimina al confirmarla. Puedes usar la plataforma mientras esperas, excepto retirar saldo. El celular debe ser tuyo y no estar registrado en otra cuenta; escribirlo con o sin +57 no crea un número distinto.',
            'También debes aceptar los Términos de Servicio y la Política de Privacidad; sin eso el botón de registro no te deja continuar. Al terminar, vuelves a la pantalla de entrada e inicias sesión con tu correo y contraseña.'
        ],
        video: { titulo: 'Cómo crear tu cuenta paso a paso', youtube: '', archivo: '' }
    },
    {
        id: 'player-tag',
        categoria: 'inicio',
        pregunta: '¿Qué es el Player Tag y dónde lo encuentro?',
        respuesta: [
            'Es el código único de tu cuenta de Clash Royale. Empieza con # y se compone de letras y números (por ejemplo #2PP). Lo usamos para saber cuáles partidas son tuyas y quién ganó.',
            'Lo encuentras dentro del juego, en tu perfil, justo debajo de tu nombre. Escríbelo con el # al inicio. La página muestra el nombre y los trofeos para identificarlo; durante el registro también debes ingresar el token de API generado en los ajustes del juego. Un administrador lo comprobará manualmente con el bot de Discord. Se conserva cifrado hasta la verificación y luego se elimina; mientras tanto, los retiros quedan bloqueados.'
        ],
        video: { titulo: 'Dónde encontrar tu Player Tag en Clash Royale', youtube: '', archivo: '' }
    },
    {
        id: 'registro-no-disponible',
        categoria: 'inicio',
        pregunta: 'No puedo entrar después de crear mi cuenta, ¿qué hago?',
        respuesta: [
            'El registro muestra una confirmación genérica por privacidad. Si el usuario, correo, Player Tag o celular ya están asociados con otra cuenta, no se crea una cuenta nueva. Intenta iniciar sesión con el correo que usaste antes o contacta al soporte.',
            'Si la verificación de propiedad no está disponible, el servicio de Clash Royale debe habilitar el permiso de API antes de aceptar nuevos registros.'
        ]
    },
    {
        id: 'telefono',
        categoria: 'inicio',
        pregunta: '¿Para qué piden mi teléfono?',
        respuesta: [
            'Es para contactarte por WhatsApp si ganas un sorteo. Usa tu celular personal; no se puede registrar el mismo número en dos cuentas. Los formatos local y +57 se reconocen como el mismo celular.'
        ]
    },
    {
        id: 'tema',
        categoria: 'inicio',
        pregunta: '¿Cómo cambio entre modo claro y oscuro?',
        respuesta: [
            'Con el botón redondo con un sol o una luna que está fijo en la esquina inferior derecha de la pantalla. Aparece en todas las secciones.'
        ]
    },
    {
        id: 'navegacion',
        categoria: 'inicio',
        pregunta: '¿Dónde está cada cosa en la página?',
        respuesta: [
            'Todo está en el menú lateral (en el celular, se abre con el botón ☰ arriba a la izquierda):'
        ],
        pasos: [
            'Chat / Anuncios: los canales de conversación y los avisos del administrador.',
            'Clash Royale → Jugar / Privada: busca partida y sala privada con tu rival.',
            'Clash Royale → Sorteos: sorteos activos, tus tickets y la encuesta del próximo premio.',
            'Rankings: los mejores jugadores por periodo y los premios.',
            'Asistente: el asistente que resuelve dudas.',
            'Ayuda / FAQ: esta página.',
            'En tu tarjeta de perfil (arriba del menú) tienes tu saldo y los botones para recargar y retirar.'
        ]
    },

    // ───────────────────────── Recargas y retiros ─────────────────────────
    {
        id: 'metodos-recarga',
        categoria: 'dinero',
        pregunta: '¿Qué formas de recargar saldo hay?',
        respuesta: [
            'Hay dos, en Finanzas → Recargar:',
            'Nequi manual: envías el dinero por Nequi y avisas el pago en la página; un administrador lo revisa y te acredita el saldo.',
            'Tarjeta / PSE (Wompi): pagas en línea y el saldo se acredita automáticamente al confirmarse el pago. Esta opción cobra una comisión de la pasarela, que ves antes de pagar.'
        ],
        enlace: { texto: 'Ir a Finanzas', to: '/finance' }
    },
    {
        id: 'recargar-nequi',
        categoria: 'dinero',
        pregunta: '¿Cómo recargo por Nequi (manual)?',
        respuesta: [
            'Es la opción sin comisión de pasarela, pero requiere que un administrador apruebe el pago, así que no es inmediata (la pantalla indica un tiempo estimado de 1 a 5 minutos).'
        ],
        pasos: [
            'Entra a Finanzas → Recargar → «Nequi Manual».',
            'Envía el dinero al número de Nequi que aparece en esa pantalla.',
            'Guarda el número de referencia del comprobante de Nequi.',
            'En la página escribe el monto que enviaste y la referencia, y pulsa «Notificar Pago».',
            'Espera la aprobación: cuando el administrador la confirme, el saldo se acredita.'
        ],
        enlace: { texto: 'Ir a Finanzas', to: '/finance' },
        video: { titulo: 'Recargar por Nequi paso a paso', youtube: '', archivo: '' }
    },
    {
        id: 'recargar-wompi',
        categoria: 'dinero',
        pregunta: '¿Cómo recargo con tarjeta o PSE (Wompi)?',
        respuesta: [
            'Es la opción automática: pagas en línea y, cuando Wompi confirma el pago, el saldo se acredita solo y recibes una notificación.',
            'En el campo pones cuánto quieres recibir en tu cuenta; la página calcula la comisión de la pasarela y te muestra el total exacto a pagar antes de continuar.'
        ],
        pasos: [
            'Entra a Finanzas → Recargar → «Tarjeta / PSE (Wompi)».',
            'Escribe cuánto quieres recibir (revisa el mínimo que indica la pantalla).',
            'Verifica el total a pagar con la comisión incluida.',
            'Pulsa «Pagar» y completa el pago en la ventana de Wompi con tu tarjeta o PSE.',
            'Espera la confirmación; no cierres sesión mientras tanto.'
        ],
        enlace: { texto: 'Ir a Finanzas', to: '/finance' },
        video: { titulo: 'Recargar con tarjeta o PSE (Wompi)', youtube: '', archivo: '' }
    },
    {
        id: 'comision-wompi',
        categoria: 'dinero',
        pregunta: '¿Por qué debo pagar más de lo que recargo con Wompi?',
        respuesta: [
            'La pasarela de pagos cobra una comisión por procesar tarjetas y PSE. Para que a tu saldo llegue exactamente el monto que pediste, esa comisión se suma al total que pagas. Nosotros no la cobramos: se la queda la pasarela.',
            'Si quieres evitarla, usa la recarga por Nequi manual.'
        ]
    },
    {
        id: 'recarga-no-llega',
        categoria: 'dinero',
        pregunta: 'Recargué pero mi saldo no aparece, ¿qué hago?',
        respuesta: [
            'Depende de cómo recargaste:'
        ],
        pasos: [
            'Nequi manual: el saldo llega cuando un administrador aprueba tu solicitud. Si pasaron varios minutos, revisa que el monto y la referencia que escribiste coincidan con el comprobante.',
            'Wompi: espera a que llegue la notificación de pago confirmado. A veces el banco tarda unos minutos en responder.',
            'Mira el saldo en tu tarjeta de perfil del menú. Si no se actualiza, cierra sesión y vuelve a entrar.',
            'Si sigue sin aparecer, escríbele al Asistente con el monto, la fecha y la referencia o el ID de la transacción para que el administrador lo revise.'
        ],
        enlace: { texto: 'Hablar con el Asistente', to: '/chatbot' }
    },
    {
        id: 'retirar',
        categoria: 'dinero',
        pregunta: '¿Cómo retiro mi dinero?',
        respuesta: [
            'Los retiros se hacen a Nequi o Daviplata y se procesan manualmente; el tiempo estimado que indica la pantalla es de hasta 24 horas.',
            'El saldo que pides retirar se descuenta de tu cuenta al momento de solicitar el retiro.'
        ],
        pasos: [
            'Entra a Finanzas → Retirar.',
            'Escribe el monto a retirar (respeta el mínimo que indica la pantalla y no pases de tu saldo disponible).',
            'Escribe el número de la cuenta de Nequi o Daviplata.',
            'Escribe el nombre completo del titular de esa cuenta.',
            'Pulsa «Solicitar Retiro» y espera la transferencia.'
        ],
        enlace: { texto: 'Ir a Finanzas', to: '/finance' },
        video: { titulo: 'Cómo solicitar un retiro', youtube: '', archivo: '' }
    },
    {
        id: 'retiro-saldo-insuficiente',
        categoria: 'dinero',
        pregunta: 'Me sale «saldo insuficiente» al retirar',
        respuesta: [
            'El monto que pides retirar es mayor que tu saldo disponible. Revisa tu saldo en la tarjeta de perfil del menú y pide un monto menor o igual.',
            'Recuerda que el dinero de una partida en curso o de un torneo que ya realizaste no está disponible hasta que termine la partida.'
        ]
    },
    {
        id: 'limites-dinero',
        categoria: 'dinero',
        pregunta: '¿Hay un mínimo y un máximo para recargar y retirar?',
        respuesta: [
            'Sí. Cada operación tiene un monto mínimo y uno máximo, y pueden cambiar con el tiempo. Si escribes un monto fuera de rango, la página te dice cuál es el límite exacto.',
            'Siempre deben ser pesos enteros, sin decimales.'
        ]
    },

    // ───────────────────────── Partidas ─────────────────────────
    {
        id: 'buscar-partida',
        categoria: 'partidas',
        pregunta: '¿Cómo busco una partida?',
        respuesta: [
            'Entra a Clash Royale → Jugar / Privada. Si no estás en una partida verás la pantalla «¿Listo para jugar?».'
        ],
        pasos: [
            'Pulsa «Buscar partida». Verás «Buscando rival...».',
            'Cuando otro jugador también esté buscando, te llevamos solos a la sala privada.',
            'Si te cansas de esperar, pulsa «Cancelar búsqueda».'
        ],
        enlace: { texto: 'Ir a Jugar', to: '/match' },
        video: { titulo: 'Buscar partida y entrar a la sala', youtube: '', archivo: '' }
    },
    {
        id: 'sala-privada',
        categoria: 'partidas',
        pregunta: '¿Qué hago dentro de la sala privada?',
        respuesta: [
            'Es donde tú y tu rival se ponen de acuerdo antes de jugar. Arriba ves contra quién juegas y el tope máximo de monto permitido. Puedes chatear con el rival en el mini chat.',
            'En «Configuración» escriben el modo de juego (texto libre, por ejemplo «Elección») y el monto. Lo que escribe uno lo ve el otro en vivo. Al poner un monto válido, la página te muestra cuánto recibirías si ganas.'
        ],
        pasos: [
            'Acuerden el modo y el monto por el chat.',
            'Escribe el modo y el monto en los campos de Configuración.',
            'Pulsa «Comenzar». Quedas esperando a que el rival también pulse.',
            'Cuando los dos pulsan, aparece una ventana para aceptar la partida con el modo y el monto: pulsa «Aceptar».',
            'Cuando ambos aceptan, la partida empieza y juegan en Clash Royale.'
        ],
        video: { titulo: 'Cómo acordar modo y monto con tu rival', youtube: '', archivo: '' }
    },
    {
        id: 'montos-partida',
        categoria: 'partidas',
        pregunta: '¿Cuánto dinero puedo poner en una partida?',
        respuesta: [
            'El monto tiene un mínimo y un máximo (hoy, desde $1.000 hasta $10.000) y además no puede superar el tope que aparece en la sala, que depende de tu saldo y el de tu rival. Si te pasas, la página te muestra el error debajo del campo y no te deja comenzar.'
        ]
    },
    {
        id: 'cuanto-gano',
        categoria: 'partidas',
        pregunta: '¿Cuánto gano si le gano a mi rival?',
        respuesta: [
            'Al escribir el monto, la sala te muestra «Si ganas recibes: $...». Es el premio ya con la comisión de la plataforma descontada; ese es el valor que se acredita a tu saldo. Si pierdes, pierdes el monto de la partida.'
        ]
    },
    {
        id: 'quien-gano',
        categoria: 'partidas',
        pregunta: '¿Cómo se decide quién ganó?',
        respuesta: [
            'Automáticamente. Cuando empieza la partida, la plataforma consulta la información de Clash Royale con el Player Tag de cada jugador y detecta el resultado. Tienes hasta 10 minutos para jugarla.',
            'Al terminar verás en pantalla si ganaste o perdiste y, si ganaste, el premio acreditado. Pulsa «Volver» para cerrar el resultado.'
        ],
        video: { titulo: 'Qué pasa cuando termina la partida', youtube: '', archivo: '' }
    },
    {
        id: 'disputa',
        categoria: 'partidas',
        pregunta: 'El resultado no fue claro, ¿qué es una disputa?',
        respuesta: [
            'Si el sistema no puede determinar claramente quién ganó (o el tiempo se acaba sin un resultado válido), se crea una disputa y verás el aviso «Disputa creada».',
            'Un administrador revisa la partida y decide el resultado. No tienes que hacer nada más; el saldo se ajusta cuando se resuelve.'
        ]
    },
    {
        id: 'salir-sala',
        categoria: 'partidas',
        pregunta: '¿Qué pasa si salgo de la sala o mi rival se va?',
        respuesta: [
            'El botón ❌ de la sala cancela la partida para los dos y cuenta como una salida tuya; antes de hacerlo te pide confirmación. Si tu rival es quien sale, recibes el aviso de que la partida fue cancelada y quedas libre para buscar otra.',
            'Mientras negocian puedes rechazar la partida en la ventana de confirmación si no estás de acuerdo con el modo o el monto.'
        ]
    },
    {
        id: 'nadie-responde',
        categoria: 'partidas',
        pregunta: 'Llevo mucho buscando y nadie aparece',
        respuesta: [
            'La búsqueda se cancela sola si pasa un tiempo sin rival y te lo avisa; puedes volver a intentarlo cuando quieras. Hay más probabilidad de encontrar rival cuando hay más gente conectada.',
            'Si recargaste hace poco, revisa que tu saldo ya esté acreditado.'
        ]
    },
    {
        id: 'cierre-sesion-partida',
        categoria: 'partidas',
        pregunta: 'Se me cerró la página o me desconecté en plena partida',
        respuesta: [
            'No pierdes la sala. Vuelve a entrar con tu cuenta y la plataforma te devuelve a la partida donde ibas, con el chat y el estado en que estaba.',
            'Ojo: abrir tu cuenta en otro dispositivo o pestaña cierra la sesión anterior.'
        ]
    },

    // ───────────────────────── Sorteos y tickets ─────────────────────────
    {
        id: 'como-funcionan-sorteos',
        categoria: 'sorteos',
        pregunta: '¿Cómo funcionan los sorteos?',
        respuesta: [
            'Los sorteos son premios (gemas, Pass Royale, evoluciones, emotes, cartas, comodines u ofertas especiales) que publica el administrador. Cada uno necesita una cantidad de tickets para completarse y tiene un tiempo límite.',
            'Tú pones tus tickets en los sorteos que quieras con los botones + y −. Cuando el sorteo reúne todos los tickets necesarios, se elige un ganador entre los participantes (mientras más tickets pongas, más opciones tienes) y se anuncia en la tarjeta del sorteo. El administrador te contacta por WhatsApp.'
        ],
        pasos: [
            'Entra a Clash Royale → Sorteos.',
            'Mira tus tickets disponibles arriba.',
            'En el sorteo que te guste, pulsa + para poner un ticket (− para quitarlo).',
            'Espera a que se complete: el ganador aparece en la tarjeta con 🏆.'
        ],
        enlace: { texto: 'Ir a Sorteos', to: '/sorteos' },
        video: { titulo: 'Cómo participar en un sorteo', youtube: '', archivo: '' }
    },
    {
        id: 'conseguir-tickets',
        categoria: 'sorteos',
        pregunta: '¿Cómo consigo tickets?',
        respuesta: [
            'Los tickets se ganan jugando: cada vez que termina una partida, tú y tu rival acumulan una parte de la comisión de sorteos, ganes o pierdas. Cada $1.000 acumulados se convierten en 1 ticket y te llega un aviso cuando ganas tickets.',
            'En Sorteos ves la barra «Mis Tickets» con cuánto llevas acumulado hacia el siguiente ticket.'
        ]
    },
    {
        id: 'pozo',
        categoria: 'sorteos',
        pregunta: '¿Qué es el «Pozo Acumulado»?',
        respuesta: [
            'Es el total que la plataforma ha ido reuniendo para los sorteos con una parte de la comisión de las partidas. De ahí salen los premios que se sortean.'
        ]
    },
    {
        id: 'retirar-tickets',
        categoria: 'sorteos',
        pregunta: '¿Puedo quitar los tickets que puse en un sorteo?',
        respuesta: [
            'Sí, mientras el sorteo siga activo: pulsa − y el ticket vuelve a tu cuenta. Cuando el sorteo ya se completó, no se pueden mover.',
            'Si el administrador elimina un sorteo, los tickets participantes se devuelven a sus dueños.'
        ]
    },
    {
        id: 'encuesta-sorteos',
        categoria: 'sorteos',
        pregunta: '¿Para qué sirve la encuesta «¿Qué sorteamos luego?»',
        respuesta: [
            'Es para que decidas qué tipo de premio quieres ver en los próximos sorteos. Tienes un voto: pulsa una categoría para votarla y, si pulsas otra, cambias tu voto. El administrador usa los resultados para escoger los próximos premios.'
        ]
    },
    {
        id: 'ganador-sorteo',
        categoria: 'sorteos',
        pregunta: 'Gané un sorteo, ¿cómo recibo el premio?',
        respuesta: [
            'El administrador te contacta al número de WhatsApp que pusiste al registrarte, por eso es importante que ese número sea correcto y que lo tengas activo.',
            'El sorteo completado queda a la vista un rato mostrando quién fue el ganador.'
        ]
    },

    // ───────────────────────── Rankings y premios ─────────────────────────
    {
        id: 'rankings',
        categoria: 'rankings',
        pregunta: '¿Cómo funcionan los rankings?',
        respuesta: [
            'En Rankings ves a los mejores jugadores por periodo con las pestañas Día, Semana, Mes, Año y Global (ordenados por victorias). Hay además dos rankings de dinero: «Monto en torneos» (cuánto dinero has movido jugando) y «Ganado» (cuánto has ganado en total).',
            'Tu nombre sale resaltado con una ⭐ cuando apareces en la lista. Arriba de la lista se muestran los premios de cada posición cuando el periodo los tiene.'
        ],
        enlace: { texto: 'Ir a Rankings', to: '/leaderboard' },
        video: { titulo: 'Cómo leer los rankings y los premios', youtube: '', archivo: '' }
    },
    {
        id: 'ranking-vacio',
        categoria: 'rankings',
        pregunta: 'El ranking dice que nadie ha ganado partidas, ¿es un error?',
        respuesta: [
            'No. Significa que en ese periodo todavía no hay victorias registradas (por ejemplo, al empezar el día). Se llena a medida que se juegan partidas.'
        ]
    },

    // ───────────────────────── Chats y asistente ─────────────────────────
    {
        id: 'canales-chat',
        categoria: 'chats',
        pregunta: '¿Cuáles son los canales de chat y para qué sirve cada uno?',
        respuesta: [
            'En Chat / Anuncios hay cinco pestañas:'
        ],
        pasos: [
            '💬 General: conversación libre entre todos los jugadores.',
            '📢 Anuncios: avisos oficiales. Solo los administradores pueden escribir; tú solo los lees.',
            '⚔️ Clash: para hablar de partidas, buscar rivales y compartir cosas de Clash Royale.',
            '📜 Registro: solo lectura. Muestra los eventos y partidas del sistema.',
            '🧾 Pagos: solo lectura. El administrador publica aquí los comprobantes de los retiros que ya pagó.'
        ],
        enlace: { texto: 'Ir al Chat', to: '/chat' }
    },
    {
        id: 'comprobante-retiro',
        categoria: 'dinero',
        pregunta: '¿Dónde veo el comprobante de mi retiro?',
        respuesta: [
            'Cuando el administrador te paga un retiro, publica la imagen del comprobante en el canal «Pagos» del chat y te llega un aviso. Ahí lo puedes ver, abrir en grande y comprobar el monto.',
            'El canal es de solo lectura y lo ven todos los jugadores, por eso el administrador tapa los datos personales antes de publicar. Solo se publican comprobantes de retiros; las recargas no aparecen ahí.'
        ],
        enlace: { texto: 'Ir al Chat', to: '/chat' }
    },
    {
        id: 'chat-sala',
        categoria: 'chats',
        pregunta: '¿El chat de la sala privada es el mismo que el general?',
        respuesta: [
            'No. El mini chat de la sala privada es solo entre tú y tu rival y existe únicamente mientras la partida está abierta. Úsalo para acordar el modo y el monto.'
        ]
    },
    {
        id: 'asistente',
        categoria: 'chats',
        pregunta: '¿Qué es el Asistente y qué le puedo preguntar?',
        respuesta: [
            'Es un asistente automático que responde dudas sobre la plataforma: recargas, retiros, partidas, sorteos, etc. Si no sabe algo, le avisa al administrador y la respuesta de él te llega en el mismo chat.',
            'También puedes escoger cómo te habla: tono por defecto, formal, informal o adaptativo (responde según cómo le escribas).'
        ],
        enlace: { texto: 'Abrir el Asistente', to: '/chatbot' }
    },
    {
        id: 'reglas-chat',
        categoria: 'chats',
        pregunta: '¿Qué puedo y qué no puedo escribir en los chats?',
        respuesta: [
            'Sé respetuoso con los demás jugadores. Los enlaces que escribas se pueden abrir con un clic. El contenido que no es texto (como imágenes o videos) solo lo suben los administradores en Anuncios; si algo no es un archivo permitido verás «[contenido no permitido]».',
            'Si alguien te molesta o ves algo raro, cuéntaselo al Asistente para que le llegue al administrador.'
        ]
    },
    {
        id: 'chat-de-voz',
        categoria: 'chats',
        pregunta: '¿Puedo hablar por voz con mi rival? ¿Necesito una cuenta de Discord?',
        respuesta: [
            'Sí, el chat de voz es opcional y funciona con Discord. Para usarlo necesitas una cuenta de Discord (es gratis); si no tienes o no quieres usarla, puedes jugar igual sin voz.',
            'Vincula tu cuenta una sola vez desde la pantalla de partidas con el botón «Vincular Discord»: Discord te pide autorizar y te devuelve a la plataforma. Cuando empieza tu partida aparece el botón «Abrir chat de voz», que te lleva a un canal privado solo para ti y tu rival. El canal se borra solo cuando la partida termina.',
            'Tu rival no ve tu usuario de Discord, y una misma cuenta de Discord solo puede estar vinculada a un usuario de la plataforma. Puedes desvincularla cuando quieras.'
        ]
    },

    // ───────────────────────── Cuenta y seguridad ─────────────────────────
    {
        id: 'recuperar-contrasena',
        categoria: 'cuenta',
        pregunta: '¿Cómo recupero mi contraseña?',
        respuesta: [
            'En la pantalla de entrada elige «¿Olvidaste tu contraseña?» y escribe tu correo. Si está asociado a una cuenta, recibirás un enlace de Brevo; revisa también la carpeta de correo no deseado y úsalo antes de que venza.',
            'El enlace funciona una sola vez. Al guardar una contraseña nueva se cerrarán las demás sesiones de tu cuenta. Nunca compartas el enlace con nadie.',
            'La contraseña debe tener entre 6 y 128 caracteres y no superar 72 bytes UTF-8. Los acentos y emojis pueden ocupar más de un byte.'
        ],
        enlace: { texto: 'Ir a iniciar sesión', to: '/auth' }
    },
    {
        id: 'sesion-cerrada',
        categoria: 'cuenta',
        pregunta: 'La página me sacó de mi cuenta, ¿por qué?',
        respuesta: [
            'Hay dos causas comunes. La primera, que abriste tu cuenta en otro dispositivo o pestaña: solo una sesión puede estar activa a la vez, así que la anterior se cierra. La segunda, que tu sesión venció o el servidor ya no la reconoce; en ese caso solo debes iniciar sesión de nuevo.'
        ]
    },
    {
        id: 'demasiadas-solicitudes',
        categoria: 'cuenta',
        pregunta: 'Me aparece «Demasiadas solicitudes», ¿qué significa?',
        respuesta: [
            'Para proteger la plataforma hay un límite de peticiones por minuto. Si lo superas (por ejemplo, haciendo clic muchas veces seguidas), el servidor te pide esperar. Basta con esperar unos segundos y volver a intentar con calma.'
        ]
    },
    {
        id: 'seguridad-saldo',
        categoria: 'cuenta',
        pregunta: '¿Qué tan seguro está mi dinero?',
        respuesta: [
            'Todos los movimientos de dinero (recargas, retiros, montos de partidas, premios y comisiones) los procesa el servidor y quedan registrados en un historial que no se puede modificar. El cliente nunca decide cuánto dinero te corresponde: el cálculo siempre se hace en el servidor.',
            'Para cuidar tu cuenta, usa una contraseña única y no la compartas con nadie. Nadie del equipo te la va a pedir.'
        ]
    },
    {
        id: 'terminos',
        categoria: 'cuenta',
        pregunta: '¿Dónde leo los Términos de Servicio y la Política de Privacidad?',
        respuesta: [
            'Están disponibles en cualquier momento, incluso sin iniciar sesión.'
        ],
        enlace: { texto: 'Leer Términos de Servicio', to: '/terminos' }
    },
    {
        id: 'datos-privados',
        categoria: 'cuenta',
        pregunta: '¿Mi rival puede ver mis datos personales?',
        respuesta: [
            'No. A tu rival solo se le muestra tu nombre de usuario. Tu correo, tu teléfono y tu contraseña no se comparten con otros jugadores.'
        ]
    },
    {
        id: 'problema-no-listado',
        categoria: 'cuenta',
        pregunta: 'Mi problema no está en esta lista, ¿a quién le escribo?',
        respuesta: [
            'Pregúntale al Asistente: si no sabe la respuesta, avisa al administrador y la respuesta te llega en el mismo chat. Mientras más detalles des (qué estabas haciendo, el monto, la hora y el mensaje de error que viste), más rápido se resuelve.'
        ],
        enlace: { texto: 'Hablar con el Asistente', to: '/chatbot' }
    }
];
