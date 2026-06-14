package me.projects.pushpage.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Optional;

@Configuration
public class OpenApiConfig {

    private static final String API_KEY_SCHEME = "ApiKeyAuth";

    @Value("${app.server-url}")
    private String serverUrl;

    @Value("${server.servlet.context-path:}")
    private String contextPath;

    @Bean
    public OpenAPI pushpageOpenAPI(Optional<BuildProperties> buildProperties) {
        final String version = buildProperties.map(BuildProperties::getVersion).orElse("dev");
        return new OpenAPI()
                .servers(List.of(new Server().url(serverUrl + contextPath)))
                .info(new Info()
                        .title("pushpage API")
                        .description("Self-hosted HTML page publishing service. " +
                                "Post HTML content and get a shareable URL back.")
                        .version(version))
                .addSecurityItem(new SecurityRequirement().addList(API_KEY_SCHEME))
                .components(new Components()
                        .addSecuritySchemes(API_KEY_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Api-Key")
                                .description("API key. Can also be passed as 'Authorization: Bearer <key>'")));
    }
}
