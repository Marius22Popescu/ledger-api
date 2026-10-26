package com.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LedgerApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(LedgerApiApplication.class, args);
	}

}
