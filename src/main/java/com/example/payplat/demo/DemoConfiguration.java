package com.example.payplat.demo;

import com.example.payplat.routing.ChannelRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("demo")
public class DemoConfiguration {
    @Bean @Primary DemoClock demoClock() { return new DemoClock(); }
    @Bean @Primary DemoGateway demoGateway(ChannelRegistry registry, DemoClock clock) {
        return new DemoGateway(registry, clock);
    }
}
