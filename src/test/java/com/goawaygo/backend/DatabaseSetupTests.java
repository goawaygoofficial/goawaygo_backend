package com.goawaygo.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies the database security model: migrations run as the owner role, the application runs as a
 * least-privilege role that can use data but cannot change the schema.
 */
@SpringBootTest
class DatabaseSetupTests extends PostgresIntegrationTest {

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void applicationConnectsAsTheAppRole() {
		assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_USER);
	}

	@Test
	void flywayAppliedTheBaselineMigrationAsTheOwner() {
		var row = jdbc.queryForMap(
				"SELECT installed_by, success FROM flyway_schema_history WHERE version = '1'");

		assertThat(row.get("installed_by")).isEqualTo(OWNER_USER);
		assertThat(row.get("success")).isEqualTo(true);
	}

	@Test
	void appRoleCannotCreateTables() {
		assertThatThrownBy(() -> jdbc.execute("CREATE TABLE should_not_exist (id int)"))
			.satisfies(DatabaseSetupTests::isInsufficientPrivilege);
	}

	@Test
	void appRoleCanReadAndWriteTablesCreatedByTheOwnerButNotDropThem() throws SQLException {
		try (Connection owner = DriverManager.getConnection(jdbcUrl(), OWNER_USER, OWNER_PASSWORD);
				Statement statement = owner.createStatement()) {
			statement.execute("CREATE TABLE permission_check (id int)");
			try {
				jdbc.update("INSERT INTO permission_check VALUES (1)");
				assertThat(jdbc.queryForObject("SELECT count(*) FROM permission_check", Integer.class)).isEqualTo(1);

				assertThatThrownBy(() -> jdbc.execute("DROP TABLE permission_check"))
					.satisfies(DatabaseSetupTests::isInsufficientPrivilege);
			}
			finally {
				statement.execute("DROP TABLE permission_check");
			}
		}
	}

	/** PostgreSQL SQLSTATE 42501 = insufficient_privilege (more reliable than matching message text). */
	private static void isInsufficientPrivilege(Throwable thrown) {
		assertThat(thrown).isInstanceOf(DataAccessException.class);
		assertThat(NestedExceptionUtils.getMostSpecificCause(thrown))
			.isInstanceOfSatisfying(SQLException.class, cause -> assertThat(cause.getSQLState()).isEqualTo("42501"));
	}

}
