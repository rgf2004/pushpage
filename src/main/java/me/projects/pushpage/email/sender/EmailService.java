package me.projects.pushpage.email.sender;

/**
 * Provider-agnostic transactional email sender. Implementations should not assume
 * anything about the underlying delivery mechanism beyond "send this HTML to this address".
 */
public interface EmailService {

    void sendVerificationEmail(String toEmail, String verificationLink);

    /** @param expiresInMinutes shown to the user so they know how long the link stays valid */
    void sendPasswordResetEmail(String toEmail, String resetLink, long expiresInMinutes);
}
