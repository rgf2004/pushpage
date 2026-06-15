package me.projects.pushpage.service;

import me.projects.pushpage.model.CreateUserRequest;
import me.projects.pushpage.model.CreateUserResponse;
import me.projects.pushpage.model.User;
import me.projects.pushpage.model.UserSummary;
import me.projects.pushpage.repository.UserRepository;
import me.projects.pushpage.util.ApiKeyHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class UserService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.count() > 0) {
            return;
        }
        String rawKey = generateRawKey();
        String id = generateId();
        userRepository.save(new User(id, "admin", null, ApiKeyHasher.hash(rawKey), Instant.now(), true, true));
        log.warn("==============================================================");
        log.warn("No admin user found — bootstrap admin created.");
        log.warn("API Key: {}", rawKey);
        log.warn("Copy this key now. It will NOT appear again after restart.");
        log.warn("==============================================================");
    }

    public CreateUserResponse createUser(CreateUserRequest request) {
        if (request.username() == null || request.username().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username is required");
        }
        if (userRepository.existsByUsername(request.username())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists: " + request.username());
        }
        String id = generateId();
        String rawKey = generateRawKey();
        Instant now = Instant.now();
        userRepository.save(new User(id, request.username(), request.email(), ApiKeyHasher.hash(rawKey), now, true, request.admin()));
        return new CreateUserResponse(id, request.username(), request.email(), rawKey, now, request.admin());
    }

    public List<UserSummary> listUsers() {
        return userRepository.findAll();
    }

    public void deactivateUser(String id) {
        if (!userRepository.deactivateById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + id);
        }
    }

    private String generateId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private String generateRawKey() {
        return "pp_" + UUID.randomUUID().toString().replace("-", "");
    }
}
