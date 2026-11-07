package th.ac.mfu.doctracking.domain;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import th.ac.mfu.doctracking.common.CommandService;
import th.ac.mfu.doctracking.identity.ActorService;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents")
public class ObservationController {
    private final ActorService actors;
    private final CommandService commands;
    private final ObservationService observations;
    public ObservationController(ActorService actors,CommandService commands,ObservationService observations) {
        this.actors=actors;this.commands=commands;this.observations=observations;
    }
    @PostMapping("/{id}/observations")
    public Object observe(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody ObservationService.Observation input) {
        var actor=actors.current();observations.authorize(actor,id,input);
        var result=commands.execute(actor,"document:observe:"+id,key,input,()->observations.observe(actor,id,input));
        return ResponseEntity.status(result.status()).body(result.body());
    }
}
