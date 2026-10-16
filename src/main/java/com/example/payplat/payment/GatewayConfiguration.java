package com.example.payplat.payment;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayConfiguration {
    @Bean
    ChannelGateway channelGateway() {
        return new ChannelGateway() {
            @Override
            public SubmitResult submit(String channelId, long orderId, long amountCents) {
                throw new UnsupportedOperationException("no channel gateway configured");
            }

            @Override
            public ChannelQueryResult query(String channelId, long orderId) {
                throw new UnsupportedOperationException("no channel gateway configured");
            }
        };
    }
}
