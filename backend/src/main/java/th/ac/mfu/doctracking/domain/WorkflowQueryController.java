package th.ac.mfu.doctracking.domain;

import org.springframework.web.bind.annotation.*;
import th.ac.mfu.doctracking.identity.ActorService;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class WorkflowQueryController {
    private final ActorService actors;
    private final WorkflowQueryService queries;
    public WorkflowQueryController(ActorService actors,WorkflowQueryService queries) { this.actors=actors;this.queries=queries; }
    @GetMapping("/stations/{id}/unit-worklist")
    public Object unitWorklist(@PathVariable UUID id,@RequestParam(required=false) UUID cursor,@RequestParam(defaultValue="25") int limit) {
        return queries.unitWorklist(actors.current(),id,cursor,limit);
    }
    @GetMapping("/courier/legs") public Object legs() { return queries.legs(actors.current()); }
}
