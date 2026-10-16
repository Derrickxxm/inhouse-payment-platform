package com.example.payplat.routing;

import java.util.List;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "payplat")
public record ChannelRegistry(List<ChannelProperties> channels) {
    public ChannelRegistry {
        channels = List.copyOf(channels);
    }

    public List<ChannelProperties> all() {
        return channels;
    }

    public Optional<ChannelProperties> byId(String id) {
        return channels.stream().filter(channel -> channel.id().equals(id)).findFirst();
    }
}
