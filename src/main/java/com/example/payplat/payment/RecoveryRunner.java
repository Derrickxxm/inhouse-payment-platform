package com.example.payplat.payment;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "payplat.recovery.on-startup", havingValue = "true", matchIfMissing = true)
public class RecoveryRunner implements ApplicationRunner {
    private final RecoveryService recovery;

    public RecoveryRunner(RecoveryService recovery) {
        this.recovery = recovery;
    }

    @Override
    public void run(ApplicationArguments args) {
        recovery.recover();
    }
}
