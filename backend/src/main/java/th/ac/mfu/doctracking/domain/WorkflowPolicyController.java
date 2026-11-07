package th.ac.mfu.doctracking.domain;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import th.ac.mfu.doctracking.common.CommandService;
import th.ac.mfu.doctracking.identity.ActorService;

@RestController
@RequestMapping("/api/v1/admin/workflow-policy")
public class WorkflowPolicyController {
    private final ActorService actors;
    private final CommandService commands;
    private final WorkflowPolicyService policy;
    public WorkflowPolicyController(ActorService actors,CommandService commands,WorkflowPolicyService policy) {
        this.actors=actors;this.commands=commands;this.policy=policy;
    }
    @GetMapping public Object get() { return policy.get(actors.current()); }
    @PutMapping public ResponseEntity<?> change(@RequestHeader("Idempotency-Key") String key,@RequestBody WorkflowPolicyService.Change input) {
        var actor=actors.current();policy.authorize(actor);
        var result=commands.execute(actor,"workflow:policy",key,input,()->policy.change(actor,input));
        return ResponseEntity.status(result.status()).body(result.body());
    }
}
