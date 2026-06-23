package me.projects.pushpage.service;

import me.projects.pushpage.model.LoginRequest;
import me.projects.pushpage.model.LoginResponse;
import me.projects.pushpage.model.RotateTokenResponse;
import me.projects.pushpage.model.SignUpRequest;
import me.projects.pushpage.model.User;
import me.projects.pushpage.model.UserSummary;
import me.projects.pushpage.repository.UserRepository;
import me.projects.pushpage.util.ApiKeyHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class UserService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();
    private static final String ADMIN_EMAIL = "admin@pushpage.link";
    private static final String ALPHANUMERIC = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final JwtService jwtService;

    public UserService(UserRepository userRepository, JwtService jwtService) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.count() > 0) {
            return;
        }
        String rawKey = generateRawKey();
        String rawPassword = generateRawPassword();
        String id = generateId();
        userRepository.save(new User(id, ADMIN_EMAIL, ApiKeyHasher.hash(rawKey),
                BCRYPT.encode(rawPassword), Instant.now(), true, true));
        log.warn("==============================================================");
        log.warn("No users found — bootstrap admin created.");
        log.warn("Email    : {}", ADMIN_EMAIL);
        log.warn("Password : {}", rawPassword);
        log.warn("API Key  : {}", rawKey);
        log.warn("Copy these credentials now. They will NOT appear again.");
        log.warn("==============================================================");
    }

    public void signUp(SignUpRequest request) {
        if (request.email() == null || !EMAIL_PATTERN.matcher(request.email().trim()).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valid email is required");
        }
        if (request.password() == null || request.password().length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at least 8 characters");
        }
        if (userRepository.findByEmail(request.email().trim()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
        }
        String id = generateId();
        String rawKey = generateRawKey();
        userRepository.save(new User(id, request.email().trim(),
                ApiKeyHasher.hash(rawKey), BCRYPT.encode(request.password()),
                Instant.now(), true, false));
    }

    public LoginResponse login(LoginRequest request) {
        if (request.email() == null || request.email().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email is required");
        }
        if (request.password() == null || request.password().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password is required");
        }
        User user = userRepository.findByEmail(request.email().trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));
        if (!user.active()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account is deactivated");
        }
        if (user.passwordHash() == null || !BCRYPT.matches(request.password(), user.passwordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
        return new LoginResponse(jwtService.generateToken(user));
    }

    public RotateTokenResponse rotateApiToken(User currentUser) {
        String rawKey = generateRawKey();
        userRepository.updateApiKeyHash(currentUser.id(), ApiKeyHasher.hash(rawKey));
        return new RotateTokenResponse(rawKey);
    }

    public List<UserSummary> listUsers() {
        return userRepository.findAll();
    }

    public void deactivateUser(String id) {
        if (!userRepository.deactivateById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + id);
        }
    }

    public void promoteUser(String id) {
        if (!userRepository.promoteById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + id);
        }
    }

    private String generateId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private String generateRawKey() {
        return "pp_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String generateRawPassword() {
        StringBuilder sb = new StringBuilder(20);
        for (int i = 0; i < 20; i++) {
            sb.append(ALPHANUMERIC.charAt(SECURE_RANDOM.nextInt(ALPHANUMERIC.length())));
        }
        return sb.toString();
    }
}
