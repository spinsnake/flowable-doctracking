package th.ac.mfu.doctracking;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import th.ac.mfu.doctracking.common.*;
import th.ac.mfu.doctracking.domain.*;
import th.ac.mfu.doctracking.identity.Actor;
import th.ac.mfu.doctracking.identity.LoginService;
import th.ac.mfu.doctracking.workflow.WorkflowOutboxWorker;
import jakarta.servlet.http.Cookie;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class WorkflowCommandsIntegrationTest extends DatabaseTest {
    @Autowired JdbcTemplate db;
    @Autowired RegistryService registry;
    @Autowired ReservationService reservations;
    @Autowired EvidenceService evidence;
    @Autowired WorkflowCommandService workflow;
    @Autowired WorkflowOutboxWorker worker;
    @Autowired CommandService commands;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @Autowired LoginService login;

    record Fixture(Actor origin,Actor courier,List<Actor> staff,List<UUID> stations,UUID template,UUID document,List<UUID> steps) {}
    String key() { return UUID.randomUUID().toString(); }
    UUID resultId(CommandResult result,String field) { return UUID.fromString(((Map<?,?>)result.body()).get(field).toString()); }
    Actor user(UUID unit,Set<UUID> stations,boolean courier) {
        UUID id=UUID.randomUUID();String email=id+"@mfu.ac.th";
        db.update("insert into users(id,email,display_name) values(?,?,?)",id,email,"Workflow Test");
        if(unit!=null) db.update("insert into unit_memberships(user_id,unit_id,role) values(?,?,'STAFF')",id,unit);
        for(UUID station:stations) db.update("insert into user_station_permissions(user_id,station_id) values(?,?)",id,station);
        if(courier) db.update("insert into user_capabilities(user_id,capability) values(?,'COURIER')",id);
        return new Actor(id,email,"Workflow Test",false,unit==null?List.of():List.of(new Actor.Membership(unit,"STAFF")),stations,courier?Set.of("COURIER"):Set.of(),0);
    }
    Fixture fixture(String mode) {
        var stations=new ArrayList<UUID>();var staff=new ArrayList<Actor>();var units=new ArrayList<UUID>();
        for(int i=0;i<3;i++) {
            UUID unit=UUID.randomUUID(),station=UUID.randomUUID();units.add(unit);stations.add(station);
            db.update("insert into units(id,code,name,is_active) values(?,?,?,true)",unit,key(),"Workflow Unit");
            db.update("insert into stations(id,unit_id,code,name) values(?,?,?,?)",station,unit,key(),"Station "+i);
            staff.add(user(unit,Set.of(station),false));
        }
        // Origin membership is sufficient for first ready and final return; no station grant.
        Actor origin=user(units.getFirst(),Set.of(),false),courier=user(null,Set.copyOf(stations),true);
        var route=List.of(stations.get(0),stations.get(1),stations.get(2),stations.get(0));
        var templateInput=new RegistryService.TemplateInput("A B C A",units.getFirst(),route,null);
        UUID template=resultId(commands.execute(origin,"template:create",key(),templateInput,()->registry.createTemplate(origin,templateInput)),"id");
        db.update("update workflow_policy set default_tracking_mode=? where id=true",mode);
        var registration=new RegistryService.Registration("Headless "+mode,null,stations.getFirst(),template,1);
        CommandResult result;
        try { result=commands.execute(origin,"document:register",key(),registration,()->registry.register(origin,registration)); }
        finally { db.update("update workflow_policy set default_tracking_mode='MODEL_B' where id=true"); }
        UUID document=UUID.fromString(((List<?>)((Map<?,?>)result.body()).get("document_ids")).getFirst().toString());
        var steps=db.queryForList("select id from route_steps where document_id=? order by sequence",UUID.class,document);
        return new Fixture(origin,courier,staff,stations,template,document,steps);
    }
    long version(Fixture f) { return db.queryForObject("select version from documents where id=?",Long.class,f.document); }
    Map<String,Object> doc(Fixture f) { return db.queryForMap("select * from documents where id=?",f.document); }
    void apply(Fixture f) {
        worker.processDocument(f.document);
        assertThat(doc(f)).containsEntry("process_sync_status","APPLIED");
    }
    CommandResult ready(Fixture f,int sequence) {
        Actor actor=sequence==0?f.origin:f.staff.get(sequence);
        var input=new WorkflowCommandService.Step(f.steps.get(sequence),version(f));
        return commands.execute(actor,"document:ready:"+f.document,key(),input,()->workflow.ready(actor,f.document,input));
    }
    UUID photo(Actor actor,UUID station) throws IOException {
        var output=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",output);byte[] bytes=output.toByteArray();
        var upload=new EvidenceService.Upload(null,station,"image/png",(long)bytes.length,EvidenceService.checksum(bytes));
        UUID photo=resultId(commands.execute(actor,"evidence:create",key(),upload,()->evidence.create(actor,upload)),"id");
        evidence.content(actor,photo,"image/png",new ByteArrayInputStream(bytes));
        commands.execute(actor,"evidence:finalize:"+photo,key(),Map.of("id",photo),()->evidence.finalizeUpload(actor,photo));
        return photo;
    }
    UUID reserve(Fixture f,int sequence) {
        var input=new ReservationService.Reserve(f.stations.get(sequence),List.of(new ReservationService.Item(f.document,f.steps.get(sequence),version(f))));
        return resultId(commands.execute(f.courier,"reservation:create",key(),input,()->reservations.reserve(f.courier,input)),"id");
    }
    WorkflowCommandService.Pickup pickupInput(Fixture f,int sequence,UUID reservation,List<UUID> photos) {
        return new WorkflowCommandService.Pickup(f.stations.get(sequence),List.of(new WorkflowCommandService.Item(f.document,f.steps.get(sequence),version(f),null)),photos,reservation,true);
    }
    CommandResult pickup(Fixture f,WorkflowCommandService.Pickup input,String key) {
        workflow.authorizePickup(f.courier,input);
        return commands.execute(f.courier,"courier:pickup",key,input,()->workflow.pickup(f.courier,input));
    }
    UUID transit(Fixture f,int sequence) throws IOException {
        UUID reservation=reserve(f,sequence);var input=pickupInput(f,sequence,reservation,List.of(photo(f.courier,f.stations.get(sequence))));
        pickup(f,input,key());apply(f);
        return (UUID)doc(f).get("active_leg_id");
    }
    void drop(Fixture f,int source,UUID leg) throws IOException {
        int destination=source+1;UUID station=f.stations.get(destination%3);
        var input=new WorkflowCommandService.Dropoff(station,List.of(new WorkflowCommandService.Item(f.document,f.steps.get(destination),version(f),leg)),List.of(photo(f.courier,station)));
        workflow.authorizeDropoff(f.courier,input);
        commands.execute(f.courier,"courier:dropoff",key(),input,()->workflow.dropoff(f.courier,input));apply(f);
    }
    void receive(Fixture f,int sequence) {
        UUID station=f.stations.get(sequence);Actor actor=f.staff.get(sequence);
        var input=new WorkflowCommandService.Batch(station,List.of(new WorkflowCommandService.Item(f.document,f.steps.get(sequence),version(f),null)));
        commands.execute(actor,"station:receipt:"+station,key(),input,()->workflow.confirmReceipt(actor,station,input));apply(f);
    }
    @Test void modelBCompletesRealWorkflowOnlyAfterExactFinalOriginReceipt() throws Exception {
        Fixture f=fixture("MODEL_B");apply(f);ready(f,0);apply(f);
        for(int source=0;source<3;source++) {
            UUID leg=transit(f,source);
            assertThat(doc(f)).containsEntry("business_state","IN_TRANSIT").containsEntry("current_route_step_id",f.steps.get(source));
            drop(f,source,leg);
            assertThat(doc(f)).containsEntry("current_route_step_id",f.steps.get(source)).containsEntry("pending_receipt_step_id",f.steps.get(source+1));
            if(source<2) { receive(f,source+1);ready(f,source+1);apply(f); }
        }
        assertThat(doc(f)).containsEntry("business_state","PENDING_ORIGIN_RETURN_RECEIPT");
        var wrong=new WorkflowCommandService.Step(f.steps.getFirst(),version(f));
        assertThatThrownBy(()->commands.execute(f.origin,"document:return:"+f.document,key(),wrong,()->workflow.confirmReturn(f.origin,f.document,wrong))).isInstanceOf(ApiException.class);
        var correct=new WorkflowCommandService.Step(f.steps.getLast(),version(f));
        commands.execute(f.origin,"document:return:"+f.document,key(),correct,()->workflow.confirmReturn(f.origin,f.document,correct));apply(f);
        assertThat(doc(f)).containsEntry("business_state","COMPLETED").containsEntry("current_route_step_id",f.steps.getLast()).containsEntry("pending_receipt_step_id",null);
        assertThat(db.queryForObject("select count(*) from outstanding_work where resource_id=? and resolved_at is null",Long.class,f.document)).isZero();
        assertThat(db.queryForObject("select count(*) from workflow_instances where document_id=?",Long.class,f.document)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from document_events where document_id=? and event_type='UNIT_READY'",Long.class,f.document)).isEqualTo(3);
        assertThat(db.queryForObject("select count(*) from document_events where document_id=? and event_type='UNIT_RECEIPT_CONFIRMED'",Long.class,f.document)).isEqualTo(2);
        assertThat(db.queryForObject("select count(*) from event_evidence x join document_events e on e.id=x.event_id where e.document_id=?",Long.class,f.document)).isEqualTo(6);
    }
    @Test void modelACompletesWithCourierEvidenceWithoutSyntheticUnitEvents() throws Exception {
        Fixture f=fixture("MODEL_A");apply(f);
        assertThatThrownBy(()->ready(f,0)).isInstanceOf(ApiException.class);
        for(int source=0;source<3;source++) {
            UUID leg=transit(f,source);drop(f,source,leg);
            assertThat(doc(f)).containsEntry("current_route_step_id",f.steps.get(source+1)).containsEntry("pending_receipt_step_id",null);
        }
        assertThat(doc(f)).containsEntry("business_state","COURIER_TRACKING_COMPLETE");
        assertThat(db.queryForObject("select count(*) from document_events where document_id=? and event_type in ('UNIT_READY','UNIT_RECEIPT_CONFIRMED','ORIGIN_RETURN_CONFIRMED')",Long.class,f.document)).isZero();
        assertThat(db.queryForObject("select count(*) from outstanding_work where resource_id=? and resolved_at is null",Long.class,f.document)).isZero();
    }
    @Test void rejectsPendingSyncMissingPhotoWrongOwnerAndExpiredReservationWithoutChangingCustody() throws Exception {
        Fixture f=fixture("MODEL_B");
        assertThatThrownBy(()->ready(f,0)).isInstanceOf(ApiException.class).satisfies(e->assertThat(((ApiException)e).code()).isEqualTo("WORKFLOW_SYNC_PENDING"));
        apply(f);ready(f,0);apply(f);UUID reservation=reserve(f,0);
        var missing=pickupInput(f,0,reservation,List.of());
        assertThatThrownBy(()->pickup(f,missing,key())).isInstanceOf(ApiException.class).satisfies(e->assertThat(((ApiException)e).code()).isEqualTo("EVIDENCE_REQUIRED"));
        Actor outsider=user(null,Set.copyOf(f.stations),true);
        assertThatThrownBy(()->workflow.authorizePickup(outsider,missing)).isInstanceOf(ApiException.class).satisfies(e->assertThat(((ApiException)e).status()).isEqualTo(403));
        var valid=pickupInput(f,0,reservation,List.of(photo(f.courier,f.stations.getFirst())));
        db.update("update reservations set expires_at=now()-interval '1 second' where id=?",reservation);
        assertThatThrownBy(()->pickup(f,valid,key())).isInstanceOf(ApiException.class).satisfies(e->assertThat(((ApiException)e).code()).isEqualTo("RESERVATION_EXPIRED"));
        assertThat(doc(f)).containsEntry("business_state","PICKUP_RESERVED").containsEntry("active_leg_id",null);
        assertThat(db.queryForObject("select count(*) from courier_legs where document_id=?",Long.class,f.document)).isZero();
    }
    @Test void pickupReplayIsStableAndFailureRollsBackCustodyEvidenceEventAndOutbox() throws Exception {
        Fixture f=fixture("MODEL_B");apply(f);ready(f,0);apply(f);UUID reservation=reserve(f,0);
        var input=pickupInput(f,0,reservation,List.of(photo(f.courier,f.stations.getFirst())));long before=version(f);String idempotency=key();
        assertThatThrownBy(()->commands.execute(f.courier,"courier:pickup",idempotency,input,()->{
            workflow.pickup(f.courier,input);throw new IllegalStateException("Injected failure after queue and evidence");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(version(f)).isEqualTo(before);assertThat(doc(f)).containsEntry("active_leg_id",null).containsEntry("process_sync_status","APPLIED");
        assertThat(db.queryForObject("select status from reservations where id=?",String.class,reservation)).isEqualTo("ACTIVE");
        assertThat(db.queryForObject("select count(*) from document_events where document_id=? and event_type='COURIER_PICKED_UP'",Long.class,f.document)).isZero();
        var first=pickup(f,input,idempotency);apply(f);var replay=pickup(f,input,idempotency);
        assertThat(json.readTree(json.writeValueAsString(first.body()))).isEqualTo(json.readTree(json.writeValueAsString(replay.body())));
        assertThat(db.queryForObject("select count(*) from courier_legs where document_id=?",Long.class,f.document)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from workflow_inbox where document_id=?",Long.class,f.document)).isEqualTo(3);
    }
    @Test void intermediateUnitNeedsMembershipAndStationPermissionAndHttpRejectsAnonymousCommands() throws Exception {
        Fixture f=fixture("MODEL_B");apply(f);ready(f,0);apply(f);UUID leg=transit(f,0);
        var dropInput=new WorkflowCommandService.Dropoff(f.stations.get(1),List.of(new WorkflowCommandService.Item(f.document,f.steps.get(1),version(f),leg)),List.of());
        Actor otherCourier=user(null,Set.copyOf(f.stations),true);
        assertThatThrownBy(()->workflow.authorizeDropoff(otherCourier,dropInput)).isInstanceOf(ApiException.class).satisfies(e->assertThat(((ApiException)e).code()).isEqualTo("LEG_OWNER_REQUIRED"));
        assertThatThrownBy(()->commands.execute(f.courier,"courier:dropoff",key(),dropInput,()->workflow.dropoff(f.courier,dropInput))).isInstanceOf(ApiException.class).satisfies(e->assertThat(((ApiException)e).code()).isEqualTo("EVIDENCE_REQUIRED"));
        assertThat(doc(f)).containsEntry("business_state","IN_TRANSIT");
        drop(f,0,leg);
        UUID station=f.stations.get(1),step=f.steps.get(1);UUID unit=db.queryForObject("select unit_id from stations where id=?",UUID.class,station);
        Actor noStation=user(unit,Set.of(),false),noMembership=user(null,Set.of(station),false);
        var input=new WorkflowCommandService.Batch(station,List.of(new WorkflowCommandService.Item(f.document,step,version(f),null)));
        for(Actor denied:List.of(noStation,noMembership,f.origin)) assertThatThrownBy(()->workflow.authorizeReceipt(denied,station,input)).isInstanceOf(ApiException.class);
        var future=new WorkflowCommandService.Step(f.steps.get(2),version(f));
        assertThatThrownBy(()->commands.execute(f.staff.get(2),"document:ready:"+f.document,key(),future,()->workflow.ready(f.staff.get(2),f.document,future))).isInstanceOf(ApiException.class);
        mvc.perform(post("/api/v1/documents/"+f.document+"/ready").header("Idempotency-Key",key()).contentType("application/json").content(json.writeValueAsBytes(future))).andExpect(status().isForbidden());
        assertThat(doc(f)).containsEntry("business_state","PENDING_UNIT_RECEIPT");
    }
    @Test void httpReadyUsesRealSessionCsrfAndIdempotencyAndOperationPollingTracksSync() throws Exception {
        Fixture f=fixture("MODEL_B");apply(f);
        Cookie session=IdentityIntegrationTest.cookie(login.finalizeLogin(IdentityIntegrationTest.google(f.origin.email(),f.origin.id().toString(),true,"mfu.ac.th")));
        var input=new WorkflowCommandService.Step(f.steps.getFirst(),version(f));String key=key();String body=json.writeValueAsString(input);
        String path="/api/v1/documents/"+f.document+"/ready";
        mvc.perform(post(path).cookie(session).header("Idempotency-Key",key).contentType("application/json").content(body)).andExpect(status().isForbidden());
        var csrf=mvc.perform(get("/api/v1/auth/csrf").cookie(session)).andExpect(status().isOk()).andReturn();
        String token=json.readTree(csrf.getResponse().getContentAsString()).get("token").asText();
        var accepted=mvc.perform(post(path).cookie(session).header("X-CSRF-TOKEN",token).header("Idempotency-Key",key).contentType("application/json").content(body))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.process_sync_status").value("PENDING")).andReturn();
        String operation=json.readTree(accepted.getResponse().getContentAsString()).get("operation_id").asText();
        assertThat(accepted.getResponse().getHeader("Location")).isEqualTo("/api/v1/operations/"+operation);
        apply(f);
        mvc.perform(get("/api/v1/operations/"+operation).cookie(session)).andExpect(status().isOk()).andExpect(jsonPath("$.process_sync_status").value("APPLIED")).andExpect(jsonPath("$.updated_states[0].process_sync_status").value("APPLIED"));
        mvc.perform(post(path).cookie(session).header("X-CSRF-TOKEN",token).header("Idempotency-Key",key).contentType("application/json").content(body))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.operation_id").value(operation));
        assertThat(db.queryForObject("select count(*) from document_events where document_id=? and event_type='UNIT_READY'",Long.class,f.document)).isEqualTo(1);
    }
}
