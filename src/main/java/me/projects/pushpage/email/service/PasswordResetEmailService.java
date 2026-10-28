package me.projects.pushpage.email.service;

import io.github.bucket4j.ConsumptionProbe;
import me.projects.pushpage.email.ConditionalOnEmailEnabled;
import me.projects.pushpage.email.sender.EmailService;
import me.projects.pushpage.repository.UserRepository;
import me.projects.pushpage.service.PasswordResetService;
import me.projects.pushpage.service.RateLimitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Self-service "forgot password": emails a reset link when the address belongs to an active
 * account. The caller always gets the same answer whether or not the account exists, and the
 * SMTP send runs off the request thread so response time doesn't reveal it either.
 */
@Service
@ConditionalOnEmailEnabled
public class PasswordResetEmailService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetEmailService.class);
    private static final String ACCOUNT_RATE_LIMIT_KEY_PREFIX = "pw-reset:";
    private static final String IP_RATE_LIMIT_KEY_PREFIX = "pw-reset-ip:";

    private final UserRepository userRepository;
    private final PasswordResetService passwordResetService;
    private final EmailService emailService;
    private final RateLimitService rateLimitService;
    private final Executor mailExecutor;
    private final int accountRateLimitPerMinute;
    private final int ipRateLimitPerMinute;
    private final long tokenTtlMinutes;

    @Autowired
    public PasswordResetEmailService(UserRepository userRepository,
                                     PasswordResetService passwordResetService,
                                     EmailService emailService,
                                     RateLimitService rateLimitService,
                                     @Value("${app.email.password-reset-rate-limit-per-minute:1}") int accountRateLimitPerMinute,
                                     @Value("${app.email.password-reset-ip-rate-limit-per-minute:5}") int ipRateLimitPerMinute,
                                     @Value("${app.password-reset.token-ttl-minutes:60}") long tokenTtlMinutes) {
        this(userRepository, passwordResetService, emailService, rateLimitService,
                Executors.newVirtualThreadPerTaskExecutor(),
                accountRateLimitPerMinute, ipRateLimitPerMinute, tokenTtlMinutes);
    }

    PasswordResetEmailService(UserRepository userRepository,
                              PasswordResetService passwordResetService,
                              EmailService emailService,
                              RateLimitService rateLimitService,
                              Executor mailExecutor,
                              int accountRateLimitPerMinute,
                              int ipRateLimitPerMinute,
                              long tokenTtlMinutes) {
        this.userRepository = userRepository;
        this.passwordResetService = passwordResetService;
        this.emailService = emailService;
        this.rateLimitService = rateLimitService;
        this.mailExecutor = mailExecutor;
        this.accountRateLimitPerMinute = accountRateLimitPerMinute;
        this.ipRateLimitPerMinute = ipRateLimitPerMinute;
        this.tokenTtlMinutes = tokenTtlMinutes;
    }

    /**
     * Rate-limited per IP (the endpoint is unauthenticated, so this stops mass mailbox spam)
     * and per account (stops flooding one user's inbox).
     */
    public ResendOutcome requestReset(String email, String clientIp) {
        ConsumptionProbe ipProbe = rateLimitService.tryConsume(IP_RATE_LIMIT_KEY_PREFIX + clientIp, ipRateLimitPerMinute);
        if (!ipProbe.isConsumed()) {
            long retryAfterSeconds = (ipProbe.getNanosToWaitForRefill() + 999_999_999L) / 1_000_000_000L;
            log.warn("Forgot-password rate limit exceeded for IP {}", clientIp);
            return ResendOutcome.rateLimited(retryAfterSeconds);
        }

        if (email != null && !email.isBlank()) {
            userRepository.findByEmail(email.trim())
                    .filter(user -> user.active())
                    .ifPresent(user -> {
                        if (!rateLimitService.tryConsume(ACCOUNT_RATE_LIMIT_KEY_PREFIX + user.id(),
                                accountRateLimitPerMinute).isConsumed()) {
                            log.warn("Password reset email rate limit exceeded for user {}", user.id());
                            return;
                        }
                        String link = passwordResetService.issueToken(user, true).link();
                        mailExecutor.execute(() -> {
                            try {
                                emailService.sendPasswordResetEmail(user.email(), link, tokenTtlMinutes);
                            } catch (Exception e) {
                                log.error("Failed to send password reset email to {}", user.email(), e);
                            }
                        });
                    });
        }
        return ResendOutcome.accepted();
    }
}
