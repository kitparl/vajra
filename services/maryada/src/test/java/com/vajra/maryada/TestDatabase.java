package com.vajra.maryada;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/** Postgres 18 with the real init script; the service connects as its own role. */
final class TestDatabase {

    static final String SCHEMA = "risk";
    static final String ROLE = SCHEMA + "_svc";
    private static final String ROLE_PASSWORD = "test-pw";
    private static final Path REPO_ROOT = Path.of("../..").toAbsolutePath().normalize();

    private TestDatabase() {}

    static PostgreSQLContainer create() {
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

    static void registerAsServiceRole(PostgreSQLContainer container, DynamicPropertyRegistry registry) {
        registry.add("DB_URL", container::getJdbcUrl);
        registry.add("DB_USER", () -> ROLE);
        registry.add("DB_PASSWORD", () -> ROLE_PASSWORD);
    }

    /** Image tag lives only in infra/compose/.env.example (one place per ecosystem). */
    private static String postgresImage() {
        try {
            return Files.readAllLines(REPO_ROOT.resolve("infra/compose/.env.example")).stream()
                    .filter(line -> line.startsWith("POSTGRES_IMAGE="))
                    .map(line -> line.substring("POSTGRES_IMAGE=".length()).trim())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("POSTGRES_IMAGE missing in .env.example"));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read infra/compose/.env.example", e);
        }
    }
}
