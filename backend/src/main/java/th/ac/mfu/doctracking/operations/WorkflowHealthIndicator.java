package th.ac.mfu.doctracking.operations;

import org.flowable.engine.RepositoryService;
import org.springframework.boot.health.contributor.*;
import org.springframework.stereotype.Component;
import th.ac.mfu.doctracking.workflow.TrackingWorkflowEngine;

@Component("workflow")
public class WorkflowHealthIndicator implements HealthIndicator {
    private final RepositoryService repository;
    public WorkflowHealthIndicator(RepositoryService repository) {this.repository=repository;}
    @Override public Health health() {
        try {return repository.createProcessDefinitionQuery().processDefinitionKey(TrackingWorkflowEngine.KEY).count()>0?Health.up().build():Health.down().build();}
        catch(RuntimeException unavailable) {return Health.down().build();}
    }
}
