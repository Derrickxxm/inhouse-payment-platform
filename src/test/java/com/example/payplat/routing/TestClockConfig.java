package com.example.payplat.routing;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class TestClockConfig {
    @Bean
    @Primary
    public TestClock testClock() { return new TestClock(); }
}
