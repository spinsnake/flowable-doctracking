package th.ac.mfu.doctracking;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import jakarta.servlet.http.Cookie;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flowable.engine.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import th.ac.mfu.doctracking.common.*;
import th.ac.mfu.doctracking.domain.*;
import th.ac.mfu.doctracking.identity.*;
import th.ac.mfu.doctracking.workflow.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc
class WorkflowReconciliationIntegrationTest extends DatabaseTest {
    @Autowired JdbcTemplate db;
    @Autowired CommandService commands;
    @Autowired RegistryService registry;
    @Autowired WorkflowCommandService business;
    @Autowired ReservationService reservations;
    @Autowired EvidenceService evidence;
    @Autowired ObservationService observations;
    @Autowired WorkflowOutboxWorker worker;
    @Autowired WorkflowReconciliationService reconciliation;
    @Autowired RuntimeService runtime;
    @Autowired TaskService tasks;
    @Autowired RepositoryService repository;
    @Autowired TrackingWorkflowEngine engine;
    @Autowired LoginService login;
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired HistoryService history;
    @Autowired PlatformTransactionManager manager;
    record Fixture(Actor actor,Actor admin,UUID document,List<UUID> stations,List<UUID> steps) {}
    String key(){return UUID.randomUUID().toString();}
    CommandResult command(Actor actor,Object input,Supplier<CommandResult> work) {
        return commands.execute(actor,"p6:fixture",key(),input,work);
    }
    UUID id(CommandResult result,String field){return UUID.fromString(((Map<?,?>)result.body()).get(field).toString());}
    Fixture fixture(String mode) {
        UUID unit=UUID.randomUUID(),user=UUID.randomUUID(),admin=UUID.randomUUID(),a=UUID.randomUUID(),b=UUID.randomUUID();
        db.update("insert into units(id,code,name,is_active) values(?,?,?,true)",unit,key(),"Reconciliation unit");
        for(UUID station:List.of(a,b))db.update("insert into stations(id,unit_id,code,name) values(?,?,?,?)",station,unit,key(),"Station");
        for(UUID actor:List.of(user,admin))db.update("insert into users(id,email,display_name,is_admin) values(?,?,?,?)",actor,actor+"@mfu.ac.th","Reconciliation actor",actor.equals(admin));
        db.update("insert into unit_memberships values(?,?,'STAFF')",user,unit);
        for(UUID station:List.of(a,b))db.update("insert into user_station_permissions values(?,?)",user,station);
        db.update("insert into user_capabilities values(?,'COURIER')",user);
        Actor actor=new Actor(user,user+"@mfu.ac.th","Reconciliation actor",false,List.of(new Actor.Membership(unit,"STAFF")),Set.of(a,b),Set.of("COURIER"),0);
        Actor central=new Actor(admin,admin+"@mfu.ac.th","Central",true,List.of(),Set.of(),Set.of(),0);
        var template=new RegistryService.TemplateInput("A B A",unit,List.of(a,b,a),null);
        UUID templateId=id(command(actor,template,()->registry.createTemplate(actor,template)),"id");
        db.update("update workflow_policy set default_tracking_mode=? where id=true",mode);
        var registration=new RegistryService.Registration("Private reconciliation title",null,a,templateId,1);
        CommandResult registered;
        try{registered=command(actor,registration,()->registry.register(actor,registration));}
        finally{db.update("update workflow_policy set default_tracking_mode='MODEL_B' where id=true");}
        UUID document=UUID.fromString(((List<?>)((Map<?,?>)registered.body()).get("document_ids")).getFirst().toString());
        return new Fixture(actor,central,document,List.of(a,b,a),db.queryForList("select id from route_steps where document_id=? order by sequence",UUID.class,document));
    }
    long version(Fixture f){return db.queryForObject("select version from documents where id=?",Long.class,f.document);}
    String instance(Fixture f){return db.queryForObject("select process_instance_id from workflow_instances where document_id=?",String.class,f.document);}
    void apply(Fixture f){assertThat(worker.processDocument(f.document)).isTrue();}
    Map<?,?> scan(Fixture f) {
        var input=new WorkflowReconciliationService.Scan("Operator checked diagnostics",f.document,1);
        return (Map<?,?>)command(f.admin,input,()->reconciliation.scan(f.admin,input)).body();
    }
    void clean(Fixture f,boolean pending){var result=scan(f);assertThat(result.get("mismatches")).isEqualTo(0);assertThat(result.get("pending")).isEqualTo(pending?1:0);}
    Set<String> findings(Fixture f){return Set.copyOf(db.queryForList("select code from workflow_reconciliation_findings where document_id=? and state='OPEN'",String.class,f.document));}
    void ready(Fixture f,int index) {
        var input=new WorkflowCommandService.Step(f.steps.get(index),version(f));
        command(f.actor,input,()->business.ready(f.actor,f.document,input));
    }
    UUID photo(Fixture f,int index) throws IOException {
        var output=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",output);byte[] bytes=output.toByteArray();
        var input=new EvidenceService.Upload(null,f.stations.get(index),"image/png",(long)bytes.length,EvidenceService.checksum(bytes));
        UUID photo=id(command(f.actor,input,()->evidence.create(f.actor,input)),"id");
        evidence.content(f.actor,photo,"image/png",new ByteArrayInputStream(bytes));
        command(f.actor,photo,()->evidence.finalizeUpload(f.actor,photo));return photo;
    }
    UUID transport(Fixture f,int index,boolean inspect) throws IOException {
        var reserve=new ReservationService.Reserve(f.stations.get(index),List.of(new ReservationService.Item(f.document,f.steps.get(index),version(f))));
        UUID reservation=id(command(f.actor,reserve,()->reservations.reserve(f.actor,reserve)),"id");
        if(inspect)clean(f,false);
        var pickup=new WorkflowCommandService.Pickup(f.stations.get(index),List.of(new WorkflowCommandService.Item(f.document,f.steps.get(index),version(f),null)),List.of(photo(f,index)),reservation,true);
        command(f.actor,pickup,()->business.pickup(f.actor,pickup));
        if(inspect)clean(f,true);apply(f);if(inspect)clean(f,false);
        UUID leg=db.queryForObject("select active_leg_id from documents where id=?",UUID.class,f.document);
        var drop=new WorkflowCommandService.Dropoff(f.stations.get(index+1),List.of(new WorkflowCommandService.Item(f.document,f.steps.get(index+1),version(f),leg)),List.of(photo(f,index+1)));
        command(f.actor,drop,()->business.dropoff(f.actor,drop));if(inspect)clean(f,true);apply(f);if(inspect)clean(f,false);
        return leg;
    }
    void receive(Fixture f,int index) {
        if(index==f.steps.size()-1) {
            var input=new WorkflowCommandService.Step(f.steps.get(index),version(f));command(f.actor,input,()->business.confirmReturn(f.actor,f.document,input));
        } else {
            var input=new WorkflowCommandService.Batch(f.stations.get(index),List.of(new WorkflowCommandService.Item(f.document,f.steps.get(index),version(f),null)));
            command(f.actor,input,()->business.confirmReceipt(f.actor,f.stations.get(index),input));
        }
    }
    @Test void canonicalModelsPendingDeliveriesReservationsCustodyReceiptsAndTerminalHaveNoFalseFindings() throws Exception {
        for(String mode:List.of("MODEL_A","MODEL_B")) {
            var f=fixture(mode);clean(f,true);apply(f);clean(f,false);
            if(mode.equals("MODEL_B")){ready(f,0);clean(f,true);apply(f);clean(f,false);}
            for(int index=0;index<2;index++) {
                transport(f,index,true);
                if(mode.equals("MODEL_B")) {
                    receive(f,index+1);clean(f,true);apply(f);clean(f,false);
                    if(index==0){ready(f,1);clean(f,true);apply(f);clean(f,false);}
                }
            }
            assertThat(runtime.createProcessInstanceQuery().processInstanceId(instance(f)).count()).isZero();
            assertThat(findings(f)).isEmpty();
        }
    }
    @Test void reportsDeduplicatedMismatchWithoutChangingBusinessOrEngineAndResolvesAfterRealRepair() throws Exception {
        var f=fixture("MODEL_B");apply(f);String instance=instance(f);
        var task=tasks.createTaskQuery().processInstanceId(instance).singleResult();
        var before=db.queryForMap("select * from documents where id=?",f.document);
        var events=db.queryForList("select * from document_events where document_id=? order by sequence",f.document);
        tasks.setVariableLocal(task.getId(),"routeStepId",f.steps.getLast().toString());
        scan(f);scan(f);
        assertThat(findings(f)).containsExactly("ENGINE_TASK_BINDING_MISMATCH");
        assertThat(db.queryForObject("select sightings from workflow_reconciliation_findings where document_id=?",Long.class,f.document)).isEqualTo(2L);
        assertThat(db.queryForMap("select * from documents where id=?",f.document)).isEqualTo(before);
        assertThat(db.queryForList("select * from document_events where document_id=? order by sequence",f.document)).isEqualTo(events);
        assertThat(tasks.getVariableLocal(task.getId(),"routeStepId")).isEqualTo(f.steps.getLast().toString());
        tasks.setVariableLocal(task.getId(),"routeStepId",f.steps.getFirst().toString());clean(f,false);
        assertThat(db.queryForObject("select state from workflow_reconciliation_findings where document_id=?",String.class,f.document)).isEqualTo("RESOLVED");
        runtime.setVariable(instance,"trackingMode","MODEL_A");scan(f);assertThat(findings(f)).contains("ENGINE_SNAPSHOT_MISMATCH");
        runtime.setVariable(instance,"trackingMode","MODEL_B");clean(f,false);
        db.update("update documents set reported_location_step_id=? where id=?",f.steps.getLast(),f.document);
        scan(f);assertThat(findings(f)).contains("PROJECTION_LEDGER_MISMATCH");
        db.update("update documents set reported_location_step_id=? where id=?",f.steps.getFirst(),f.document);clean(f,false);
        runtime.setVariable(instance,"stepIndex",1);scan(f);assertThat(findings(f)).contains("ENGINE_ROUTE_MISMATCH");
        runtime.setVariable(instance,"stepIndex",0);clean(f,false);
        ready(f,0);apply(f);UUID closed=transport(f,0,true);
        db.update("update documents set active_leg_id=? where id=?",closed,f.document);
        scan(f);assertThat(findings(f)).contains("CUSTODY_LEG_MISMATCH","ENGINE_CUSTODY_MISMATCH","PROJECTION_LEDGER_MISMATCH");
        db.update("update documents set active_leg_id=null where id=?",f.document);clean(f,false);
    }
    @Test void heldObservationExceptionIsConsistentAndDoesNotAdvanceOrCreateChecks() throws Exception {
        var f=fixture("MODEL_A");apply(f);String task=tasks.createTaskQuery().processInstanceId(instance(f)).singleResult().getId();
        var input=new ObservationService.Observation(version(f),f.stations.get(1),f.steps.get(1),List.of(photo(f,1)),"Observed a gap",true);
        command(f.actor,input,()->observations.observe(f.actor,f.document,input));
        clean(f,false);
        assertThat(tasks.createTaskQuery().processInstanceId(instance(f)).singleResult().getId()).isEqualTo(task);
        assertThat(db.queryForObject("select count(*) from document_events where document_id=? and event_type like 'COURIER_%'",Long.class,f.document)).isZero();
        db.update("update tracking_exceptions set previous_state='READY_FOR_PICKUP' where document_id=?",f.document);
        scan(f);assertThat(findings(f)).contains("PROJECTION_EXCEPTION_MISMATCH");
    }
    @Test void canonicalEnvelopeTamperingIsVisibleWhilePendingAndNeverReplayedByScan() {
        var f=fixture("MODEL_B");
        db.update("update workflow_outbox set payload=jsonb_set(payload,'{tracking_mode}','\"MODEL_A\"') where document_id=?",f.document);
        scan(f);assertThat(findings(f)).containsExactly("DELIVERY_LEDGER_MISMATCH");
        assertThat(db.queryForObject("select count(*) from workflow_instances where document_id=?",Long.class,f.document)).isZero();
        assertThat(db.queryForObject("select count(*) from workflow_inbox where document_id=?",Long.class,f.document)).isZero();
        for(String invalid:List.of("[]","null","\"scalar\"","1")) {
            db.update("update workflow_outbox set payload=?::jsonb where document_id=?",invalid,f.document);
            scan(f);assertThat(findings(f)).containsExactly("DELIVERY_LEDGER_MISMATCH");
        }
    }
    @Test void scheduledScanMovesPastPoisonDocumentAndDoesNotResolveItsExistingEngineFindingWhilePending() {
        var first=fixture("MODEL_B");var second=fixture("MODEL_B");
        Fixture poison=first.document.toString().compareTo(second.document.toString())<0?first:second;
        Fixture later=poison==first?second:first;
        apply(later);var task=tasks.createTaskQuery().processInstanceId(instance(later)).singleResult();
        tasks.setVariableLocal(task.getId(),"routeStepId",later.steps.getLast().toString());
        db.update("update workflow_outbox set payload='[]'::jsonb where document_id=?",poison.document);
        db.update("insert into workflow_reconciliation_findings(document_id,code,state) values(?,'ENGINE_TASK_BINDING_MISMATCH','OPEN')",poison.document);
        var scheduled=new WorkflowReconciliationService(db,json,runtime,tasks,history,repository,manager,true);
        // Start immediately before this document, independently of other integration fixtures.
        var numeric=new java.math.BigInteger(poison.document.toString().replace("-",""),16).subtract(java.math.BigInteger.ONE);
        String hex=String.format("%032x",numeric);
        UUID before=UUID.fromString(hex.substring(0,8)+"-"+hex.substring(8,12)+"-"+hex.substring(12,16)+"-"+hex.substring(16,20)+"-"+hex.substring(20));
        ReflectionTestUtils.setField(scheduled,"cursor",before);scheduled.applicationReady();
        for(int i=0;i<20 && findings(later).isEmpty();i++)scheduled.scheduled();
        assertThat(findings(poison)).contains("DELIVERY_LEDGER_MISMATCH","ENGINE_TASK_BINDING_MISMATCH");
        assertThat(findings(later)).contains("ENGINE_TASK_BINDING_MISMATCH");
        assertThat(db.queryForObject("select count(*) from workflow_instances where document_id=?",Long.class,poison.document)).isZero();
    }
    Cookie session(Actor actor){return IdentityIntegrationTest.cookie(login.finalizeLogin(IdentityIntegrationTest.google(actor.email(),actor.id().toString(),true,"mfu.ac.th")));}
    String csrf(Cookie cookie) throws Exception{return json.readTree(http.perform(get("/api/v1/auth/csrf").cookie(cookie)).andReturn().getResponse().getContentAsString()).get("token").asText();}
    @Test void adminHttpRequiresCsrfAndIdempotencyRevalidatesPermissionAndRedactsPrivateFields() throws Exception {
        var f=fixture("MODEL_B");apply(f);Cookie admin=session(f.admin),staff=session(f.actor);String path="/api/v1/admin/operations/reconciliation";
        String token=csrf(admin),key=key(),body=json.writeValueAsString(new WorkflowReconciliationService.Scan("Manual review",f.document,1));
        http.perform(get(path+"/findings").cookie(staff)).andExpect(status().isForbidden());
        http.perform(post(path+"/scan").cookie(admin).header("Idempotency-Key",key).contentType("application/json").content(body)).andExpect(status().isForbidden());
        http.perform(post(path+"/scan").cookie(admin).header("X-CSRF-TOKEN",token).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        for(int i=0;i<2;i++)http.perform(post(path+"/scan").cookie(admin).header("X-CSRF-TOKEN",token).header("Idempotency-Key",key).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.mismatches").value(0));
        assertThat(db.queryForObject("select count(*) from audit_events where actor_id=? and action='WORKFLOW_RECONCILIATION_SCANNED'",Long.class,f.admin.id())).isEqualTo(1L);
        var task=tasks.createTaskQuery().processInstanceId(instance(f)).singleResult();tasks.setVariableLocal(task.getId(),"routeStepId",f.steps.getLast().toString());scan(f);
        String response=http.perform(get(path+"/findings").param("document_id",f.document.toString()).cookie(admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(response).contains("ENGINE_TASK_BINDING_MISMATCH").doesNotContain("Private reconciliation title",f.actor.email(),"payload","reason");
        db.update("update users set is_admin=false where id=?",f.admin.id());
        assertThatThrownBy(()->reconciliation.authorize(f.admin)).isInstanceOf(ApiException.class);
        http.perform(post(path+"/scan").cookie(admin).header("X-CSRF-TOKEN",token).header("Idempotency-Key",key).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
    }
    @Test void newDefinitionDeploymentLeavesOldMappedAAndBInstancesPinnedAndBothCompleteUsingCompatibleDelegates() throws Exception {
        var oldA=fixture("MODEL_A");var oldB=fixture("MODEL_B");apply(oldA);apply(oldB);
        var aMapping=db.queryForMap("select * from workflow_instances where document_id=?",oldA.document);
        var bMapping=db.queryForMap("select * from workflow_instances where document_id=?",oldB.document);
        String xml;
        try(var input=getClass().getResourceAsStream("/processes/document_tracking_v1.bpmn20.xml")) {
            xml=new String(Objects.requireNonNull(input).readAllBytes(),StandardCharsets.UTF_8);
        }
        try {
            repository.createDeployment().name("document-tracking-v1").enableDuplicateFiltering()
                    .addString("processes/document_tracking_v1.bpmn20.xml",xml.replace("Document tracking A/B v1","Document tracking A/B compatible P6 deployment")).deploy();
            var newer=fixture("MODEL_B");apply(newer);
            assertThat(db.queryForObject("select process_definition_version from workflow_instances where document_id=?",Integer.class,newer.document))
                    .isGreaterThan(((Number)bMapping.get("process_definition_version")).intValue());
            assertThat(db.queryForMap("select * from workflow_instances where document_id=?",oldA.document)).isEqualTo(aMapping);
            assertThat(db.queryForMap("select * from workflow_instances where document_id=?",oldB.document)).isEqualTo(bMapping);
            clean(oldA,false);clean(oldB,false);clean(newer,false);
            ready(oldB,0);apply(oldB);
            for(int index=0;index<2;index++) {
                transport(oldA,index,true);transport(oldB,index,true);
                receive(oldB,index+1);apply(oldB);clean(oldB,false);
                if(index==0){ready(oldB,1);apply(oldB);}
            }
            assertThat(runtime.createProcessInstanceQuery().processInstanceId(instance(oldA)).count()).isZero();
            assertThat(runtime.createProcessInstanceQuery().processInstanceId(instance(oldB)).count()).isZero();
            assertThat(db.queryForMap("select * from workflow_instances where document_id=?",oldA.document)).isEqualTo(aMapping);
            assertThat(db.queryForMap("select * from workflow_instances where document_id=?",oldB.document)).isEqualTo(bMapping);
        } finally {engine.deploy();}
    }
}
