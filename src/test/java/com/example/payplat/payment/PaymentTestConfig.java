package com.example.payplat.payment;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class PaymentTestConfig {
    @Bean
    @Primary
    public ScriptedGateway scriptedGateway() { return new ScriptedGateway(); }
}
