package me.projects.pushpage.service;

import me.projects.pushpage.model.ApiKey;
import me.projects.pushpage.model.ApiKeyResponse;
import me.projects.pushpage.model.CreateApiKeyResponse;
import me.projects.pushpage.model.User;
import me.projects.pushpage.repository.ApiKeyRepository;
import me.projects.pushpage.util.ApiKeyHasher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ApiKeyService {

    public static final int MAX_NAME_LENGTH = 60;
    static final int PREFIX_LENGTH = 8;
    /** A key's last_used_at is written at most this often, so authenticating does not write on every request. */
    static final Duration LAST_USED_WRITE_INTERVAL = Duration.ofMinutes(5);

    private final ApiKeyRepository repository;
    private final int maxKeysPerUser;
    private final Map<String, Instant> lastUsedWrites = new ConcurrentHashMap<>();

    public ApiKeyService(ApiKeyRepository repository,
                         @Value("${app.api-keys.max-per-user:10}") int maxKeysPerUser) {
        this.repository = repository;
        this.maxKeysPerUser = maxKeysPerUser;
    }

    /** Creates a named key for the user and returns the raw key, which is not recoverable afterwards. */
    public CreateApiKeyResponse create(String userId, String rawName) {
        String name = validateName(rawName);
        if (repository.countByUserId(userId) >= maxKeysPerUser) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "API key limit reached (" + maxKeysPerUser + "). Revoke a key you no longer use first.");
        }
        String rawKey = generateRawKey();
        ApiKey key = new ApiKey(generateId(), userId, name, rawKey.substring(0, PREFIX_LENGTH), Instant.now(), null);
        repository.save(key, ApiKeyHasher.hash(rawKey));
        return new CreateApiKeyResponse(key.id(), key.name(), rawKey, key.keyPrefix(), key.createdAt());
    }

    /** Creates a key for the bootstrap admin without the per-account limit check; returns the raw key. */
    public String createForBootstrap(String userId, String name) {
        String rawKey = generateRawKey();
        repository.save(new ApiKey(generateId(), userId, name, rawKey.substring(0, PREFIX_LENGTH), Instant.now(), null),
                ApiKeyHasher.hash(rawKey));
        return rawKey;
    }

    public List<ApiKeyResponse> list(User user) {
        return repository.findByUserId(user.id()).stream().map(ApiKeyResponse::from).toList();
    }

    public void revoke(User user, String keyId) {
        if (!repository.deleteByIdAndUserId(keyId, user.id())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "API key not found: " + keyId);
        }
    }

    /** Records that a key was used, writing to the database at most once per interval per key. */
    public void recordUse(String keyHash) {
        Instant now = Instant.now();
        Instant last = lastUsedWrites.get(keyHash);
        if (last != null && Duration.between(last, now).compareTo(LAST_USED_WRITE_INTERVAL) < 0) {
            return;
        }
        lastUsedWrites.put(keyHash, now);
        repository.touchLastUsed(keyHash, now);
    }

    private String validateName(String rawName) {
        String name = rawName == null ? "" : rawName.strip();
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A name for the key is required");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Key name must be at most " + MAX_NAME_LENGTH + " characters");
        }
        return name;
    }

    private static String generateRawKey() {
        return "pp_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String generateId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
