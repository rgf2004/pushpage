package me.projects.pushpage.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Value("${app.server-url}")
    private String serverUrl;

    @Value("${server.servlet.context-path:}")
    private String contextPath;

    @Bean
    public OpenAPI pushpageOpenAPI() {
        return new OpenAPI()
                .servers(List.of(new Server().url(serverUrl + contextPath)))
                .info(new Info()
                        .title("pushpage API")
                        .description("Self-hosted HTML page publishing service. " +
                                "Post HTML content and get a shareable URL back.")
                        .version("1.0.0"));
    }
}
