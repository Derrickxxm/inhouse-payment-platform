package com.example.payplat.payment;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayConfiguration {
    @Bean
    ChannelGateway channelGateway() {
        return (channelId, orderId, amountCents) -> {
            throw new UnsupportedOperationException("no channel gateway configured");
        };
    }
}
