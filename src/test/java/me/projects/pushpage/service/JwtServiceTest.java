package me.projects.pushpage.service;

import me.projects.pushpage.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private JwtService jwtService;

    private static final User USER = new User(
            "abc12345", "alice@example.com", "pp_key", null, Instant.now(), true, false
    );
    private static final User ADMIN = new User(
            "def67890", "admin@pushpage.link", "pp_adminkey", null, Instant.now(), true, true
    );

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secret", "test-jwt-secret-for-all-tests-minimum-32-chars-xxxxxxxx");
        ReflectionTestUtils.setField(jwtService, "expirationHours", 24L);
        jwtService.init();
    }

    @Test
    void generateToken_returnsNonBlankToken() {
        String token = jwtService.generateToken(USER);
        assertThat(token).isNotBlank();
    }

    @Test
    void validateToken_withValidToken_returnsUserId() {
        String token = jwtService.generateToken(USER);
        Optional<String> userId = jwtService.validateToken(token);
        assertThat(userId).contains(USER.id());
    }

    @Test
    void validateToken_adminToken_returnsAdminId() {
        String token = jwtService.generateToken(ADMIN);
        assertThat(jwtService.validateToken(token)).contains(ADMIN.id());
    }

    @Test
    void validateToken_withTamperedToken_returnsEmpty() {
        String token = jwtService.generateToken(USER);
        String tampered = token.substring(0, token.length() - 4) + "xxxx";
        assertThat(jwtService.validateToken(tampered)).isEmpty();
    }

    @Test
    void validateToken_withGarbage_returnsEmpty() {
        assertThat(jwtService.validateToken("not.a.jwt")).isEmpty();
    }

    @Test
    void validateToken_withExpiredToken_returnsEmpty() {
        ReflectionTestUtils.setField(jwtService, "expirationHours", 0L);
        jwtService.init();
        String token = jwtService.generateToken(USER);
        assertThat(jwtService.validateToken(token)).isEmpty();
    }

    @Test
    void validateToken_tokenSignedWithDifferentSecret_returnsEmpty() {
        JwtService other = new JwtService();
        ReflectionTestUtils.setField(other, "secret", "completely-different-secret-value-xxxxxxxxx");
        ReflectionTestUtils.setField(other, "expirationHours", 24L);
        other.init();

        String token = other.generateToken(USER);
        assertThat(jwtService.validateToken(token)).isEmpty();
    }
}
