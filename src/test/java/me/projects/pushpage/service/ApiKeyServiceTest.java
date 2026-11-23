package me.projects.pushpage.service;

import me.projects.pushpage.model.ApiKey;
import me.projects.pushpage.model.ApiKeyResponse;
import me.projects.pushpage.model.CreateApiKeyResponse;
import me.projects.pushpage.model.User;
import me.projects.pushpage.repository.ApiKeyRepository;
import me.projects.pushpage.util.ApiKeyHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApiKeyServiceTest {

    static final User USER = new User("user1", "u@example.com", "hash", Instant.now(), true, false);

    @Mock
    ApiKeyRepository repository;

    ApiKeyService service;

    @BeforeEach
    void setUp() {
        service = new ApiKeyService(repository, 3);
    }

    private static void assertStatus(Throwable ex, HttpStatus expected) {
        assertThat(ex).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(expected);
    }

    @Test
    void create_returnsRawKeyOnceAndStoresOnlyItsHash() {
        when(repository.countByUserId("user1")).thenReturn(0L);

        CreateApiKeyResponse created = service.create("user1", "  laptop  ");

        assertThat(created.apiKey()).startsWith("pp_").hasSizeGreaterThan(20);
        assertThat(created.name()).isEqualTo("laptop");
        assertThat(created.keyPrefix()).isEqualTo(created.apiKey().substring(0, 8));
        ArgumentCaptor<ApiKey> key = ArgumentCaptor.forClass(ApiKey.class);
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(repository).save(key.capture(), hash.capture());
        assertThat(hash.getValue()).isEqualTo(ApiKeyHasher.hash(created.apiKey()));
        assertThat(hash.getValue()).doesNotContain(created.apiKey());
        assertThat(key.getValue().userId()).isEqualTo("user1");
        assertThat(key.getValue().lastUsedAt()).isNull();
    }

    @Test
    void create_twiceYieldsDifferentKeys() {
        when(repository.countByUserId("user1")).thenReturn(0L);

        assertThat(service.create("user1", "a").apiKey()).isNotEqualTo(service.create("user1", "b").apiKey());
    }

    @Test
    void create_withBlankNameFails() {
        assertThatThrownBy(() -> service.create("user1", "   ")).satisfies(ex -> assertStatus(ex, HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.create("user1", null)).satisfies(ex -> assertStatus(ex, HttpStatus.BAD_REQUEST));
        verify(repository, never()).save(any(), anyString());
    }

    @Test
    void create_withTooLongNameFails() {
        String tooLong = "x".repeat(ApiKeyService.MAX_NAME_LENGTH + 1);

        assertThatThrownBy(() -> service.create("user1", tooLong)).satisfies(ex -> assertStatus(ex, HttpStatus.BAD_REQUEST));
        verify(repository, never()).save(any(), anyString());
    }

    @Test
    void create_atTheLimitFailsWithConflict() {
        when(repository.countByUserId("user1")).thenReturn(3L);

        assertThatThrownBy(() -> service.create("user1", "one too many"))
                .satisfies(ex -> assertStatus(ex, HttpStatus.CONFLICT));
        verify(repository, never()).save(any(), anyString());
    }

    @Test
    void createForBootstrap_ignoresTheLimitAndReturnsTheRawKey() {
        String raw = service.createForBootstrap("admin1", "Bootstrap key");

        assertThat(raw).startsWith("pp_");
        verify(repository).save(any(ApiKey.class), eq(ApiKeyHasher.hash(raw)));
        verify(repository, never()).countByUserId(anyString());
    }

    @Test
    void list_mapsKeysWithoutAnySecret() {
        Instant created = Instant.parse("2026-11-01T10:00:00Z");
        when(repository.findByUserId("user1")).thenReturn(
                List.of(new ApiKey("k1", "user1", "laptop", "pp_ab12cd", created, null)));

        List<ApiKeyResponse> keys = service.list(USER);

        assertThat(keys).hasSize(1);
        assertThat(keys.get(0).name()).isEqualTo("laptop");
        assertThat(keys.get(0).keyPrefix()).isEqualTo("pp_ab12cd");
        assertThat(keys.get(0).lastUsedAt()).isNull();
    }

    @Test
    void revoke_deletesOnlyTheCallersKey() {
        when(repository.deleteByIdAndUserId("k1", "user1")).thenReturn(true);

        service.revoke(USER, "k1");

        verify(repository).deleteByIdAndUserId("k1", "user1");
    }

    @Test
    void revoke_unknownOrForeignKeyIsNotFound() {
        when(repository.deleteByIdAndUserId("k9", "user1")).thenReturn(false);

        assertThatThrownBy(() -> service.revoke(USER, "k9")).satisfies(ex -> assertStatus(ex, HttpStatus.NOT_FOUND));
    }

    @Test
    void recordUse_writesAtMostOncePerIntervalPerKey() {
        service.recordUse("hash-a");
        service.recordUse("hash-a");
        service.recordUse("hash-a");
        service.recordUse("hash-b");

        verify(repository, times(1)).touchLastUsed(eq("hash-a"), any(Instant.class));
        verify(repository, times(1)).touchLastUsed(eq("hash-b"), any(Instant.class));
    }
}
