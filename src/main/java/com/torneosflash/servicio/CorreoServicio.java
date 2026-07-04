package com.torneosflash.servicio;

import jakarta.mail.*;
import jakarta.mail.internet.*;
import java.util.Properties;

/**
 * Servicio de envío de correos electrónicos.
 * Equivalente al nodemailer transporter de index.js.
 */
public class CorreoServicio {

    private Session session;
    private String fromEmail;
    private boolean habilitado;

    public CorreoServicio(String gmailUser, String gmailPass) {
        this.fromEmail = gmailUser;
        this.habilitado = gmailUser != null && !gmailUser.isEmpty() &&
                          gmailPass != null && !gmailPass.isEmpty();

        if (habilitado) {
            Properties props = new Properties();
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.host", "smtp.gmail.com");
            props.put("mail.smtp.port", "587");

            this.session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(gmailUser, gmailPass);
                }
            });
            System.out.println("✅ Servicio de correo configurado");
        } else {
            System.out.println("⚠️ Correo no configurado (faltan GMAIL_USER/GMAIL_PASS)");
        }
    }

    /**
     * Envía un correo electrónico.
     */
    public void enviar(String to, String subject, String body) {
        if (!habilitado) return;

        new Thread(() -> {
            try {
                MimeMessage message = new MimeMessage(session);
                message.setFrom(new InternetAddress(fromEmail, "Torneos Flash Bot"));
                message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
                message.setSubject(subject);
                message.setText(body);
                Transport.send(message);
                System.out.println("📧 Correo enviado a " + to);
            } catch (Exception e) {
                System.err.println("Error enviando correo: " + e.getMessage());
            }
        }).start();
    }

    /**
     * Envía notificación al admin (al email configurado).
     */
    public void notificarAdmin(String asunto, String detalle) {
        enviar(fromEmail, "🔔 " + asunto, detalle);
    }

    public boolean isHabilitado() { return habilitado; }
}
