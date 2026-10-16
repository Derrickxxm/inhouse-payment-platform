package com.example.payplat.payment;

import com.example.payplat.PayplatApplication;
import com.example.payplat.routing.TestClock;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;

/** Starts one application context on a file-based H2 database; call twice on the same dir to simulate a restart. */
final class CrashApp {
    private static volatile Duration clockOffset = Duration.ZERO;

    private CrashApp() {}

    /** Test-owned beans, passed explicitly because scanning of other test configurations is switched off. */
    @org.springframework.context.annotation.Configuration
    static class CrashConfig {
        @Bean
        @Primary
        CrashGateway crashGateway() { return new CrashGateway(); }

        @Bean
        @Primary
        TestClock testClock() {
            TestClock c = new TestClock();
            c.advance(clockOffset);
            return c;
        }
    }

    /** Keeps the scripted gateway / clock test configurations of the other tests out of this plain run. */
    static class ExcludeTestConfigurations extends TypeExcludeFilter {
        @Override
        public boolean match(MetadataReader reader, MetadataReaderFactory factory) {
            return reader.getAnnotationMetadata().isAnnotated(TestConfiguration.class.getName());
        }
    }

    static ConfigurableApplicationContext start(Path dir, Duration offset, boolean recoveryOnStartup) {
        clockOffset = offset;
        return new SpringApplicationBuilder(PayplatApplication.class, CrashConfig.class)
            .web(WebApplicationType.NONE)
            .initializers(ctx -> ctx.getBeanFactory().registerSingleton("excludeTestConfigurations", new ExcludeTestConfigurations()))
            .run("--spring.main.banner-mode=off",
                "--spring.datasource.url=jdbc:h2:file:" + dir.resolve("payplat") + ";DB_CLOSE_DELAY=0",
                "--spring.config.additional-location=classpath:/test-channels.properties",
                "--payplat.recovery.on-startup=" + recoveryOnStartup);
    }
}
