package com.ledger.config;

import com.ledger.domain.LedgerCurrency;
import com.ledger.store.LedgerStore;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SettlementAccounts {

    @Bean
    ApplicationRunner settlementAccountsExist(LedgerStore store) {
        return args -> {
            for (LedgerCurrency currency : LedgerCurrency.values()) {
                store.findAccount(currency.settlementAccountId()).orElseThrow(() ->
                        new IllegalStateException("Missing settlement account " + currency.settlementAccountId()));
            }
        };
    }
}
