package com.example.payplat;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PayplatApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(PayplatApplication.class);
        if (java.util.Arrays.asList(args).contains("demo")) {
            application.setDefaultProperties(java.util.Map.of(
                "spring.main.banner-mode", "off", "logging.level.root", "WARN"));
        }
        application.run(args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
