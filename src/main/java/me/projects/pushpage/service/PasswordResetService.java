package me.projects.pushpage.service;

import me.projects.pushpage.model.User;
import me.projects.pushpage.repository.PasswordResetRepository;
import me.projects.pushpage.repository.PasswordResetRepository.RedeemedToken;
import me.projects.pushpage.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Issues and redeems single-use password-reset tokens. Delivery of the token (email, or an
 * admin handing over the link) is up to the caller, so this works with or without EMAIL_ENABLED.
 */
@Service
public class PasswordResetService {

    /** A freshly issued reset token. {@code rawToken} exists only here and is never stored. */
    public record IssuedToken(String rawToken, String link, Instant expiresAt) {}

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final PasswordResetRepository resetRepository;
    private final UserRepository userRepository;
    private final UserLifecycleHooks lifecycleHooks;
    private final String appServerUrl;
    private final Duration tokenTtl;

    public PasswordResetService(PasswordResetRepository resetRepository,
                                UserRepository userRepository,
                                UserLifecycleHooks lifecycleHooks,
                                @Value("${app.server-url}") String appServerUrl,
                                @Value("${app.password-reset.token-ttl-minutes:60}") long tokenTtlMinutes) {
        this.resetRepository = resetRepository;
        this.userRepository = userRepository;
        this.lifecycleHooks = lifecycleHooks;
        this.appServerUrl = appServerUrl;
        this.tokenTtl = Duration.ofMinutes(tokenTtlMinutes);
    }

    /**
     * Issues a token for the user, invalidating any earlier one. {@code emailDelivered} must be
     * true only when the link is sent to the user's own mailbox.
     */
    @Transactional
    public IssuedToken issueToken(User user, boolean emailDelivered) {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(tokenTtl);
        resetRepository.replaceToken(user.id(), hash(rawToken), now, expiresAt, emailDelivered);
        return new IssuedToken(rawToken, appServerUrl + "/dashboard?reset_token=" + rawToken, expiresAt);
    }

    /** Admin-initiated: issues a link the admin passes to the user. */
    @Transactional
    public IssuedToken issueTokenForAdmin(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + userId));
        if (!user.active()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "User is deactivated");
        }
        return issueToken(user, false);
    }

    @Transactional
    public void resetPassword(String token, String newPassword) {
        if (newPassword == null || newPassword.length() < UserService.MIN_PASSWORD_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Password must be at least " + UserService.MIN_PASSWORD_LENGTH + " characters");
        }
        RedeemedToken redeemed = (token == null || token.isBlank())
                ? null
                : resetRepository.redeem(hash(token.trim()), Instant.now()).orElse(null);
        User user = redeemed == null ? null : userRepository.findById(redeemed.userId()).orElse(null);
        if (user == null || !user.active()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired reset token");
        }

        userRepository.updatePassword(user.id(), BCRYPT.encode(newPassword), Instant.now());
        resetRepository.deleteByUserId(user.id());
        if (redeemed.emailDelivered()) {
            lifecycleHooks.afterEmailOwnershipProven(user);
        }
        log.info("Password reset completed for user {}", user.id());
    }

    @Scheduled(cron = "${app.cleanup.schedule:0 0 * * * *}")
    public void purgeExpiredTokens() {
        int purged = resetRepository.deleteExpired(Instant.now());
        if (purged > 0) {
            log.info("Purged {} expired password reset tokens", purged);
        }
    }

    static String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
