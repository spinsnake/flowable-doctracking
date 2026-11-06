package th.ac.mfu.doctracking.domain;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import th.ac.mfu.doctracking.common.*;
import th.ac.mfu.doctracking.identity.*;

import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class TrackingController {
    private final ActorService actors;
    private final CommandService commands;
    private final RegistryService registry;
    private final ReservationService reservations;
    private final EvidenceService evidence;
    private final TrackingStore s;
    public TrackingController(ActorService actors,CommandService commands,RegistryService registry,ReservationService reservations,EvidenceService evidence,TrackingStore s) {
        this.actors=actors;this.commands=commands;this.registry=registry;this.reservations=reservations;this.evidence=evidence;this.s=s;
    }
    private ResponseEntity<?> response(CommandResult result) {
        var response=ResponseEntity.status(result.status());
        if(result.status()==202 && result.body() instanceof Map<?,?> body && body.get("operation_id")!=null)
            response.header("Location","/api/v1/operations/"+body.get("operation_id"));
        return response.body(result.body());
    }
    @GetMapping("/stations")
    public Object stations() {
        actors.current();
        return Map.of("items",s.db.queryForList("select s.id,s.unit_id,s.code,s.name from stations s join units u on u.id=s.unit_id where s.is_active and u.is_active order by s.name,s.id"));
    }
    @GetMapping("/route-templates") public Object templates() { return registry.templates(actors.current()); }
    @PostMapping("/route-templates")
    public Object createTemplate(@RequestHeader("Idempotency-Key") String key,@RequestBody RegistryService.TemplateInput input) {
        Actor actor=actors.current();s.origin(actor,input.origin_unit_id());
        return response(commands.execute(actor,"template:create",key,input,()->registry.createTemplate(actor,input)));
    }
    @PutMapping("/route-templates/{id}")
    public Object reviseTemplate(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody RegistryService.TemplateInput input) {
        Actor actor=actors.current();s.origin(actor,TrackingStore.id(s.one("select origin_unit_id from route_templates where id=?",id),"origin_unit_id"));s.origin(actor,input.origin_unit_id());
        return response(commands.execute(actor,"template:"+id,key,input,()->registry.reviseTemplate(actor,id,input)));
    }
    @GetMapping("/documents")
    public Object documents(@RequestParam String scope,@RequestParam(required=false) UUID cursor,@RequestParam(defaultValue="25") int limit) { return registry.documents(actors.current(),scope,cursor,limit); }
    @PostMapping("/documents")
    public Object register(@RequestHeader("Idempotency-Key") String key,@RequestBody RegistryService.Registration input) {
        Actor actor=actors.current();
        var template=s.one("select origin_unit_id from route_templates where id=?",input.template_id());s.origin(actor,TrackingStore.id(template,"origin_unit_id"));
        return response(commands.execute(actor,"document:register",key,input,()->registry.register(actor,input)));
    }
    @GetMapping("/documents/{id}") public Object document(@PathVariable UUID id) { return registry.detail(actors.current(),id); }
    @PostMapping("/documents/{id}/evidence")
    public Object attachEvidence(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody RegistryService.EvidenceAttachment input) {
        Actor actor=actors.current();s.visible(actor,s.document(id,false));
        return response(commands.execute(actor,"document:evidence:"+id,key,input,()->registry.attachEvidence(actor,id,input)));
    }
    @GetMapping("/documents/{id}/timeline")
    public Object timeline(@PathVariable UUID id,@RequestParam(defaultValue="0") long cursor,@RequestParam(defaultValue="25") int limit) { return registry.timeline(actors.current(),id,cursor,limit); }
    @GetMapping("/stations/{id}/worklist")
    public Object worklist(@PathVariable UUID id,@RequestParam(required=false) UUID cursor,@RequestParam(defaultValue="25") int limit) { return registry.worklist(actors.current(),id,cursor,limit); }
    @GetMapping("/courier/reservations") public Object reservations() { return reservations.list(actors.current()); }
    @PostMapping("/courier/reservations")
    public Object reserve(@RequestHeader("Idempotency-Key") String key,@RequestBody ReservationService.Reserve input) {
        Actor actor=actors.current();reservations.authorizeStation(actor,input.station_id());
        return response(commands.execute(actor,"reservation:create",key,input,()->reservations.reserve(actor,input)));
    }
    @DeleteMapping("/courier/reservations/{id}")
    public Object cancel(@PathVariable UUID id,@RequestParam long expected_version,@RequestHeader("Idempotency-Key") String key,@RequestBody(required=false) ReservationService.Cancel input) {
        Actor actor=actors.current();String reason=input==null?null:input.reason();reservations.authorizeCancel(actor,id,reason);
        var payload=new LinkedHashMap<String,Object>();payload.put("expected_version",expected_version);payload.put("reason",reason);
        return response(commands.execute(actor,"reservation:cancel:"+id,key,payload,()->reservations.cancel(actor,id,expected_version,reason)));
    }
    @GetMapping("/operations/{id}") public Object operation(@PathVariable UUID id) { return registry.operation(actors.current(),id); }
    @PostMapping("/evidence/uploads")
    public Object upload(@RequestHeader("Idempotency-Key") String key,@RequestBody EvidenceService.Upload input) {
        Actor actor=actors.current();evidence.authorizeContext(actor,input);
        return response(commands.execute(actor,"evidence:create",key,input,()->evidence.create(actor,input)));
    }
    @PutMapping("/evidence/uploads/{id}/content")
    public Object content(@PathVariable UUID id,HttpServletRequest request) throws IOException {
        return evidence.content(actors.current(),id,request.getContentType(),request.getInputStream());
    }
    @PostMapping("/evidence/uploads/{id}/finalize")
    public Object finalizeUpload(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key) {
        Actor actor=actors.current();evidence.authorizeOwner(actor,id);
        return response(commands.execute(actor,"evidence:finalize:"+id,key,Map.of("id",id),()->evidence.finalizeUpload(actor,id)));
    }
    @GetMapping("/evidence/{id}/content")
    public ResponseEntity<byte[]> download(@PathVariable UUID id) {
        var download=evidence.download(actors.current(),id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(download.contentType()))
                .header("Content-Disposition","attachment; filename=\""+id+("image/png".equals(download.contentType())?".png":".jpg")+"\"")
                .header("Cache-Control","private, no-store").header("X-Content-Type-Options","nosniff").body(download.bytes());
    }
}
