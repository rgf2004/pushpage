package me.projects.pushpage.email.sender;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import me.projects.pushpage.email.ConditionalOnEmailEnabled;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * SMTP-based {@link EmailService}. SMTP is supported by every major transactional
 * email provider (Mailtrap, Resend, SES, Sendgrid, Postmark, ...), so swapping
 * providers is a matter of changing the {@code EMAIL_SMTP_*} env vars — no code change.
 */
@Service
@ConditionalOnEmailEnabled
public class SmtpEmailService implements EmailService {

    /**
     * Mailtrap-specific: groups sends under this name in Mailtrap's category stats
     * (open/click/bounce rate, etc. — see their "categories" docs). Sent as a plain
     * X- header, so it's a no-op with any other SMTP provider, not a provider lock-in.
     */
    private static final String VERIFICATION_EMAIL_CATEGORY = "email-verification";
    private static final String PASSWORD_RESET_EMAIL_CATEGORY = "password-reset";
    private static final String VERIFICATION_EMAIL_TEMPLATE_PATH = "email/verification-email.html";
    private static final String PASSWORD_RESET_EMAIL_TEMPLATE_PATH = "email/password-reset-email.html";

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final String verificationEmailTemplate;
    private final String passwordResetEmailTemplate;

    public SmtpEmailService(JavaMailSender mailSender, @Value("${app.email.from}") String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.verificationEmailTemplate = loadTemplate(VERIFICATION_EMAIL_TEMPLATE_PATH);
        this.passwordResetEmailTemplate = loadTemplate(PASSWORD_RESET_EMAIL_TEMPLATE_PATH);
    }

    private static String loadTemplate(String classpathLocation) {
        try {
            return StreamUtils.copyToString(
                    new ClassPathResource(classpathLocation).getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load email template: " + classpathLocation, e);
        }
    }

    @Override
    public void sendVerificationEmail(String toEmail, String verificationLink) {
        send(toEmail, "Confirm your pushpage email address", VERIFICATION_EMAIL_CATEGORY,
                verificationEmailTemplate.replace("{{verificationLink}}", verificationLink));
    }

    @Override
    public void sendPasswordResetEmail(String toEmail, String resetLink, long expiresInMinutes) {
        send(toEmail, "Reset your pushpage password", PASSWORD_RESET_EMAIL_CATEGORY,
                passwordResetEmailTemplate
                        .replace("{{resetLink}}", resetLink)
                        .replace("{{expiresIn}}", describeDuration(expiresInMinutes)));
    }

    private void send(String toEmail, String subject, String category, String html) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(html, true);
            message.setHeader("X-MT-Category", category);
            mailSender.send(message);
        } catch (MessagingException e) {
            throw new IllegalStateException("Failed to send email to " + toEmail, e);
        }
    }

    private static String describeDuration(long minutes) {
        if (minutes % 60 == 0) {
            long hours = minutes / 60;
            return hours + (hours == 1 ? " hour" : " hours");
        }
        return minutes + " minutes";
    }
}
