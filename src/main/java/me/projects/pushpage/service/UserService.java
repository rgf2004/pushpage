package me.projects.pushpage.service;

import jakarta.annotation.PostConstruct;
import me.projects.pushpage.model.CreateUserRequest;
import me.projects.pushpage.model.CreateUserResponse;
import me.projects.pushpage.model.User;
import me.projects.pushpage.model.UserSummary;
import me.projects.pushpage.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    @Value("${app.admin.bootstrap-api-key:}")
    private String bootstrapApiKey;

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @PostConstruct
    public void bootstrap() {
        if (!bootstrapApiKey.isBlank() && userRepository.count() == 0) {
            String id = generateId();
            User admin = new User(id, "admin", bootstrapApiKey, Instant.now(), true, true);
            userRepository.save(admin);
            log.info("Bootstrap admin user created (id={})", id);
        }
    }

    public CreateUserResponse createUser(CreateUserRequest request) {
        if (request.username() == null || request.username().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username is required");
        }
        if (userRepository.existsByUsername(request.username())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists: " + request.username());
        }
        String id = generateId();
        String apiKey = "pp_" + UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        userRepository.save(new User(id, request.username(), apiKey, now, true, request.admin()));
        return new CreateUserResponse(id, request.username(), apiKey, now, request.admin());
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
}
