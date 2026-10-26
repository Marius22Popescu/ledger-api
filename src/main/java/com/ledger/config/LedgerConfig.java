package com.ledger.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class LedgerConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
