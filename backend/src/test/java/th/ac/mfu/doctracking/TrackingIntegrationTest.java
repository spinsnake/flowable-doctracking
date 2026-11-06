package th.ac.mfu.doctracking;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import th.ac.mfu.doctracking.common.*;
import th.ac.mfu.doctracking.domain.*;
import th.ac.mfu.doctracking.identity.Actor;
import th.ac.mfu.doctracking.identity.LoginService;
import jakarta.servlet.http.Cookie;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class TrackingIntegrationTest extends DatabaseTest {
    @Autowired JdbcTemplate db;
    @Autowired RegistryService registry;
    @Autowired ReservationService reservations;
    @Autowired EvidenceService evidence;
    @Autowired CommandService commands;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager manager;
    @Autowired MockMvc mvc;
    @Autowired LoginService login;
    @MockitoSpyBean PrivatePhotoStorage storage;

    record Fixture(Actor origin,Actor courier,Actor competitor,UUID unit,UUID station,UUID other,UUID template) {}
    Actor user(UUID unit,UUID station,boolean courier) {
        UUID id=UUID.randomUUID();
        db.update("insert into users(id,email,display_name) values(?,?,?)",id,id+"@mfu.ac.th","Test User");
        List<Actor.Membership> memberships=unit==null?List.of():List.of(new Actor.Membership(unit,"STAFF"));
        if(unit!=null) db.update("insert into unit_memberships(user_id,unit_id,role) values(?,?,'STAFF')",id,unit);
        if(station!=null) db.update("insert into user_station_permissions(user_id,station_id) values(?,?)",id,station);
        if(courier) db.update("insert into user_capabilities(user_id,capability) values(?,'COURIER')",id);
        return new Actor(id,id+"@mfu.ac.th","Test User",false,memberships,station==null?Set.of():Set.of(station),courier?Set.of("COURIER"):Set.of(),0);
    }
    Fixture fixture() {
        UUID unit=UUID.randomUUID(), station=UUID.randomUUID(), other=UUID.randomUUID(), otherUnit=UUID.randomUUID();
        for(UUID u:List.of(unit,otherUnit)) db.update("insert into units(id,code,name,is_active) values(?,?,?,true)",u,u.toString(),"Test Unit");
        db.update("insert into stations(id,unit_id,code,name) values(?,?,?,?)",station,unit,station.toString(),"Origin");
        db.update("insert into stations(id,unit_id,code,name) values(?,?,?,?)",other,otherUnit,other.toString(),"Destination");
        Actor origin=user(unit,null,false),courier=user(null,station,true),competitor=user(null,station,true);
        var input=new RegistryService.TemplateInput("A B A",unit,List.of(station,other,station),null);
        var result=commands.execute(origin,"template:create",key(),input,()->registry.createTemplate(origin,input));
        return new Fixture(origin,courier,competitor,unit,station,other,id(result,"id"));
    }
    String key() { return UUID.randomUUID().toString(); }
    @Test void capturedActorCannotWriteOrReplayAfterRevocation() {
        Fixture f=fixture();String key=key();var input=new RegistryService.Registration("Before revocation",null,f.station,f.template,1);
        commands.execute(f.origin,"document:register",key,input,()->registry.register(f.origin,input));
        db.update("update users set session_version=session_version+1 where id=?",f.origin.id());
        assertThatThrownBy(()->commands.execute(f.origin,"document:register",key,input,()->registry.register(f.origin,input)))
                .isInstanceOf(ApiException.class).satisfies(e->assertThat(((ApiException)e).status()).isEqualTo(401));
        assertThatThrownBy(()->register(f)).isInstanceOf(ApiException.class);
        assertThat(db.queryForObject("select count(*) from documents where origin_unit_id=?",Long.class,f.unit)).isEqualTo(1);
    }
    UUID id(CommandResult result,String field) { return UUID.fromString(((Map<?,?>)result.body()).get(field).toString()); }
    UUID register(Fixture f) {
        var input=new RegistryService.Registration("Book "+UUID.randomUUID(),null,f.station,f.template,1);
        var result=commands.execute(f.origin,"document:register",key(),input,()->registry.register(f.origin,input));
        return UUID.fromString(((List<?>)((Map<?,?>)result.body()).get("document_ids")).getFirst().toString());
    }
    void readyFixture(UUID document) {
        // P1 does not apply BPMN. Only this fixture creates P2-synchronized/ready state for reservation tests.
        db.update("update documents set business_state='READY_FOR_PICKUP',process_sync_status='APPLIED' where id=?",document);
    }
    ReservationService.Reserve request(Fixture f,UUID document) {
        UUID step=db.queryForObject("select current_route_step_id from documents where id=?",UUID.class,document);
        long version=db.queryForObject("select version from documents where id=?",Long.class,document);
        return new ReservationService.Reserve(f.station,List.of(new ReservationService.Item(document,step,version)));
    }
    CommandResult reserve(Actor actor,ReservationService.Reserve request,String key) {
        return commands.execute(actor,"reservation:create",key,request,()->reservations.reserve(actor,request));
    }
    @Test void registrationSnapshotIsImmutableAndPendingOutboxIsAtomic() {
        Fixture f=fixture();UUID doc=register(f);
        var detail=registry.detail(f.origin,doc);
        assertThat(detail).containsEntry("tracking_mode","MODEL_B").containsEntry("business_state","WAIT_ORIGIN_READY").containsEntry("process_sync_status","PENDING");
        var steps=(List<?>)detail.get("route_snapshot");assertThat(steps).hasSize(3);
        assertThat(((Map<?,?>)steps.getFirst()).get("step_id")).isNotEqualTo(((Map<?,?>)steps.getLast()).get("step_id"));
        assertThat(((Map<?,?>)steps.getFirst()).get("sequence")).isEqualTo(0);
        assertThat(db.queryForObject("select count(*) from workflow_outbox where document_id=? and status='PENDING'",Integer.class,doc)).isEqualTo(1);
        assertThatThrownBy(()->reserve(f.courier,request(f,doc),key())).isInstanceOf(ApiException.class).satisfies(e->assertThat(((ApiException)e).code()).isEqualTo("WORKFLOW_SYNC_PENDING"));
        var revision=new RegistryService.TemplateInput("Changed A A",f.unit,List.of(f.station,f.station),1);
        commands.execute(f.origin,"template:"+f.template,key(),revision,()->registry.reviseTemplate(f.origin,f.template,revision));
        assertThat((List<?>)registry.detail(f.origin,doc).get("route_snapshot")).hasSize(3);
        assertThatThrownBy(()->db.update("update documents set tracking_mode='MODEL_A' where id=?",doc)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->db.update("update route_steps set sequence=9 where document_id=? and sequence=1",doc)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->db.update("delete from document_events where document_id=?",doc)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void invalidOrWhitespaceRegistrationLeavesNoEvents() {
        Fixture f=fixture();int before=db.queryForObject("select count(*) from documents where origin_unit_id=?",Integer.class,f.unit);
        var input=new RegistryService.Registration("   ","\t",f.station,f.template,1);
        assertThatThrownBy(()->commands.execute(f.origin,"document:register",key(),input,()->registry.register(f.origin,input))).isInstanceOf(ApiException.class);
        assertThat(db.queryForObject("select count(*) from documents where origin_unit_id=?",Integer.class,f.unit)).isEqualTo(before);
    }
    @Test void failureAfterOutboxInsertRollsBackDocumentSnapshotLedgerAndReceipt() {
        Fixture f=fixture();String key=key();var input=new RegistryService.Registration("Atomic failure",null,f.station,f.template,1);
        assertThatThrownBy(()->commands.execute(f.origin,"document:register",key,input,()->{
            registry.register(f.origin,input);throw new IllegalStateException("Injected crash before command receipt");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("select count(*) from documents where origin_unit_id=?",Integer.class,f.unit)).isZero();
        assertThat(db.queryForObject("select count(*) from command_receipts where actor_id=? and key=?",Integer.class,f.origin.id(),key)).isZero();
        assertThat(commands.execute(f.origin,"document:register",key,input,()->registry.register(f.origin,input)).status()).isEqualTo(202);
    }
    @Test void identicalRetryReturnsOriginalAndChangedPayloadConflicts() throws Exception {
        Fixture f=fixture();String key=key();var input=new RegistryService.Registration("Retry",null,f.station,f.template,1);
        var first=commands.execute(f.origin,"document:register",key,input,()->registry.register(f.origin,input));
        var replay=commands.execute(f.origin,"document:register",key,input,()->{throw new AssertionError("Replay must not execute work");});
        assertThat(json.readTree(json.writeValueAsString(first.body()))).isEqualTo(json.readTree(json.writeValueAsString(replay.body())));
        var changed=new RegistryService.Registration("Different",null,f.station,f.template,1);
        assertThatThrownBy(()->commands.execute(f.origin,"document:register",key,changed,()->registry.register(f.origin,changed))).isInstanceOf(ApiException.class).satisfies(e->assertThat(((ApiException)e).status()).isEqualTo(409));
        assertThat(db.queryForObject("select count(*) from documents where origin_unit_id=?",Integer.class,f.unit)).isEqualTo(1);
    }
    @Test void competingCouriersProduceOneReservationWithoutChangingCustody() throws Exception {
        Fixture f=fixture();UUID doc=register(f);readyFixture(doc);var input=request(f,doc);
        var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> futures=new ArrayList<>();
            for(Actor actor:List.of(f.courier,f.competitor)) futures.add(executor.submit(()->{start.await();try{return reserve(actor,input,key()).status();}catch(ApiException e){return e.status();}}));
            start.countDown();var statuses=new ArrayList<Integer>();for(var future:futures) statuses.add(future.get(20,TimeUnit.SECONDS));
            assertThat(statuses).containsExactlyInAnyOrder(201,409);
        }
        assertThat(db.queryForObject("select count(*) from reservation_items where document_id=? and is_active",Integer.class,doc)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from courier_legs where document_id=?",Integer.class,doc)).isZero();
        assertThat(db.queryForObject("select count(*) from workflow_outbox where document_id=?",Integer.class,doc)).isEqualTo(1);
        assertThat(db.queryForObject("select business_state from documents where id=?",String.class,doc)).isEqualTo("PICKUP_RESERVED");
    }
    @Test void ownerCancelAndExpiryRestorePreviousStateButNeverExpireInTransit() {
        Fixture f=fixture();UUID doc=register(f);readyFixture(doc);
        UUID reservation=id(reserve(f.courier,request(f,doc),key()),"id");
        assertThatThrownBy(()->reservations.authorizeCancel(f.competitor,reservation,"Other" )).isInstanceOf(ApiException.class);
        commands.execute(f.courier,"cancel:"+reservation,key(),Map.of(),()->reservations.cancel(f.courier,reservation,0,null));
        assertThat(db.queryForObject("select business_state from documents where id=?",String.class,doc)).isEqualTo("READY_FOR_PICKUP");
        UUID expiring=id(reserve(f.competitor,request(f,doc),key()),"id");
        db.update("update reservations set expires_at=now()-interval '1 minute' where id=?",expiring);assertThat(reservations.expire()).isGreaterThanOrEqualTo(1);
        assertThat(db.queryForObject("select status from reservations where id=?",String.class,expiring)).isEqualTo("EXPIRED");
        UUID active=id(reserve(f.courier,request(f,doc),key()),"id");
        db.update("update reservations set expires_at=now()-interval '1 minute' where id=?",active);
        db.update("update documents set business_state='IN_TRANSIT' where id=?",doc);
        reservations.expire();assertThat(db.queryForObject("select status from reservations where id=?",String.class,active)).isEqualTo("ACTIVE");
    }
    @Test void databaseRejectsCrossDocumentStepsAndCompetingTransportTypes() {
        Fixture f=fixture();UUID first=register(f),second=register(f);readyFixture(first);
        UUID secondStep=db.queryForObject("select current_route_step_id from documents where id=?",UUID.class,second);
        assertThatThrownBy(()->new TransactionTemplate(manager).execute(status->{
            db.update("update documents set current_route_step_id=? where id=?",secondStep,first);return null;
        })).isInstanceOf(org.springframework.dao.DataAccessException.class);
        reserve(f.courier,request(f,first),key());
        var steps=db.queryForList("select id from route_steps where document_id=? order by sequence",UUID.class,first);
        assertThatThrownBy(()->db.update("insert into courier_legs(id,document_id,courier_user_id,from_step_id,to_step_id) values(?,?,?,?,?)",UUID.randomUUID(),first,f.courier.id(),steps.get(0),steps.get(1))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->db.update("insert into direct_transfers(id,document_id,dispatched_by,from_step_id,to_step_id) values(?,?,?,?,?)",UUID.randomUUID(),first,f.origin.id(),steps.get(0),steps.get(1))).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void operationalCancellationRequiresExplicitGrantStationAndReason() {
        Fixture f=fixture();UUID doc=register(f);readyFixture(doc);UUID reservation=id(reserve(f.courier,request(f,doc),key()),"id");
        Actor operator=new Actor(f.competitor.id(),f.competitor.email(),"Resolver",false,List.of(),Set.of(f.station),Set.of("RESERVATION_RESOLVE"),0);
        assertThatThrownBy(()->reservations.authorizeCancel(operator,reservation,"  ")).isInstanceOf(ApiException.class).satisfies(e->assertThat(((ApiException)e).status()).isEqualTo(422));
        commands.execute(operator,"resolve-reservation:"+reservation,key(),Map.of("reason","Courier unavailable"),()->reservations.cancel(operator,reservation,0,"Courier unavailable"));
        assertThat(db.queryForObject("select status from reservations where id=?",String.class,reservation)).isEqualTo("CANCELLED");
        assertThat(db.queryForObject("select payload->>'reason' from document_events where document_id=? and event_type='PICKUP_RESERVATION_CANCELLED'",String.class,doc)).isEqualTo("Courier unavailable");
    }
    @Test void unrelatedOriginAndAdminCannotReadDetailTimelineOrEvidence() {
        Fixture f=fixture();UUID doc=register(f);Actor outsider=user(null,f.other,true);
        assertThatThrownBy(()->registry.detail(outsider,doc)).isInstanceOf(ApiException.class).satisfies(e->assertThat(((ApiException)e).status()).isEqualTo(404));
        assertThatThrownBy(()->registry.timeline(outsider,doc,0,25)).isInstanceOf(ApiException.class);
        var admin=new Actor(outsider.id(),outsider.email(),"Admin",true,List.of(),Set.of(),Set.of(),0);
        assertThatThrownBy(()->registry.detail(admin,doc)).isInstanceOf(ApiException.class);
        assertThat((List<?>)registry.documents(f.origin,"origin",null,25).get("items")).hasSize(1);
        readyFixture(doc);var work=(List<?>)registry.worklist(f.courier,f.station,null,25).get("items");
        assertThat(work).hasSize(1);assertThat(((Map<?,?>)work.getFirst()).get("title")).isNull();
        assertThatThrownBy(()->registry.worklist(outsider,f.station,null,25)).isInstanceOf(ApiException.class);
    }
    byte[] png() throws IOException {var output=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",output);return output.toByteArray();}
    UUID upload(Fixture f,UUID document,byte[] bytes) {
        var input=new EvidenceService.Upload(document,null,"image/png",(long)bytes.length,EvidenceService.checksum(bytes));
        return id(commands.execute(f.origin,"evidence:create",key(),input,()->evidence.create(f.origin,input)),"id");
    }
    @Test void photosRequireOwnerIntegrityDecodeAndFinalizeBeforeLinking() throws Exception {
        Fixture f=fixture();UUID doc=register(f);byte[] bytes=png();UUID upload=upload(f,doc,bytes);
        assertThatThrownBy(()->evidence.requireFinalized(f.origin,List.of(upload),doc,f.station)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->evidence.content(f.origin,upload,"image/png",new ByteArrayInputStream(new byte[]{1}))).isInstanceOf(ApiException.class);
        evidence.content(f.origin,upload,"image/png",new ByteArrayInputStream(bytes));
        assertThatThrownBy(()->evidence.authorizeOwner(f.competitor,upload)).isInstanceOf(ApiException.class);
        assertThat(commands.execute(f.origin,"evidence:finalize:"+upload,key(),Map.of(),()->evidence.finalizeUpload(f.origin,upload)).status()).isEqualTo(200);
        new TransactionTemplate(manager).execute(status->{evidence.requireFinalized(f.origin,List.of(upload),doc,f.station);return null;});
        var attachment=new RegistryService.EvidenceAttachment(0L,List.of(upload));
        commands.execute(f.origin,"attach",key(),attachment,()->registry.attachEvidence(f.origin,doc,attachment));
        var timeline=(List<?>)registry.timeline(f.origin,doc,0,25).get("items");
        assertThat(((List<?>)((Map<?,?>)timeline.getLast()).get("evidence_ids")).getFirst()).isEqualTo(upload);
        assertThat(evidence.download(f.origin,upload).bytes()).isEqualTo(bytes);
        assertThatThrownBy(()->evidence.download(f.competitor,upload)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->evidence.content(f.origin,upload,"image/png",new ByteArrayInputStream(bytes))).isInstanceOf(ApiException.class);
        byte[] invalid="not an image".getBytes();UUID invalidId=upload(f,doc,invalid);
        evidence.content(f.origin,invalidId,"image/png",new ByteArrayInputStream(invalid));
        assertThatThrownBy(()->commands.execute(f.origin,"finalize",key(),Map.of(),()->evidence.finalizeUpload(f.origin,invalidId))).isInstanceOf(ApiException.class);
        assertThat(db.queryForObject("select status from evidence_uploads where id=?",String.class,invalidId)).isEqualTo("STORED");
    }
    @Test void storageFailureRollsBackUploadStatusAndProducesNoBusinessEvent() throws Exception {
        Fixture f=fixture();UUID doc=register(f);byte[] bytes=png();UUID upload=upload(f,doc,bytes);
        doThrow(new ApiException(503,"EVIDENCE_STORAGE_UNAVAILABLE","Injected outage")).when(storage).put(anyString(),any(byte[].class),anyString());
        try {assertThatThrownBy(()->evidence.content(f.origin,upload,"image/png",new ByteArrayInputStream(bytes))).isInstanceOf(ApiException.class);}
        finally {doCallRealMethod().when(storage).put(anyString(),any(byte[].class),anyString());}
        assertThat(db.queryForObject("select status from evidence_uploads where id=?",String.class,upload)).isEqualTo("INITIATED");
        assertThat(db.queryForObject("select count(*) from document_events where document_id=?",Integer.class,doc)).isEqualTo(1);
    }
    Cookie session(Actor actor) {
        return IdentityIntegrationTest.cookie(login.finalizeLogin(IdentityIntegrationTest.google(actor.email(),actor.id().toString(),true,"mfu.ac.th")));
    }
    @Test void httpGuardsCsrfScopesAndActualAuthenticatedPhotoAccess() throws Exception {
        Fixture f=fixture();UUID doc=register(f);byte[] bytes=png();UUID upload=upload(f,doc,bytes);
        evidence.content(f.origin,upload,"image/png",new ByteArrayInputStream(bytes));
        commands.execute(f.origin,"finalize",key(),Map.of(),()->evidence.finalizeUpload(f.origin,upload));
        mvc.perform(get("/api/v1/documents/"+doc)).andExpect(status().isUnauthorized());
        Cookie courier=session(f.courier),competitor=session(f.competitor),origin=session(f.origin);
        mvc.perform(get("/api/v1/documents/"+doc).cookie(competitor)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/courier/reservations").cookie(courier).contentType("application/json").header("Idempotency-Key",key()).content(json.writeValueAsString(request(f,doc)))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/evidence/"+upload+"/content").cookie(origin)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store")).andExpect(content().bytes(bytes));
        mvc.perform(get("/api/v1/evidence/"+upload+"/content").cookie(competitor)).andExpect(status().isNotFound());
        var csrfResult=mvc.perform(get("/api/v1/auth/csrf").cookie(origin)).andExpect(status().isOk()).andReturn();
        String token=json.readTree(csrfResult.getResponse().getContentAsString()).get("token").asText();
        mvc.perform(post("/api/v1/documents").cookie(origin).header("X-CSRF-TOKEN",token).header("Idempotency-Key",key()).contentType("application/json").content(json.writeValueAsString(new RegistryService.Registration("HTTP",null,f.station,f.template,1)))).andExpect(status().isAccepted()).andExpect(jsonPath("$.process_sync_status").value("PENDING"));
    }
}
