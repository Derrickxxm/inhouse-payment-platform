package com.example.payplat.routing;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

/** Fresh context per test: registries are stateful singletons. Subclasses declare their own @TestPropertySource. */
@SpringBootTest
@Import(TestClockConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
abstract class RoutingTestBase {
    static final String A = "FakeBankA";
    static final String B = "FakeBankB";
    static final String C = "FakeBankC";
    static final String W = "FakeWallet";

    @Autowired ChannelRegistry registry;
    @Autowired Router router;
    @Autowired CircuitBreakerRegistry circuits;
    @Autowired RateLimiterRegistry limiter;
    @Autowired ChannelSwitchService switchService;
    @Autowired SwitchLog switchLog;
    @Autowired TestClock clock;
}
