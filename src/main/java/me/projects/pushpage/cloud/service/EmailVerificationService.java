package me.projects.pushpage.cloud.service;

import io.github.bucket4j.ConsumptionProbe;
import me.projects.pushpage.cloud.email.EmailService;
import me.projects.pushpage.cloud.repository.EmailVerificationRepository;
import me.projects.pushpage.model.User;
import me.projects.pushpage.repository.UserRepository;
import me.projects.pushpage.service.RateLimitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@Profile("cloud")
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);
    private static final String ACCOUNT_RATE_LIMIT_KEY_PREFIX = "email-verify:";
    private static final String RESEND_IP_RATE_LIMIT_KEY_PREFIX = "resend-verify-ip:";

    private final EmailVerificationRepository verificationRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final RateLimitService rateLimitService;
    private final String appServerUrl;
    private final int verificationRateLimitPerMinute;
    private final int resendIpRateLimitPerMinute;
    private final Duration tokenTtl;

    public EmailVerificationService(EmailVerificationRepository verificationRepository,
                                     UserRepository userRepository,
                                     EmailService emailService,
                                     RateLimitService rateLimitService,
                                     @Value("${app.server-url}") String appServerUrl,
                                     @Value("${app.email.verification-rate-limit-per-minute:1}") int verificationRateLimitPerMinute,
                                     @Value("${app.email.resend-ip-rate-limit-per-minute:5}") int resendIpRateLimitPerMinute,
                                     @Value("${app.email.verification-token-ttl-hours:24}") long tokenTtlHours) {
        this.verificationRepository = verificationRepository;
        this.userRepository = userRepository;
        this.emailService = emailService;
        this.rateLimitService = rateLimitService;
        this.appServerUrl = appServerUrl;
        this.verificationRateLimitPerMinute = verificationRateLimitPerMinute;
        this.resendIpRateLimitPerMinute = resendIpRateLimitPerMinute;
        this.tokenTtl = Duration.ofHours(tokenTtlHours);
    }

    /**
     * Sends (or re-sends) the verification email. Rate-limited per account so that
     * neither repeated sign-up attempts nor resend-verification spam can be used to
     * flood a mailbox or run up the SMTP provider's bill — both callers funnel through
     * here, so this is the single choke point to guard.
     */
    public void sendVerificationEmail(User user) {
        ConsumptionProbe probe = rateLimitService.tryConsume(
                ACCOUNT_RATE_LIMIT_KEY_PREFIX + user.id(), verificationRateLimitPerMinute);
        if (!probe.isConsumed()) {
            log.warn("Verification email rate limit exceeded for user {}", user.id());
            return;
        }

        String token = UUID.randomUUID().toString();
        verificationRepository.setVerificationToken(user.id(), token, Instant.now().plus(tokenTtl));
        String link = appServerUrl + "/api/auth/verify-email?token=" + token;
        try {
            emailService.sendVerificationEmail(user.email(), link);
        } catch (Exception e) {
            log.error("Failed to send verification email to {}", user.email(), e);
        }
    }

    public boolean isVerified(String userId) {
        return verificationRepository.isVerified(userId);
    }

    public VerifyResult verifyToken(String token) {
        Optional<String> userId = verificationRepository.findUserIdByValidToken(token);
        if (userId.isEmpty()) {
            return VerifyResult.INVALID_OR_EXPIRED;
        }
        verificationRepository.markVerified(userId.get());
        return VerifyResult.SUCCESS;
    }

    /**
     * Rate-limited per IP: the endpoint is unauthenticated, so without this an attacker
     * could mass-spam many different mailboxes at once (each capped individually by
     * sendVerificationEmail's per-account cooldown, but not as a group).
     */
    public ResendOutcome resendVerification(String email, String clientIp) {
        ConsumptionProbe probe = rateLimitService.tryConsume(
                RESEND_IP_RATE_LIMIT_KEY_PREFIX + clientIp, resendIpRateLimitPerMinute);
        if (!probe.isConsumed()) {
            long retryAfterSeconds = (probe.getNanosToWaitForRefill() + 999_999_999L) / 1_000_000_000L;
            log.warn("Resend-verification rate limit exceeded for IP {}", clientIp);
            return ResendOutcome.rateLimited(retryAfterSeconds);
        }

        if (email != null && !email.isBlank()) {
            userRepository.findByEmail(email.trim()).ifPresent(user -> {
                if (!isVerified(user.id())) {
                    sendVerificationEmail(user);
                }
            });
        }
        return ResendOutcome.accepted();
    }

    public void markVerifiedManually(String userId) {
        verificationRepository.markVerified(userId);
    }
}
