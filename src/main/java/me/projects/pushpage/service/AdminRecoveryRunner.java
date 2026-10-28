package me.projects.pushpage.service;

import me.projects.pushpage.model.User;
import me.projects.pushpage.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Break-glass for a locked-out admin on an instance without email: when
 * {@code ADMIN_RECOVERY=true}, logs a one-time password reset link for every active admin at
 * startup. Same trust model as the bootstrap credentials: whoever can read the logs can already
 * control the deployment. Unset the flag after use, since every restart issues fresh links.
 */
@Component
@ConditionalOnProperty(name = "app.admin-recovery.enabled", havingValue = "true")
public class AdminRecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminRecoveryRunner.class);

    private final UserRepository userRepository;
    private final PasswordResetService passwordResetService;

    public AdminRecoveryRunner(UserRepository userRepository, PasswordResetService passwordResetService) {
        this.userRepository = userRepository;
        this.passwordResetService = passwordResetService;
    }

    @Override
    public void run(ApplicationArguments args) {
        var admins = userRepository.findAll().stream()
                .filter(u -> u.admin() && u.active())
                .toList();
        log.warn("==============================================================");
        log.warn("ADMIN_RECOVERY is enabled. Remove it once you have regained access.");
        if (admins.isEmpty()) {
            log.warn("No active admin accounts found.");
        }
        for (var summary : admins) {
            User admin = userRepository.findById(summary.id()).orElseThrow();
            PasswordResetService.IssuedToken token = passwordResetService.issueToken(admin, false);
            log.warn("Password reset link for {} (expires {}):", admin.email(), token.expiresAt());
            log.warn("{}", token.link());
        }
        log.warn("==============================================================");
    }
}
