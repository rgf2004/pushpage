package me.projects.pushpage.cloud.email;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmtpEmailServiceTest {

    @Mock
    private JavaMailSender mailSender;

    @Test
    void sendVerificationEmail_setsMailtrapCategoryHeaderAndBasicFields() throws Exception {
        MimeMessage realMessage = new MimeMessage(Session.getDefaultInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(realMessage);

        SmtpEmailService service = new SmtpEmailService(mailSender, "no-reply@pushpage.link");
        service.sendVerificationEmail("alice@example.com", "https://pushpage.link/api/auth/verify-email?token=abc");

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage sent = captor.getValue();

        assertThat(sent.getHeader("X-MT-Category", null)).isEqualTo("email-verification");
        assertThat(sent.getSubject()).isEqualTo("Confirm your pushpage email address");
        assertThat(sent.getAllRecipients()).extracting(Object::toString).containsExactly("alice@example.com");
    }
}
