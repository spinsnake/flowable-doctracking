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
    public record Retry(@NotNull @Min(0) Long expected_version,@NotBlank @Size(max=1000) String reason,UUID delivery_id) {
        public Retry(Long expected_version,String reason) { this(expected_version,reason,null); }
    }
    @GetMapping("/{id}")
    public Object status(@PathVariable UUID id) {
        actors.current().requireAdmin();
        var rows=db.queryForList("select o.id,o.event_id,o.document_id,e.operation_id,o.status,o.attempts,o.available_at as next_retry_at,o.last_error_code,o.version from workflow_outbox o join document_events e on e.id=o.event_id where e.operation_id=? order by o.document_id,o.sequence",id);
        if(rows.isEmpty())throw new ApiException(404,"NOT_FOUND","Workflow delivery not found");
        var result=new LinkedHashMap<String,Object>();result.put("operation_id",id);
        // Keep the original singleton fields for existing admin clients.
        if(rows.size()==1) for(String field:List.of("status","attempts","next_retry_at","last_error_code","version")) result.put(field,rows.getFirst().get(field));
        result.put("process_sync_status",rows.stream().anyMatch(row->Set.of("RETRY_WAIT","DEAD_LETTER").contains(row.get("status")))?"FAILED_RETRYABLE":rows.stream().allMatch(row->"APPLIED".equals(row.get("status")))?"APPLIED":"PENDING");
        result.put("deliveries",rows);return result;
    }
    @PostMapping("/{id}/retry")
    public ResponseEntity<?> retry(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Retry input) {
        Actor actor=actors.current();actor.requireAdmin();
        var result=commands.execute(actor,"workflow:retry:"+id,key,input,()->{
            var operations=db.queryForList("select document_id from tracking_operations where id=?",id);
            if(operations.isEmpty())throw new ApiException(404,"NOT_FOUND","Operation not found");
            var candidates=db.queryForList("select o.id,o.document_id from workflow_outbox o join document_events e on e.id=o.event_id where e.operation_id=? and (?::uuid is null or o.id=?::uuid) order by o.document_id,o.sequence",id,input.delivery_id(),input.delivery_id());
            if(candidates.isEmpty())throw new ApiException(404,"NOT_FOUND","Workflow delivery not found");
            if(candidates.size()!=1)throw new ApiException(422,"DELIVERY_REQUIRED","Select the exact workflow delivery to retry");
            UUID delivery=(UUID)candidates.getFirst().get("id"),document=(UUID)candidates.getFirst().get("document_id");
            db.queryForList("select id from documents where id=? for update",document);
            var rows=db.queryForList("select o.* from workflow_outbox o join document_events e on e.id=o.event_id where e.operation_id=? and o.id=? for update of o",id,delivery);
            if(rows.size()!=1)throw new ApiException(409,"RETRY_NOT_ALLOWED","One existing workflow delivery is required");
            var job=rows.getFirst();
            if(((Number)job.get("version")).longValue()!=input.expected_version())throw new ApiException(409,"STALE_VERSION","Reload workflow delivery");
            if(!Set.of("RETRY_WAIT","DEAD_LETTER").contains(job.get("status")))throw new ApiException(409,"RETRY_NOT_ALLOWED","Only failed deliveries may be retried");
            db.update("update workflow_outbox set status='PENDING',available_at=now(),last_error_code=null,version=version+1 where id=?",job.get("id"));
            db.update("update documents set process_sync_status='PENDING' where id=?",document);
            identities.audit(actor,"WORKFLOW_RETRY",id,"Delivery "+delivery+": "+input.reason());
            return new CommandResult(202,Map.of("operation_id",id,"delivery_id",delivery,"process_sync_status","PENDING"));
        });
        return ResponseEntity.status(result.status()).header("Location","/api/v1/operations/"+id).body(result.body());
    }
}
