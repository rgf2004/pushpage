package me.projects.pushpage.cloud.email;

/**
 * Provider-agnostic transactional email sender. Implementations should not assume
 * anything about the underlying delivery mechanism beyond "send this HTML to this address".
 */
public interface EmailService {

    void sendVerificationEmail(String toEmail, String verificationLink);
}
