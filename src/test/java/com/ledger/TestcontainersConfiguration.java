package com.ledger;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	MySQLContainer mysqlContainer() {
		return new MySQLContainer(DockerImageName.parse("mysql:8.4"))
				.withUrlParam("connectionTimeZone", "UTC")
				.withUrlParam("forceConnectionTimeZoneToSession", "true")
				.withUrlParam("sslMode", "DISABLED")
				.withUrlParam("allowPublicKeyRetrieval", "true")
				.withCommand("--default-time-zone=+00:00");
	}

	@Bean
	@ServiceConnection
	RabbitMQContainer rabbitContainer() {
		return new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management"));
	}

}
