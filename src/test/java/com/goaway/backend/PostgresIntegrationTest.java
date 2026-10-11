package com.goaway.backend;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import com.goaway.backend.mail.RecordingEmailSender;

/**
 * Base class for tests that need the database.
 *
 * <p>Starts one PostgreSQL container for the whole test run, using the same image and the same
 * role-setup script ({@code docker/postgres/init/01-create-roles.sh}) as local development, so tests
 * run against the real least-privilege setup: Flyway migrates as the owner role, the application
 * connects as the app role.
 *
 * <p>Rate limiting is off here, because tests sign in many times from the same address; {@code RateLimitTests}
 * turns it on with small limits. Emails are recorded ({@link RecordingEmailSender}) and sent synchronously, so
 * tests can read them right after the request.
 */
@TestPropertySource(properties = { "goaway.rate-limit.enabled=false", "goaway.mail.async=false" })
@Import(RecordingEmailSender.Config.class)
public abstract class PostgresIntegrationTest {

	protected static final String DB_NAME = "goaway";
	protected static final String OWNER_USER = "goaway_owner";
	protected static final String OWNER_PASSWORD = "test-owner-password";
	protected static final String APP_USER = "goaway_app";
	protected static final String APP_PASSWORD = "test-app-password";

	// Keep in sync with the image in compose.yaml
	private static final String POSTGRES_IMAGE = "postgres:17.11";

	protected static final GenericContainer<?> POSTGRES = new GenericContainer<>(POSTGRES_IMAGE)
		.withEnv("POSTGRES_USER", "postgres")
		.withEnv("POSTGRES_PASSWORD", "test-superuser-password")
		.withEnv("POSTGRES_DB", "postgres")
		.withEnv("GOAWAY_DB_NAME", DB_NAME)
		.withEnv("GOAWAY_OWNER_USER", OWNER_USER)
		.withEnv("GOAWAY_OWNER_PASSWORD", OWNER_PASSWORD)
		.withEnv("GOAWAY_APP_USER", APP_USER)
		.withEnv("GOAWAY_APP_PASSWORD", APP_PASSWORD)
		.withCopyFileToContainer(
				MountableFile.forHostPath("docker/postgres/init/01-create-roles.sh", 0755),
				"/docker-entrypoint-initdb.d/01-create-roles.sh")
		.withExposedPorts(5432)
		// Postgres logs this once for the temporary init server and once when it is really ready
		.waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2));

	static {
		POSTGRES.start();
	}

	protected static String jdbcUrl() {
		return "jdbc:postgresql://%s:%d/%s".formatted(POSTGRES.getHost(), POSTGRES.getMappedPort(5432), DB_NAME);
	}

	/** Throwaway ES256 key pair for signing access tokens in tests. */
	private static final KeyPair JWT_KEYS = generateJwtKeys();

	@DynamicPropertySource
	static void databaseProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", PostgresIntegrationTest::jdbcUrl);
		registry.add("spring.datasource.username", () -> APP_USER);
		registry.add("spring.datasource.password", () -> APP_PASSWORD);
		registry.add("spring.flyway.user", () -> OWNER_USER);
		registry.add("spring.flyway.password", () -> OWNER_PASSWORD);
		registry.add("goaway.auth.jwt.private-key",
				() -> Base64.getEncoder().encodeToString(JWT_KEYS.getPrivate().getEncoded()));
		registry.add("goaway.auth.jwt.public-key",
				() -> Base64.getEncoder().encodeToString(JWT_KEYS.getPublic().getEncoded()));
	}

	private static KeyPair generateJwtKeys() {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
			generator.initialize(new ECGenParameterSpec("secp256r1"));
			return generator.generateKeyPair();
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
