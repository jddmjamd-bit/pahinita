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

    public CorreoServicio(String smtpUser, String smtpPass) {
        this.fromEmail = smtpUser;
        this.habilitado = smtpUser != null && !smtpUser.isEmpty() &&
                          smtpPass != null && !smtpPass.isEmpty();

        if (habilitado) {
            Properties props = new Properties();
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.host", "smtp-relay.brevo.com");
            props.put("mail.smtp.port", "587");

            this.session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(smtpUser, smtpPass);
                }
            });
            System.out.println("✅ Servicio de correo (Brevo SMTP) configurado");
        } else {
            System.out.println("⚠️ Correo no configurado (faltan SMTP_USER/SMTP_PASS)");
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
