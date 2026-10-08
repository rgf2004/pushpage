package me.projects.pushpage.cloud.email;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import me.projects.pushpage.cloud.config.ConditionalOnEmailVerificationEnabled;
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
@ConditionalOnEmailVerificationEnabled
public class SmtpEmailService implements EmailService {

    /**
     * Mailtrap-specific: groups sends under this name in Mailtrap's category stats
     * (open/click/bounce rate, etc. — see their "categories" docs). Sent as a plain
     * X- header, so it's a no-op with any other SMTP provider, not a provider lock-in.
     */
    private static final String VERIFICATION_EMAIL_CATEGORY = "email-verification";
    private static final String VERIFICATION_EMAIL_TEMPLATE_PATH = "email/verification-email.html";

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final String verificationEmailTemplate;

    public SmtpEmailService(JavaMailSender mailSender, @Value("${app.email.from}") String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.verificationEmailTemplate = loadTemplate(VERIFICATION_EMAIL_TEMPLATE_PATH);
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
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(toEmail);
            helper.setSubject("Confirm your pushpage email address");
            helper.setText(buildHtml(verificationLink), true);
            message.setHeader("X-MT-Category", VERIFICATION_EMAIL_CATEGORY);
            mailSender.send(message);
        } catch (MessagingException e) {
            throw new IllegalStateException("Failed to send verification email to " + toEmail, e);
        }
    }

    private String buildHtml(String verificationLink) {
        return verificationEmailTemplate.replace("{{verificationLink}}", verificationLink);
    }
}
