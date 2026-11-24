package me.projects.pushpage.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import me.projects.pushpage.config.AuthContext;
import me.projects.pushpage.model.ApiKeyResponse;
import me.projects.pushpage.model.CreateApiKeyRequest;
import me.projects.pushpage.model.CreateApiKeyResponse;
import me.projects.pushpage.service.ApiKeyService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Tag(name = "API keys", description = "Create, list and revoke your named API keys (requires a signed-in session, not an API key)")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;
    private final AuthContext authContext;

    public ApiKeyController(ApiKeyService apiKeyService, AuthContext authContext) {
        this.apiKeyService = apiKeyService;
        this.authContext = authContext;
    }

    @Operation(summary = "List API keys",
            description = "Lists the authenticated user's keys: name, key prefix, creation and last-used time. Raw keys are never returned.")
    @GetMapping("/me/keys")
    public List<ApiKeyResponse> list() {
        return apiKeyService.list(authContext.requireCurrentUser());
    }

    @Operation(summary = "Create an API key",
            description = "Creates a named key. The raw key is returned once, store it securely. Other keys are not affected.")
    @PostMapping("/me/keys")
    @ResponseStatus(HttpStatus.CREATED)
    public CreateApiKeyResponse create(@RequestBody CreateApiKeyRequest request) {
        return apiKeyService.create(authContext.requireCurrentUser().id(), request.name());
    }

    @Operation(summary = "Revoke an API key",
            description = "Permanently revokes one key. Other keys keep working.")
    @DeleteMapping("/me/keys/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable String id) {
        apiKeyService.revoke(authContext.requireCurrentUser(), id);
    }
}
