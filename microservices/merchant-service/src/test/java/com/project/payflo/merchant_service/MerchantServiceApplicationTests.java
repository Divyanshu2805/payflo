package com.project.payflo.merchant_service;

import com.project.payflo.test_support.PayfloIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

// Starts the whole service on an empty PostgreSQL, so Flyway has to build the schema from its migrations and Hibernate
// (ddl-auto: validate) has to find every entity in it. A migration that is missing or doesn't match fails here.
class MerchantServiceApplicationTests extends PayfloIntegrationTest {

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void contextLoads() {
	}

	@Test
	void theMigrationsRanOnAnEmptyDatabase() {
		Integer applied = jdbc.queryForObject("select count(*) from flyway_schema_history where success", Integer.class);

		assertThat(applied).isPositive();
	}
}
