package th.ac.mfu.doctracking.operations;

import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import th.ac.mfu.doctracking.identity.*;
import th.ac.mfu.doctracking.common.*;
import th.ac.mfu.doctracking.domain.OrphanUploadCleanup;

@RestController @RequestMapping("/api/v1/admin/operations")
public class OperationsController {
    private final ActorService actors;private final OperationsService operations;private final OrphanUploadCleanup cleanup;private final CommandService commands;private final IdentityService identities;
    public OperationsController(ActorService actors,OperationsService operations,OrphanUploadCleanup cleanup,CommandService commands,IdentityService identities) {this.actors=actors;this.operations=operations;this.cleanup=cleanup;this.commands=commands;this.identities=identities;}
    @GetMapping("/summary") public Object summary() {return operations.summary(actors.current());}
    @GetMapping({"/workflow-deliveries","/login-notifications","/exceptions","/audit-events","/reminders","/tasks"})
    public Object list(jakarta.servlet.http.HttpServletRequest request,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="50") int limit,@RequestParam(required=false) String status) {
        return operations.list(actors.current(),request.getRequestURI().substring(request.getRequestURI().lastIndexOf('/')+1),cursor,limit,status);
    }
    @GetMapping("/documents/{id}/engine") public Object engine(@PathVariable UUID id) {return operations.engine(actors.current(),id);}
    public record Cleanup(@NotNull Boolean dry_run,@NotNull @Min(1) @Max(100) Integer limit,@NotBlank @Size(max=1000) String reason) {}
    @PostMapping("/orphan-uploads/cleanup") public Object cleanup(@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Cleanup input) {
        Actor actor=actors.current();actor.requireAdmin();var result=commands.execute(actor,"operations:orphan-cleanup",key,input,()->{
            var outcome=cleanup.run(actor,input.dry_run(),input.limit());
            identities.audit(actor,input.dry_run()?"ORPHAN_CLEANUP_PREVIEW":"ORPHAN_CLEANUP",null,input.reason()+"; candidates="+outcome.get("candidates")+"; deleted="+outcome.get("deleted")+"; skipped="+outcome.get("skipped")+"; failed="+outcome.get("failed"));
            return new CommandResult(200,outcome);
        });return ResponseEntity.status(result.status()).body(result.body());
    }
}
