package com.vajra.coreservice.testsupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/** Postgres 18 with the real init script; the service connects as its own role. */
public final class TestDatabase {

    public static final String SCHEMA = "core";
    public static final String ROLE = SCHEMA + "_svc";
    private static final String ROLE_PASSWORD = "test-pw";
    private static final Path REPO_ROOT = Path.of("../..").toAbsolutePath().normalize();

    private TestDatabase() {}

    public static PostgreSQLContainer create() {
        PostgreSQLContainer container = new PostgreSQLContainer(DockerImageName.parse(postgresImage())
                        .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("vajra")
                .withCopyFileToContainer(
                        MountableFile.forHostPath(REPO_ROOT.resolve("infra/postgres/init/01-roles-and-schemas.sh")),
                        "/docker-entrypoint-initdb.d/01-roles-and-schemas.sh");
        for (String schema : new String[] {"CORE", "RISK", "GATEWAY", "RESEARCH", "SIGNAL"}) {
            container.withEnv(schema + "_DB_PASSWORD", ROLE_PASSWORD);
        }
        return container;
    }

    public static void registerAsServiceRole(PostgreSQLContainer container, DynamicPropertyRegistry registry) {
        registry.add("DB_URL", container::getJdbcUrl);
        registry.add("DB_USER", () -> ROLE);
        registry.add("DB_PASSWORD", () -> ROLE_PASSWORD);
        registry.add("vajra.signals.enabled", () -> "false");
    }

    /** DataSource as the service role, with core-service's Flyway migrations applied (no Spring context). */
    public static DataSource migratedDataSource(PostgreSQLContainer container) {
        DataSource dataSource = new DriverManagerDataSource(container.getJdbcUrl(), ROLE, ROLE_PASSWORD);
        Flyway.configure().dataSource(dataSource).schemas(SCHEMA).defaultSchema(SCHEMA).load().migrate();
        return dataSource;
    }

    /** Image tag lives only in infra/compose/.env.example (one place per ecosystem). */
    private static String postgresImage() {
        return imageFromEnvExample("POSTGRES_IMAGE");
    }

    public static String kafkaImage() {
        return imageFromEnvExample("KAFKA_IMAGE");
    }

    private static String imageFromEnvExample(String name) {
        try {
            return Files.readAllLines(REPO_ROOT.resolve("infra/compose/.env.example")).stream()
                    .filter(line -> line.startsWith(name + "="))
                    .map(line -> line.substring(name.length() + 1).trim())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(name + " missing in .env.example"));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read infra/compose/.env.example", e);
        }
    }
}
