package com.bunq.javabackend.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI launchlensOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("LaunchLens API")
                        .description("Compliance analysis pipeline: ingest → extract → map → score → sanctions → ground-check → narrate → report.")
                        .version("v1")
                        .license(new License().name("Internal")));
    }
}
