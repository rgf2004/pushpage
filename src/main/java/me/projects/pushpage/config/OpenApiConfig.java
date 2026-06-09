package me.projects.pushpage.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI postpageOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("postpage API")
                        .description("Self-hosted HTML page publishing service. " +
                                "Post HTML content and get a shareable URL back.")
                        .version("1.0.0"));
    }
}
