package th.ac.mfu.doctracking.workflow;

import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import th.ac.mfu.doctracking.common.*;
import th.ac.mfu.doctracking.identity.*;

@RestController
@RequestMapping("/api/v1/admin/workflow-operations")
public class WorkflowRecoveryController {
    private final JdbcTemplate db;
    private final ActorService actors;
    private final CommandService commands;
    private final IdentityService identities;
    public WorkflowRecoveryController(JdbcTemplate db,ActorService actors,CommandService commands,IdentityService identities) {
        this.db=db;this.actors=actors;this.commands=commands;this.identities=identities;
    }
    public record Retry(@NotNull @Min(0) Long expected_version,@NotBlank @Size(max=1000) String reason) {}
    @GetMapping("/{id}")
    public Object status(@PathVariable UUID id) {
        actors.current().requireAdmin();
        var rows=db.queryForList("select e.operation_id,o.status,o.attempts,o.available_at as next_retry_at,o.last_error_code,o.version from workflow_outbox o join document_events e on e.id=o.event_id where e.operation_id=?",id);
        if(rows.isEmpty())throw new ApiException(404,"NOT_FOUND","Workflow delivery not found");
        return rows.getFirst();
    }
    @PostMapping("/{id}/retry")
    public ResponseEntity<?> retry(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Retry input) {
        Actor actor=actors.current();actor.requireAdmin();
        var result=commands.execute(actor,"workflow:retry:"+id,key,input,()->{
            var operations=db.queryForList("select document_id from tracking_operations where id=?",id);
            if(operations.isEmpty())throw new ApiException(404,"NOT_FOUND","Operation not found");
            UUID document=(UUID)operations.getFirst().get("document_id");
            db.queryForList("select id from documents where id=? for update",document);
            var rows=db.queryForList("select o.* from workflow_outbox o join document_events e on e.id=o.event_id where e.operation_id=? for update of o",id);
            if(rows.size()!=1)throw new ApiException(409,"RETRY_NOT_ALLOWED","One existing workflow delivery is required");
            var job=rows.getFirst();
            if(((Number)job.get("version")).longValue()!=input.expected_version())throw new ApiException(409,"STALE_VERSION","Reload workflow delivery");
            if(!Set.of("RETRY_WAIT","DEAD_LETTER").contains(job.get("status")))throw new ApiException(409,"RETRY_NOT_ALLOWED","Only failed deliveries may be retried");
            db.update("update workflow_outbox set status='PENDING',available_at=now(),last_error_code=null,version=version+1 where id=?",job.get("id"));
            db.update("update documents set process_sync_status='PENDING' where id=?",document);
            identities.audit(actor,"WORKFLOW_RETRY",id,input.reason());
            return new CommandResult(202,Map.of("operation_id",id,"process_sync_status","PENDING"));
        });
        return ResponseEntity.status(result.status()).header("Location","/api/v1/operations/"+id).body(result.body());
    }
}
