package th.ac.mfu.doctracking.workflow;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import th.ac.mfu.doctracking.common.CommandService;
import th.ac.mfu.doctracking.identity.ActorService;

@RestController
@RequestMapping("/api/v1/admin/operations/reconciliation")
public class WorkflowReconciliationController {
    private final ActorService actors;
    private final CommandService commands;
    private final WorkflowReconciliationService reconciliation;
    public WorkflowReconciliationController(ActorService actors,CommandService commands,WorkflowReconciliationService reconciliation) {
        this.actors=actors;this.commands=commands;this.reconciliation=reconciliation;
    }
    @GetMapping("/findings") public Object findings(@RequestParam(defaultValue="OPEN") String state,
            @RequestParam(required=false) UUID document_id,@RequestParam(defaultValue="100") int limit) {
        return reconciliation.findings(actors.current(),state,document_id,limit);
    }
    @PostMapping("/scan") public ResponseEntity<?> scan(@RequestHeader("Idempotency-Key") String key,
            @RequestBody WorkflowReconciliationService.Scan input) {
        var actor=actors.current();reconciliation.authorize(actor);
        var result=commands.execute(actor,"operations:reconciliation",key,input,()->reconciliation.scan(actor,input));
        return ResponseEntity.status(result.status()).body(result.body());
    }
}
