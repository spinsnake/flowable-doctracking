package th.ac.mfu.doctracking.domain;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import th.ac.mfu.doctracking.common.*;
import th.ac.mfu.doctracking.identity.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class WorkflowCommandController {
    private final ActorService actors;
    private final CommandService commands;
    private final WorkflowCommandService workflow;
    public WorkflowCommandController(ActorService actors,CommandService commands,WorkflowCommandService workflow) { this.actors=actors;this.commands=commands;this.workflow=workflow; }
    private ResponseEntity<?> response(CommandResult result) {
        var response=ResponseEntity.status(result.status());
        if(result.body() instanceof Map<?,?> body && body.get("operation_id")!=null) response.header("Location","/api/v1/operations/"+body.get("operation_id"));
        return response.body(result.body());
    }
    @PostMapping("/documents/{id}/ready")
    public Object ready(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody WorkflowCommandService.Step input) {
        Actor actor=actors.current();workflow.authorizeReady(actor,id,input);
        return response(commands.execute(actor,"document:ready:"+id,key,input,()->workflow.ready(actor,id,input)));
    }
    @PostMapping("/documents/{id}/confirm-return")
    public Object confirmReturn(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody WorkflowCommandService.Step input) {
        Actor actor=actors.current();workflow.authorizeReturn(actor,id,input);
        return response(commands.execute(actor,"document:return:"+id,key,input,()->workflow.confirmReturn(actor,id,input)));
    }
    @PostMapping("/stations/{id}/confirm-receipt")
    public Object receipt(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody WorkflowCommandService.Batch input) {
        Actor actor=actors.current();workflow.authorizeReceipt(actor,id,input);
        return response(commands.execute(actor,"station:receipt:"+id,key,input,()->workflow.confirmReceipt(actor,id,input)));
    }
    @PostMapping("/courier/check-receive")
    public Object pickup(@RequestHeader("Idempotency-Key") String key,@RequestBody WorkflowCommandService.Pickup input) {
        Actor actor=actors.current();workflow.authorizePickup(actor,input);
        return response(commands.execute(actor,"courier:pickup",key,input,()->workflow.pickup(actor,input)));
    }
    @PostMapping("/courier/check-dropoff")
    public Object dropoff(@RequestHeader("Idempotency-Key") String key,@RequestBody WorkflowCommandService.Dropoff input) {
        Actor actor=actors.current();workflow.authorizeDropoff(actor,input);
        return response(commands.execute(actor,"courier:dropoff",key,input,()->workflow.dropoff(actor,input)));
    }
}
