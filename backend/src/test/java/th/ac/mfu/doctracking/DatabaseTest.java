package th.ac.mfu.doctracking;

import java.sql.DriverManager;
import java.util.UUID;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ActiveProfiles("test")
public abstract class DatabaseTest {
    private static final String SCHEMA = "p1_test_" + UUID.randomUUID().toString().replace("-", "");
    private static boolean created;

    @DynamicPropertySource
    static synchronized void database(DynamicPropertyRegistry registry) throws Exception {
        String url = required("TEST_DB_URL");
        String user = required("TEST_DB_USER");
        String password = required("TEST_DB_PASSWORD");
        if (!url.startsWith("jdbc:postgresql:") || url.contains("currentSchema=")) {
            throw new IllegalStateException("Use a disposable PostgreSQL TEST_DB_URL without currentSchema");
        }
        if (!created) {
            try (var connection = DriverManager.getConnection(url, user, password);
                 var statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA " + SCHEMA);
            }
            created = true;
        }
        registry.add("spring.datasource.url", () -> url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        registry.add("spring.datasource.username", () -> user);
        registry.add("spring.datasource.password", () -> password);
        registry.add("flowable.database-schema-update", () -> "true");
        registry.add("flowable.database-schema", () -> SCHEMA);
        registry.add("app.mail-worker-enabled", () -> "false");
        registry.add("app.session-secure", () -> "false");
        registry.add("app.evidence.root", () -> ".local/test-evidence");
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Set " + name + "; H2 fallback is not permitted");
        return value;
    }
}
