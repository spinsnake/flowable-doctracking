package th.ac.mfu.doctracking;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

import javax.sql.DataSource;

import org.flowable.engine.ManagementService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = CompatibilityTest.Application.class, properties = {
    "spring.main.web-application-type=none",
    "flowable.database-schema-update=true",
    "flowable.async-executor-activate=false",
    "flowable.check-process-definitions=false",
    "flowable.idm.enabled=false",
    "flowable.eventregistry.enabled=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompatibilityTest {
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class Application { }

    @Autowired RepositoryService repository;
    @Autowired RuntimeService runtime;
    @Autowired TaskService tasks;
    @Autowired ManagementService jobs;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired SpringProcessEngineConfiguration engineConfiguration;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) throws SQLException {
        String url = requiredEnvironment("SPIKE_DB_URL");
        String user = requiredEnvironment("SPIKE_DB_USER");
        String password = requiredEnvironment("SPIKE_DB_PASSWORD");
        if (!url.startsWith("jdbc:postgresql:") || url.contains("currentSchema=")) {
            throw new IllegalArgumentException("SPIKE_DB_URL must be PostgreSQL without currentSchema");
        }
        // Each run owns a new schema; existing application/engine tables are never modified.
        String schema = "p0_spike_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url, user, password);
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        System.out.println("P0 spike isolated schema: " + schema);
        String isolatedUrl = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        properties.add("spring.datasource.url", () -> isolatedUrl);
        properties.add("spring.datasource.username", () -> user);
        properties.add("spring.datasource.password", () -> password);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Set " + name + " for a disposable PostgreSQL database");
        }
        return value;
    }

    @BeforeAll
    void deploy() {
        jdbc.execute("""
            CREATE TABLE spike_workflow_inbox (
                event_id UUID PRIMARY KEY,
                process_instance_id VARCHAR(64) NOT NULL,
                applied BOOLEAN NOT NULL DEFAULT FALSE
            )
            """);
        repository.createDeployment().name("P0 compatibility")
            .addClasspathResource("compatibility.bpmn20.xml").deploy();
    }

    @Test
    void deployCompleteAndExecutePersistedTimer() {
        String processId = start();
        tasks.complete(task(processId, "review").getId());
        var timer = jobs.createTimerJobQuery().processInstanceId(processId).singleResult();
        assertNotNull(timer, "Completing review must persist a timer in PostgreSQL");
        assertNull(tasks.createTaskQuery().processInstanceId(processId).singleResult());
        var executable = jobs.moveTimerToExecutableJob(timer.getId());
        jobs.executeJob(executable.getId());
        tasks.complete(task(processId, "afterTimer").getId());
        assertEquals(0, runtime.createProcessInstanceQuery().processInstanceId(processId).count());
        assertEquals(0, jobs.createTimerJobQuery().processInstanceId(processId).count());
    }

    @Test
    void applicationInboxAndEngineCompletionRollbackTogether() {
        DataSource engineDataSource = engineConfiguration.getDataSource();
        if (engineDataSource instanceof TransactionAwareDataSourceProxy proxy) {
            engineDataSource = proxy.getTargetDataSource();
        }
        assertSame(dataSource, engineDataSource);
        assertSame(transactionManager, engineConfiguration.getTransactionManager());
        String processId = start();
        UUID eventId = UUID.randomUUID();
        var transaction = new TransactionTemplate(transactionManager);
        assertThrows(InjectedFailure.class, () -> transaction.executeWithoutResult(status -> {
            assertTrue(apply(eventId, processId));
            throw new InjectedFailure();
        }));
        assertEquals(0, inboxCount(eventId));
        assertNotNull(task(processId, "review"));
        assertEquals(0, jobs.createTimerJobQuery().processInstanceId(processId).count());

        assertTrue(Boolean.TRUE.equals(transaction.execute(status -> apply(eventId, processId))));
        assertEquals(1, inboxCount(eventId));
        assertEquals(Boolean.TRUE, jdbc.queryForObject(
            "SELECT applied FROM spike_workflow_inbox WHERE event_id = ?", Boolean.class, eventId));
        assertEquals(1, jobs.createTimerJobQuery().processInstanceId(processId).count());
    }

    @Test
    void committedEventRetryDoesNotAdvanceNextTask() {
        String processId = start();
        UUID eventId = UUID.randomUUID();
        var transaction = new TransactionTemplate(transactionManager);
        assertTrue(Boolean.TRUE.equals(transaction.execute(status -> apply(eventId, processId))));
        var timer = jobs.createTimerJobQuery().processInstanceId(processId).singleResult();
        var executable = jobs.moveTimerToExecutableJob(timer.getId());
        jobs.executeJob(executable.getId());
        String nextTaskId = task(processId, "afterTimer").getId();
        assertFalse(Boolean.TRUE.equals(transaction.execute(status -> apply(eventId, processId))));
        assertEquals(nextTaskId, task(processId, "afterTimer").getId());
        assertEquals(1, inboxCount(eventId));
    }

    private String start() {
        return runtime.startProcessInstanceByKey("compatibility", UUID.randomUUID().toString()).getId();
    }

    private Task task(String processId, String key) {
        return Objects.requireNonNull(tasks.createTaskQuery().processInstanceId(processId)
            .taskDefinitionKey(key).singleResult(), "Expected task: " + key);
    }

    // Caller owns the transaction; event identity and engine effect commit as one unit.
    private boolean apply(UUID eventId, String processId) {
        int inserted = jdbc.update("""
            INSERT INTO spike_workflow_inbox(event_id, process_instance_id)
            VALUES (?, ?) ON CONFLICT (event_id) DO NOTHING
            """, eventId, processId);
        if (inserted == 0) {
            return false;
        }
        tasks.complete(task(processId, "review").getId());
        jdbc.update("UPDATE spike_workflow_inbox SET applied = TRUE WHERE event_id = ?", eventId);
        return true;
    }

    private int inboxCount(UUID eventId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM spike_workflow_inbox WHERE event_id = ?",
            Integer.class, eventId);
    }

    private static class InjectedFailure extends RuntimeException { }
}
