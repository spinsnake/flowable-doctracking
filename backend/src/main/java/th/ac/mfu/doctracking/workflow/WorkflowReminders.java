package th.ac.mfu.doctracking.workflow;

import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Durable reminder feed; delivery/operations UI belongs to P6. It never changes custody or completes tasks. */
@Component
public class WorkflowReminders {
    private final JdbcTemplate db;
    public WorkflowReminders(JdbcTemplate db) {this.db=db;}
    @EventListener
    public void record(TrackingWorkflowEngine.Reminder event) {
        db.update("insert into workflow_reminders(document_id,task_key,route_step_id,execution_id) values(?,?,?,?)",
                UUID.fromString(event.documentId()),event.taskKey(),UUID.fromString(event.routeStepId()),event.executionId());
    }
}
