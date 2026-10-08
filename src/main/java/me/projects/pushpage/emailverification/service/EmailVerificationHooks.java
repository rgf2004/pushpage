package me.projects.pushpage.emailverification.service;

import jakarta.annotation.PostConstruct;
import me.projects.pushpage.model.User;
import me.projects.pushpage.service.UserLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import me.projects.pushpage.emailverification.ConditionalOnEmailVerificationEnabled;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Overrides the public no-op {@link UserLifecycleHooks} bean: sends a confirmation
 * email after sign-up and blocks login until the address is verified.
 */
@Service
@Primary
@ConditionalOnEmailVerificationEnabled
public class EmailVerificationHooks implements UserLifecycleHooks {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationHooks.class);

    private final EmailVerificationService verificationService;

    public EmailVerificationHooks(EmailVerificationService verificationService) {
        this.verificationService = verificationService;
    }

    @PostConstruct
    void logActivation() {
        log.info("UserLifecycleHooks active: {} (sign-up email verification, overrides NoOpUserLifecycleHooks)",
                getClass().getSimpleName());
    }

    @Override
    public void afterSignUp(User user) {
        verificationService.sendVerificationEmail(user);
    }

    @Override
    public void beforeLogin(User user) {
        if (!verificationService.isVerified(user.id())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Please verify your email address before logging in. Check your inbox, or request a new link via POST /api/auth/resend-verification.");
        }
    }
}
