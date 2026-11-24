package me.projects.pushpage.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import me.projects.pushpage.model.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;

@Service
public class JwtService {

    @Value("${app.jwt.secret}")
    private String secret;

    @Value("${app.jwt.expiration-hours:24}")
    private long expirationHours;

    private static final String PASSWORD_VERSION_CLAIM = "pwd_v";
    private static final String SCOPE_CLAIM = "scope";

    private SecretKey signingKey;

    @PostConstruct
    public void init() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("app.jwt.secret (JWT_SECRET) must be set");
        }
        signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.id())
                .claim("email", user.email())
                .claim("admin", user.admin())
                .claim(PASSWORD_VERSION_CLAIM, JwtClaims.passwordVersionOf(user))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expirationHours, ChronoUnit.HOURS)))
                .signWith(signingKey)
                .compact();
    }

    /** A short-lived access token for an OAuth client, restricted to the granted scopes. */
    public String generateAccessToken(User user, java.util.Set<String> scopes, String clientId, long minutes) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.id())
                .claim("email", user.email())
                .claim(PASSWORD_VERSION_CLAIM, JwtClaims.passwordVersionOf(user))
                .claim(SCOPE_CLAIM, String.join(" ", scopes))
                .claim("client_id", clientId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(minutes, ChronoUnit.MINUTES)))
                .signWith(signingKey)
                .compact();
    }

    public Optional<String> validateToken(String token) {
        return parseToken(token).map(JwtClaims::userId);
    }

    /** Verifies the signature and expiry, and returns who the token was issued to and under which password version. */
    public Optional<JwtClaims> parseToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            // Tokens issued before this claim existed have none, which is version 0 (never changed).
            Object version = claims.get(PASSWORD_VERSION_CLAIM);
            long passwordVersion = version instanceof Number n ? n.longValue() : 0L;
            Object scope = claims.get(SCOPE_CLAIM);
            java.util.Set<String> scopes = scope instanceof String s
                    ? new java.util.LinkedHashSet<>(java.util.Arrays.asList(s.trim().split("\\s+")))
                    : null;
            return Optional.of(new JwtClaims(claims.getSubject(), passwordVersion, scopes));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
