package th.ac.mfu.doctracking;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.BeforeEach;
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

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ObservationIntegrationTest extends DatabaseTest {
    @Autowired JdbcTemplate db;
    @Autowired RegistryService registry;
    @Autowired ReservationService reservations;
    @Autowired EvidenceService evidence;
    @Autowired WorkflowCommandService workflow;
    @Autowired WorkflowOutboxWorker worker;
    @Autowired CommandService commands;
    @Autowired ObservationService observations;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @Autowired LoginService login;
    @Autowired TaskService tasks;
    WorkflowCommandsIntegrationTest h;
    @BeforeEach void helpers() {
        h=new WorkflowCommandsIntegrationTest();h.db=db;h.registry=registry;h.reservations=reservations;
        h.evidence=evidence;h.workflow=workflow;h.worker=worker;h.commands=commands;h.json=json;
    }
    String key() { return UUID.randomUUID().toString(); }
    String task(WorkflowCommandsIntegrationTest.Fixture f) {
        String process=db.queryForObject("select process_instance_id from workflow_instances where document_id=?",String.class,f.document());
        return tasks.createTaskQuery().processInstanceId(process).singleResult().getId();
    }
    ObservationService.Observation input(WorkflowCommandsIntegrationTest.Fixture f,int station,int sequence) throws Exception {
        UUID location=f.stations().get(station);
        return new ObservationService.Observation(h.version(f),location,sequence<0?null:f.steps().get(sequence),List.of(h.photo(f.courier(),location)),"Found physically at this station",true);
    }
    CommandResult observe(WorkflowCommandsIntegrationTest.Fixture f,ObservationService.Observation input,String key) {
        observations.authorize(f.courier(),f.document(),input);
        return commands.execute(f.courier(),"document:observe:"+f.document(),key,input,()->observations.observe(f.courier(),f.document(),input));
    }
    void rejected(WorkflowCommandsIntegrationTest.Fixture f,ObservationService.Observation input,String code) {
        assertThatThrownBy(()->observe(f,input,key())).isInstanceOf(ApiException.class).satisfies(e->assertThat(((ApiException)e).code()).isEqualTo(code));
    }
    @Test void sameOccurrenceRecordsActualObserverPhotoAndTimeWithoutAdvancingTaskOrCustody() throws Exception {
        var f=h.fixture("MODEL_A");h.apply(f);String original=task(f);long version=h.version(f);
        var input=input(f,0,0);var result=observe(f,input,key());
        assertThat(result.status()).isEqualTo(200);
        assertThat(h.doc(f)).containsEntry("business_state","AWAIT_PHYSICAL_PICKUP").containsEntry("current_route_step_id",f.steps().getFirst()).containsEntry("reported_location_step_id",f.steps().getFirst()).containsEntry("version",version+1).containsEntry("process_sync_status","APPLIED");
        assertThat(task(f)).isEqualTo(original);
        var event=db.queryForMap("select * from document_events where document_id=? and event_type='OBSERVED_AT'",f.document());
        assertThat(event).containsEntry("actor_id",f.courier().id()).containsEntry("route_step_id",f.steps().getFirst());
        assertThat(event.get("recorded_at")).isNotNull();
        assertThat(db.queryForObject("select count(*) from event_evidence where event_id=?",Long.class,event.get("id"))).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from workflow_outbox where document_id=?",Long.class,f.document())).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from tracking_exceptions where document_id=?",Long.class,f.document())).isZero();
    }
    @Test void t10ForwardObservationCreatesUnknownTransferAndCaseWhileHoldingRealPickupTask() throws Exception {
        var f=h.fixture("MODEL_A");h.apply(f);UUID leg=h.transit(f,0);h.drop(f,0,leg);
        String original=task(f);var input=input(f,2,2);var result=observe(f,input,key());
        assertThat(h.doc(f)).containsEntry("business_state","EXCEPTION").containsEntry("current_route_step_id",f.steps().get(1)).containsEntry("reported_location_step_id",f.steps().get(1)).containsEntry("active_leg_id",null).containsEntry("active_reservation_id",null);
        assertThat(task(f)).isEqualTo(original);
        assertThat(((Map<?,?>)result.body()).get("reconciliation_status")).isEqualTo("REQUIRES_REVIEW");
        var gap=db.queryForMap("select actor_id,from_step_id,to_step_id,payload::text as payload from document_events where document_id=? and event_type='UNOBSERVED_TRANSFER'",f.document());
        assertThat(gap).containsEntry("actor_id",null).containsEntry("from_step_id",f.steps().get(1)).containsEntry("to_step_id",f.steps().get(2));
        var missing=json.readTree(gap.get("payload").toString());
        for(String field:List.of("transfer_actor_id","transfer_started_at","transfer_arrived_at")) assertThat(missing.get(field).isNull()).isTrue();
        assertThat(missing.get("missing_transfer_evidence").asBoolean()).isTrue();
        assertThat(db.queryForObject("select previous_state from tracking_exceptions where document_id=?",String.class,f.document())).isEqualTo("DROPPED_OFF_UNCONFIRMED");
        assertThat(db.queryForObject("select count(*) from document_events where document_id=? and event_type in ('UNIT_READY','UNIT_RECEIPT_CONFIRMED','ORIGIN_RETURN_CONFIRMED')",Long.class,f.document())).isZero();
        var timeline=registry.timeline(f.origin(),f.document(),0,100);
        assertThat(json.writeValueAsString(timeline)).contains("missing_transfer_evidence","Found physically");
        assertThat(registry.worklist(f.courier(),f.stations().get(2),null,20).get("items")).asList().isEmpty();
    }
    @Test void repeatedStationRequiresExactOccurrenceAndFinalObservationCannotCompleteTracking() throws Exception {
        var f=h.fixture("MODEL_A");h.apply(f);String original=task(f);var photo=input(f,0,0);
        rejected(f,new ObservationService.Observation(h.version(f),f.stations().getFirst(),null,photo.evidence_upload_ids(),"Ambiguous A",true),"EXACT_STEP_REQUIRED");
        rejected(f,new ObservationService.Observation(h.version(f),f.stations().getFirst(),f.steps().get(1),photo.evidence_upload_ids(),"Wrong occurrence",true),"EXACT_STEP_REQUIRED");
        var finalObservation=new ObservationService.Observation(h.version(f),f.stations().getFirst(),f.steps().getLast(),photo.evidence_upload_ids(),"Found at return occurrence",true);
        observe(f,finalObservation,key());
        assertThat(h.doc(f)).containsEntry("business_state","EXCEPTION").containsEntry("current_route_step_id",f.steps().getFirst());
        assertThat(task(f)).isEqualTo(original);
        assertThat(db.queryForObject("select count(*) from courier_legs where document_id=?",Long.class,f.document())).isZero();
    }
    @Test void offRouteAndBackwardObservationsOpenCasesWithoutInventingRouteProgress() throws Exception {
        var f=h.fixture("MODEL_A");h.apply(f);UUID leg=h.transit(f,0);h.drop(f,0,leg);
        String original=task(f);observe(f,input(f,0,0),key());
        assertThat(h.doc(f)).containsEntry("business_state","EXCEPTION").containsEntry("current_route_step_id",f.steps().get(1));
        assertThat(task(f)).isEqualTo(original);
        var other=h.fixture("MODEL_A");h.apply(other);UUID unit=UUID.randomUUID(),station=UUID.randomUUID();
        db.update("insert into units(id,code,name,is_active) values(?,?,?,true)",unit,key(),"Off route unit");
        db.update("insert into stations(id,unit_id,code,name) values(?,?,?,?)",station,unit,key(),"Off route station");
        db.update("insert into user_station_permissions(user_id,station_id) values(?,?)",other.courier().id(),station);
        Set<UUID> stations=new HashSet<>(other.courier().stationIds());stations.add(station);
        var courier=new Actor(other.courier().id(),other.courier().email(),"Courier",false,List.of(),stations,Set.of("COURIER"),0);
        var input=new ObservationService.Observation(h.version(other),station,null,List.of(h.photo(courier,station)),"Physically found outside route",true);
        commands.execute(courier,"document:observe:"+other.document(),key(),input,()->observations.observe(courier,other.document(),input));
        assertThat(h.doc(other)).containsEntry("business_state","EXCEPTION").containsEntry("current_route_step_id",other.steps().getFirst());
        assertThat(db.queryForObject("select code from tracking_exceptions where document_id=?",String.class,other.document())).isEqualTo("WRONG_STATION");
        assertThat(db.queryForMap("select route_step_id,actor_id,payload::text as payload from document_events where document_id=? and event_type='OBSERVED_AT'",other.document())).containsEntry("route_step_id",null).containsEntry("actor_id",courier.id());
    }
    @Test void rollbackAndReplayAreAtomicAcrossCaseEventsEvidenceAndVersion() throws Exception {
        var f=h.fixture("MODEL_A");h.apply(f);var input=input(f,2,2);String key=key();long version=h.version(f);String original=task(f);
        assertThatThrownBy(()->commands.execute(f.courier(),"document:observe:"+f.document(),key,input,()->{
            observations.observe(f.courier(),f.document(),input);throw new IllegalStateException("Injected observation transaction rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(h.version(f)).isEqualTo(version);
        assertThat(db.queryForObject("select count(*) from tracking_exceptions where document_id=?",Long.class,f.document())).isZero();
        assertThat(db.queryForObject("select count(*) from event_evidence x join document_events e on e.id=x.event_id where e.document_id=?",Long.class,f.document())).isZero();
        var first=observe(f,input,key);var replay=observe(f,input,key);
        assertThat(json.readTree(json.writeValueAsString(first.body()))).isEqualTo(json.readTree(json.writeValueAsString(replay.body())));
        assertThat(db.queryForObject("select count(*) from document_events where document_id=? and event_type in ('OBSERVED_AT','UNOBSERVED_TRANSFER','EXCEPTION_OPENED')",Long.class,f.document())).isEqualTo(3);
        assertThat(db.queryForObject("select count(*) from tracking_exceptions where document_id=?",Long.class,f.document())).isEqualTo(1);
        assertThat(task(f)).isEqualTo(original);
    }
    @Test void guardsRejectPendingModelBReservationActiveLegMissingPhysicalProofAndUnfinalizedPhoto() throws Exception {
        var f=h.fixture("MODEL_A");var photo=input(f,0,0);
        rejected(f,photo,"WORKFLOW_SYNC_PENDING");h.apply(f);
        rejected(f,new ObservationService.Observation(h.version(f),photo.observed_station_id(),photo.proposed_route_step_id(),photo.evidence_upload_ids(),photo.reason(),false),"PHYSICAL_PRESENCE_REQUIRED");
        rejected(f,new ObservationService.Observation(h.version(f),photo.observed_station_id(),photo.proposed_route_step_id(),List.of(),photo.reason(),true),"EVIDENCE_REQUIRED");
        var unfinished=new EvidenceService.Upload(null,f.stations().getFirst(),"image/png",10L,"0".repeat(64));
        UUID upload=h.resultId(commands.execute(f.courier(),"evidence:create",key(),unfinished,()->evidence.create(f.courier(),unfinished)),"id");
        rejected(f,new ObservationService.Observation(h.version(f),photo.observed_station_id(),photo.proposed_route_step_id(),List.of(upload),photo.reason(),true),"EVIDENCE_NOT_USABLE");
        h.reserve(f,0);rejected(f,input(f,0,0),"CUSTODY_CONFLICT");
        var other=h.fixture("MODEL_A");h.apply(other);h.transit(other,0);rejected(other,input(other,1,1),"CUSTODY_CONFLICT");
        var b=h.fixture("MODEL_B");h.apply(b);rejected(b,input(b,0,0),"MODEL_MISMATCH");
    }
    @Test void courierCapabilityAssignedActiveStationAndCurrentSessionAreRequired() throws Exception {
        var f=h.fixture("MODEL_A");h.apply(f);var input=input(f,0,0);
        for(Actor denied:List.of(f.origin(),h.user(null,Set.of(f.stations().getFirst()),false),h.user(null,Set.of(f.stations().get(1)),true)))
            assertThatThrownBy(()->observations.authorize(denied,f.document(),input)).isInstanceOf(ApiException.class);
        db.update("update stations set is_active=false where id=?",f.stations().getFirst());
        assertThatThrownBy(()->observe(f,input,key())).isInstanceOf(ApiException.class);
        db.update("update stations set is_active=true where id=?",f.stations().getFirst());
        db.update("update users set session_version=session_version+1 where id=?",f.courier().id());
        assertThatThrownBy(()->observe(f,input,key())).isInstanceOf(ApiException.class);
        assertThat(db.queryForObject("select count(*) from document_events where document_id=? and event_type='OBSERVED_AT'",Long.class,f.document())).isZero();
    }
    @Test void staleVersionBlankReasonAndForeignStationPhotosCannotWriteObservation() throws Exception {
        var f=h.fixture("MODEL_A");h.apply(f);var input=input(f,0,0);
        rejected(f,new ObservationService.Observation(h.version(f)+1,input.observed_station_id(),input.proposed_route_step_id(),input.evidence_upload_ids(),input.reason(),true),"STALE_VERSION");
        rejected(f,new ObservationService.Observation(h.version(f),input.observed_station_id(),input.proposed_route_step_id(),input.evidence_upload_ids(),"  ",true),"REASON_REQUIRED");
        rejected(f,new ObservationService.Observation(h.version(f),input.observed_station_id(),input.proposed_route_step_id(),List.of(h.photo(f.courier(),f.stations().get(1))),input.reason(),true),"EVIDENCE_NOT_USABLE");
        assertThat(db.queryForObject("select count(*) from document_events where document_id=? and event_type='OBSERVED_AT'",Long.class,f.document())).isZero();
    }
    @Test void finishedCourierTrackingCannotBeReopenedByObservation() throws Exception {
        var f=h.fixture("MODEL_A");h.apply(f);
        for(int i=0;i<3;i++) { UUID leg=h.transit(f,i);h.drop(f,i,leg); }
        rejected(f,input(f,0,3),"INVALID_STATE");
        assertThat(h.doc(f)).containsEntry("business_state","COURIER_TRACKING_COMPLETE");
        assertThat(db.queryForObject("select count(*) from tracking_exceptions where document_id=?",Long.class,f.document())).isZero();
    }
    @Test void httpRequiresRealSessionCsrfAndReturnsReplayAndReadableLocalOperation() throws Exception {
        var f=h.fixture("MODEL_A");h.apply(f);var input=input(f,0,0);String path="/api/v1/documents/"+f.document()+"/observations",key=key();
        var cookie=IdentityIntegrationTest.cookie(login.finalizeLogin(IdentityIntegrationTest.google(f.courier().email(),f.courier().id().toString(),true,"mfu.ac.th")));
        mvc.perform(post(path).cookie(cookie).header("Idempotency-Key",key).contentType("application/json").content(json.writeValueAsBytes(input))).andExpect(status().isForbidden());
        String token=json.readTree(mvc.perform(get("/api/v1/auth/csrf").cookie(cookie)).andReturn().getResponse().getContentAsString()).get("token").asText();
        var result=mvc.perform(post(path).cookie(cookie).header("Idempotency-Key",key).header("X-CSRF-TOKEN",token).contentType("application/json").content(json.writeValueAsBytes(input)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reconciliation_status").value("NOT_REQUIRED")).andReturn();
        String operation=json.readTree(result.getResponse().getContentAsString()).get("operation_id").asText();
        mvc.perform(get("/api/v1/operations/"+operation).cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.process_sync_status").value("APPLIED"));
        mvc.perform(post(path).cookie(cookie).header("Idempotency-Key",key).header("X-CSRF-TOKEN",token).contentType("application/json").content(json.writeValueAsBytes(input))).andExpect(status().isOk()).andExpect(jsonPath("$.operation_id").value(operation));
    }
}
