package th.ac.mfu.doctracking;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import th.ac.mfu.doctracking.common.*;
import th.ac.mfu.doctracking.domain.*;
import th.ac.mfu.doctracking.identity.*;
import th.ac.mfu.doctracking.workflow.WorkflowOutboxWorker;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class WorkflowQueriesIntegrationTest extends DatabaseTest {
    @Autowired JdbcTemplate db;
    @Autowired RegistryService registry;
    @Autowired ReservationService reservations;
    @Autowired WorkflowQueryService queries;
    @Autowired WorkflowCommandService workflow;
    @Autowired EvidenceService evidence;
    @Autowired WorkflowOutboxWorker worker;
    @Autowired CommandService commands;
    @Autowired IdentityService identity;
    @Autowired LoginService login;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    record Fixture(Actor origin,Actor destination,Actor courier,UUID originUnit,UUID destinationUnit,UUID a,UUID b,UUID document,List<UUID> steps) {}
    String key() { return UUID.randomUUID().toString(); }
    Actor user(UUID unit,Set<UUID> stations,boolean courier,boolean admin) {
        UUID id=UUID.randomUUID();String email=id+"@mfu.ac.th";
        db.update("insert into users(id,email,display_name,is_admin) values(?,?,?,?)",id,email,"Visible Actor",admin);
        if(unit!=null) db.update("insert into unit_memberships(user_id,unit_id,role) values(?,?,'STAFF')",id,unit);
        for(UUID station:stations) db.update("insert into user_station_permissions(user_id,station_id) values(?,?)",id,station);
        if(courier) db.update("insert into user_capabilities(user_id,capability) values(?,'COURIER')",id);
        return new Actor(id,email,"Visible Actor",admin,unit==null?List.of():List.of(new Actor.Membership(unit,"STAFF")),stations,courier?Set.of("COURIER"):Set.of(),0);
    }
    CommandResult command(Actor actor,Object input,Supplier<CommandResult> work) { return commands.execute(actor,"query-test-command",key(),input,work); }
    UUID resultId(CommandResult result,String field) { return UUID.fromString(((Map<?,?>)result.body()).get(field).toString()); }
    Fixture fixture() {
        UUID ou=UUID.randomUUID(),du=UUID.randomUUID(),a=UUID.randomUUID(),b=UUID.randomUUID();
        for(UUID unit:List.of(ou,du)) db.update("insert into units(id,code,name,is_active) values(?,?,?,true)",unit,key(),"Query Unit");
        db.update("insert into stations(id,unit_id,code,name) values(?,?,?,?)",a,ou,key(),"Origin");
        db.update("insert into stations(id,unit_id,code,name) values(?,?,?,?)",b,du,key(),"Destination");
        Actor origin=user(ou,Set.of(a),false,false),destination=user(du,Set.of(b),false,false),courier=user(null,Set.of(a,b),true,false);
        var template=new RegistryService.TemplateInput("A B A",ou,List.of(a,b,a),null);
        UUID templateId=resultId(command(origin,template,()->registry.createTemplate(origin,template)),"id");
        var registration=new RegistryService.Registration("Private title","Private number",a,templateId,1);
        var result=command(origin,registration,()->registry.register(origin,registration));
        UUID doc=UUID.fromString(((List<?>)((Map<?,?>)result.body()).get("document_ids")).getFirst().toString());
        return new Fixture(origin,destination,courier,ou,du,a,b,doc,db.queryForList("select id from route_steps where document_id=? order by sequence",UUID.class,doc));
    }
    long version(Fixture f) { return db.queryForObject("select version from documents where id=?",Long.class,f.document); }
    void apply(Fixture f) { worker.processDocument(f.document);assertThat(db.queryForObject("select process_sync_status from documents where id=?",String.class,f.document)).isEqualTo("APPLIED"); }
    @SuppressWarnings("unchecked") List<Map<String,Object>> items(Map<String,Object> response) { return (List<Map<String,Object>>)response.get("items"); }
    Map<String,Object> row(Fixture f,Actor actor,UUID station) { return items(queries.unitWorklist(actor,station,null,25)).stream().filter(r->f.document.equals(r.get("id"))).findFirst().orElseThrow(); }
    void ready(Fixture f,int sequence,Actor actor) {
        var input=new WorkflowCommandService.Step(f.steps.get(sequence),version(f));
        command(actor,input,()->workflow.ready(actor,f.document,input));
    }
    UUID reserve(Fixture f,int sequence,UUID station) {
        var input=new ReservationService.Reserve(station,List.of(new ReservationService.Item(f.document,f.steps.get(sequence),version(f))));
        return resultId(command(f.courier,input,()->reservations.reserve(f.courier,input)),"id");
    }
    UUID photo(Fixture f,UUID station) throws Exception {
        var output=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",output);byte[] bytes=output.toByteArray();
        var input=new EvidenceService.Upload(null,station,"image/png",(long)bytes.length,EvidenceService.checksum(bytes));
        UUID id=resultId(command(f.courier,input,()->evidence.create(f.courier,input)),"id");
        evidence.content(f.courier,id,"image/png",new ByteArrayInputStream(bytes));command(f.courier,Map.of("id",id),()->evidence.finalizeUpload(f.courier,id));return id;
    }
    UUID pickup(Fixture f,int source,UUID station,UUID reservation) throws Exception {
        var input=new WorkflowCommandService.Pickup(station,List.of(new WorkflowCommandService.Item(f.document,f.steps.get(source),version(f),null)),List.of(photo(f,station)),reservation,true);
        command(f.courier,input,()->workflow.pickup(f.courier,input));
        return db.queryForObject("select active_leg_id from documents where id=?",UUID.class,f.document);
    }
    void drop(Fixture f,int destination,UUID station,UUID leg) throws Exception {
        var input=new WorkflowCommandService.Dropoff(station,List.of(new WorkflowCommandService.Item(f.document,f.steps.get(destination),version(f),leg)),List.of(photo(f,station)));
        command(f.courier,input,()->workflow.dropoff(f.courier,input));
    }
    void destinationPending(Fixture f) throws Exception { apply(f);ready(f,0,f.origin);apply(f);UUID reservation=reserve(f,0,f.a);UUID leg=pickup(f,0,f.a,reservation);apply(f);drop(f,1,f.b,leg); }

    @Test void unitQueueUsesExactReceiptOccurrenceAndPendingSyncNeverOffersMutation() throws Exception {
        Fixture f=fixture();
        assertThat(row(f,f.origin,f.a)).containsEntry("action",null).containsEntry("action_route_step_id",f.steps.getFirst());
        apply(f);assertThat(row(f,f.origin,f.a)).containsEntry("action","READY");
        ready(f,0,f.origin);assertThat(row(f,f.origin,f.a)).containsEntry("action",null);apply(f);
        UUID reservation=reserve(f,0,f.a),leg=pickup(f,0,f.a,reservation);apply(f);drop(f,1,f.b,leg);
        assertThat(row(f,f.destination,f.b)).containsEntry("action",null).containsEntry("pending_receipt_step_id",f.steps.get(1));apply(f);
        assertThat(row(f,f.destination,f.b)).containsEntry("action","CONFIRM_RECEIPT").containsEntry("action_route_step_id",f.steps.get(1)).containsEntry("title",null).containsEntry("document_number",null);
        var receipt=new WorkflowCommandService.Batch(f.b,List.of(new WorkflowCommandService.Item(f.document,f.steps.get(1),version(f),null)));
        command(f.destination,receipt,()->workflow.confirmReceipt(f.destination,f.b,receipt));
        assertThat(row(f,f.destination,f.b)).containsEntry("action",null);apply(f);
        assertThat(row(f,f.destination,f.b)).containsEntry("action","READY").containsEntry("current_route_step_id",f.steps.get(1));
        ready(f,1,f.destination);apply(f);assertThat(row(f,f.destination,f.b)).containsEntry("action",null).containsEntry("business_state","READY_FOR_PICKUP");
        UUID backReservation=reserve(f,1,f.b),backLeg=pickup(f,1,f.b,backReservation);apply(f);drop(f,2,f.a,backLeg);apply(f);
        assertThat(row(f,f.origin,f.a)).containsEntry("action","CONFIRM_RETURN").containsEntry("action_route_step_id",f.steps.getLast()).containsEntry("title","Private title");
        assertThat(items(queries.unitWorklist(f.destination,f.b,null,25))).noneMatch(r->f.document.equals(r.get("id")));
        var finalReceipt=new WorkflowCommandService.Step(f.steps.getLast(),version(f));command(f.origin,finalReceipt,()->workflow.confirmReturn(f.origin,f.document,finalReceipt));apply(f);
        assertThat(items(queries.unitWorklist(f.origin,f.a,null,25))).noneMatch(r->f.document.equals(r.get("id")));
        assertThat(items(registry.timeline(f.origin,f.document,0,100))).allSatisfy(e->assertThat(e).containsEntry("actor_display_name","Visible Actor"));
    }

    @Test void unitQueueRequiresMembershipAndExplicitStationEvenForAdminAndCourier() throws Exception {
        Fixture f=fixture();destinationPending(f);apply(f);
        for(Actor denied:List.of(f.origin,f.courier,user(f.destinationUnit,Set.of(),false,false),user(null,Set.of(f.b),false,true)))
            assertThatThrownBy(()->queries.unitWorklist(denied,f.b,null,25)).isInstanceOf(ApiException.class).satisfies(e->assertThat(((ApiException)e).status()).isEqualTo(403));
        db.update("delete from user_station_permissions where user_id=?",f.destination.id());
        assertThatThrownBy(()->queries.unitWorklist(f.destination,f.b,null,25)).isInstanceOf(ApiException.class);
        db.update("insert into user_station_permissions values(?,?)",f.destination.id(),f.b);
        db.update("update units set is_active=false where id=?",f.destinationUnit);
        assertThatThrownBy(()->queries.unitWorklist(f.destination,f.b,null,25)).isInstanceOf(ApiException.class);
    }

    @Test void inactiveOriginMembershipDoesNotUnredactDestinationWorklist() throws Exception {
        Fixture f=fixture();destinationPending(f);apply(f);
        db.update("insert into unit_memberships(user_id,unit_id,role) values(?,?,'STAFF')",f.destination.id(),f.originUnit);
        assertThat(row(f,f.destination,f.b)).containsEntry("title","Private title");
        db.update("update units set is_active=false where id=?",f.originUnit);
        assertThat(row(f,f.destination,f.b)).containsEntry("title",null).containsEntry("document_number",null).containsEntry("action","CONFIRM_RECEIPT");
    }

    @Test void reservationVersionsStayCurrentAndLegsExposeOnlyOwnedScopedCustody() throws Exception {
        Fixture f=fixture();apply(f);ready(f,0,f.origin);apply(f);UUID reservation=reserve(f,0,f.a);
        var own=items(reservations.list(f.courier)).stream().filter(r->reservation.equals(r.get("id"))).findFirst().orElseThrow();
        var reserved=items(own).getFirst();long reservedVersion=((Number)reserved.get("reserved_version")).longValue();
        assertThat(reserved).containsEntry("document_version",version(f)).containsEntry("expected_version",version(f)).containsEntry("tracking_mode","MODEL_B");
        // A legitimate photo attachment increments document version while reservation version remains its own guard.
        UUID photo=photo(f,f.a);var attachment=new RegistryService.EvidenceAttachment(version(f),List.of(photo));
        // Use an origin-owned document upload for the attachment; courier photos remain custody-context evidence.
        byte[] bytes=evidence.download(f.courier,photo).bytes();
        var upload=new EvidenceService.Upload(f.document,null,"image/png",(long)bytes.length,EvidenceService.checksum(bytes));
        UUID originPhoto=resultId(command(f.origin,upload,()->evidence.create(f.origin,upload)),"id");
        evidence.content(f.origin,originPhoto,"image/png",new ByteArrayInputStream(bytes));command(f.origin,Map.of("id",originPhoto),()->evidence.finalizeUpload(f.origin,originPhoto));
        attachment=new RegistryService.EvidenceAttachment(version(f),List.of(originPhoto));var attach=attachment;
        command(f.origin,attach,()->registry.attachEvidence(f.origin,f.document,attach));
        var updated=items(items(reservations.list(f.courier)).stream().filter(r->reservation.equals(r.get("id"))).findFirst().orElseThrow()).getFirst();
        assertThat(updated).containsEntry("reserved_version",reservedVersion).containsEntry("document_version",version(f)).containsEntry("expected_version",version(f));
        assertThat(version(f)).isGreaterThan(reservedVersion);
        Actor other=user(null,Set.of(f.a,f.b),true,false);assertThat(items(reservations.list(other))).isEmpty();
        UUID leg=pickup(f,0,f.a,reservation);
        var active=items(queries.legs(f.courier)).stream().filter(r->leg.equals(r.get("id"))).findFirst().orElseThrow();
        assertThat(active).containsEntry("to_step_id",f.steps.get(1)).containsEntry("to_station_id",f.b).containsEntry("document_version",version(f)).containsEntry("process_sync_status","PENDING");
        assertThat(active).doesNotContainKeys("title","document_number","origin_unit_id","route_snapshot");
        assertThat(items(queries.legs(other))).isEmpty();
        db.update("delete from user_station_permissions where user_id=? and station_id=?",f.courier.id(),f.b);
        assertThat(items(queries.legs(f.courier))).isEmpty();
    }

    @Test void revokedReservationStationOrCapabilityCannotLeakOldOwnerQueue() {
        Fixture f=fixture();apply(f);ready(f,0,f.origin);apply(f);reserve(f,0,f.a);
        db.update("delete from user_station_permissions where user_id=? and station_id=?",f.courier.id(),f.a);
        assertThat(items(reservations.list(f.courier))).isEmpty();
        db.update("delete from user_capabilities where user_id=?",f.courier.id());
        assertThatThrownBy(()->reservations.list(f.courier)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->queries.legs(f.courier)).isInstanceOf(ApiException.class);
    }

    Cookie session(Actor actor) { return IdentityIntegrationTest.cookie(login.finalizeLogin(IdentityIntegrationTest.google(actor.email(),actor.id().toString(),true,"mfu.ac.th"))); }
    String csrf(Cookie cookie) throws Exception { return json.readTree(mvc.perform(get("/api/v1/auth/csrf").cookie(cookie)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText(); }
    void guardedPatch(Cookie admin,String token,String path,Map<String,Object> payload) throws Exception {
        mvc.perform(patch(path).cookie(admin).header("X-CSRF-TOKEN",token).header("Idempotency-Key",key()).contentType("application/json").content(json.writeValueAsBytes(payload))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OUTSTANDING_WORK"));
    }
    @Test void realDocumentReservationAndLegBlockAdministrativeRemovalAndRoleChangeRevokesSession() throws Exception {
        Fixture f=fixture();Actor admin=user(null,Set.of(),false,true);Cookie adminCookie=session(admin);String token=csrf(adminCookie);
        // The outstanding DOCUMENT row comes from registration, not a manually inserted guard fixture.
        guardedPatch(adminCookie,token,"/api/v1/units/"+f.destinationUnit,Map.of("expected_version",0,"is_active",false,"reason","Close destination"));
        apply(f);ready(f,0,f.origin);apply(f);UUID reservation=reserve(f,0,f.a);
        guardedPatch(adminCookie,token,"/api/v1/users/"+f.courier.id(),Map.of("expected_version",0,"is_active",false,"reason","Disable reserved courier"));
        guardedPatch(adminCookie,token,"/api/v1/users/"+f.courier.id()+"/grants",Map.of("expected_version",0,"station_ids",List.of(),"capabilities",List.of(),"reason","Remove courier access"));
        pickup(f,0,f.a,reservation);
        guardedPatch(adminCookie,token,"/api/v1/users/"+f.courier.id(),Map.of("expected_version",0,"is_active",false,"reason","Disable active leg owner"));
        guardedPatch(adminCookie,token,"/api/v1/units/"+f.originUnit,Map.of("expected_version",0,"is_active",false,"reason","Close active source"));
        Actor freeStaff=user(f.destinationUnit,Set.of(f.b),false,false);Cookie old=session(freeStaff);
        long userVersion=((Number)identity.user(freeStaff.id()).get("version")).longValue();
        mvc.perform(patch("/api/v1/users/"+freeStaff.id()).cookie(adminCookie).header("X-CSRF-TOKEN",token).header("Idempotency-Key",key()).contentType("application/json").content(json.writeValueAsBytes(Map.of("expected_version",userVersion,"unit_id",f.destinationUnit,"unit_role","UNIT_ADMIN","reason","Appoint administrator")))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/stations/"+f.b+"/unit-worklist").cookie(old)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/courier/legs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/stations/"+f.b+"/unit-worklist").cookie(session(f.destination))).andExpect(status().isOk());
    }
}
