package th.ac.mfu.doctracking;

import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flowable.engine.*;
import org.flowable.spring.SpringProcessEngineConfiguration;
import th.ac.mfu.doctracking.common.*;
import th.ac.mfu.doctracking.domain.*;
import th.ac.mfu.doctracking.identity.*;
import th.ac.mfu.doctracking.workflow.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc
class WorkflowOutboxIntegrationTest extends DatabaseTest {
    @Autowired JdbcTemplate db;
    @Autowired RegistryService registry;
    @Autowired WorkflowCommandService business;
    @Autowired ReservationService reservations;
    @Autowired CommandService commands;
    @Autowired WorkflowOutboxWorker worker;
    @MockitoSpyBean TrackingWorkflowEngine engine;
    @Autowired RuntimeService runtime;
    @Autowired TaskService tasks;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager manager;
    @Autowired SpringProcessEngineConfiguration configuration;
    @Autowired DataSource dataSource;
    @Autowired LoginService login;
    @Autowired MockMvc http;

    record Fixture(Actor actor,UUID document,UUID step,UUID event,UUID operation,UUID outbox) {}
    Fixture fixture() {
        UUID unit=UUID.randomUUID(),station=UUID.randomUUID(),user=UUID.randomUUID();String email=user+"@mfu.ac.th";
        db.update("insert into units(id,code,name,is_active) values (?,?,?,true)",unit,unit.toString(),"Workflow fixture");
        db.update("insert into stations(id,unit_id,code,name) values (?,?,?,?)",station,unit,station.toString(),"Origin");
        db.update("insert into users(id,email,display_name) values (?,?,'Workflow fixture')",user,email);
        db.update("insert into unit_memberships values (?,?,'STAFF')",user,unit);
        Actor actor=new Actor(user,email,"Workflow fixture",false,List.of(new Actor.Membership(unit,"STAFF")),Set.of(),Set.of(),0);
        var input=new RegistryService.TemplateInput("Origin return",unit,List.of(station,station),null);
        var template=commands.execute(actor,"template:create",UUID.randomUUID().toString(),input,()->registry.createTemplate(actor,input));
        UUID templateId=UUID.fromString(((Map<?,?>)template.body()).get("id").toString());
        var registration=new RegistryService.Registration("Recovery fixture",null,station,templateId,1);
        var result=commands.execute(actor,"document:register",UUID.randomUUID().toString(),registration,()->registry.register(actor,registration));
        Map<?,?> body=(Map<?,?>)result.body();UUID document=UUID.fromString(((List<?>)body.get("document_ids")).getFirst().toString());
        var event=db.queryForMap("select e.id,e.route_step_id,e.operation_id,o.id as outbox from document_events e join workflow_outbox o on o.event_id=e.id where e.document_id=?",document);
        return new Fixture(actor,document,(UUID)event.get("route_step_id"),(UUID)event.get("id"),(UUID)event.get("operation_id"),(UUID)event.get("outbox"));
    }
    String instance(Fixture f) {return db.queryForObject("select process_instance_id from workflow_instances where document_id=?",String.class,f.document);}
    String state(Fixture f) {return db.queryForObject("select process_sync_status from documents where id=?",String.class,f.document);}
    void due(UUID outbox) {db.update("update workflow_outbox set available_at=now()-interval '1 second' where id=?",outbox);}

    record BulkDelivery(UUID operation,List<Fixture> documents) {}
    BulkDelivery bulkReady() throws Exception { return bulkReady(false); }
    BulkDelivery bulkReady(boolean multipleOrigins) throws Exception {
        Fixture first=fixture();worker.processDocument(first.document);
        UUID secondDocument;Actor owner;
        if(multipleOrigins) {
            Fixture second=fixture();secondDocument=second.document;
            UUID unit=second.actor.memberships().getFirst().unitId();
            db.update("insert into unit_memberships(user_id,unit_id,role) values(?,?,'STAFF')",first.actor.id(),unit);
            db.update("insert into user_capabilities(user_id,capability) values(?,'COURIER')",first.actor.id());
            var memberships=new ArrayList<>(first.actor.memberships());memberships.add(new Actor.Membership(unit,"STAFF"));
            owner=new Actor(first.actor.id(),first.actor.email(),first.actor.displayName(),false,memberships,Set.of(),Set.of("COURIER"),0);
        } else {
            var source=db.queryForMap("select origin_station_id,template_id,template_version from documents where id=?",first.document);
            var registration=new RegistryService.Registration("Second bulk recovery fixture",null,(UUID)source.get("origin_station_id"),(UUID)source.get("template_id"),((Number)source.get("template_version")).intValue());
            var registered=commands.execute(first.actor,"document:register",UUID.randomUUID().toString(),registration,()->registry.register(first.actor,registration));
            secondDocument=UUID.fromString(((List<?>)((Map<?,?>)registered.body()).get("document_ids")).getFirst().toString());owner=first.actor;
        }
        worker.processDocument(secondDocument);
        UUID operation=UUID.randomUUID();var deliveries=new ArrayList<Fixture>();var states=new ArrayList<Map<String,Object>>();
        // Canonical committed ledger/outbox fixtures; transitions still execute on the real engine.
        new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(status->{
            for(UUID document:List.of(first.document,secondDocument)) {
                var doc=db.queryForMap("select * from documents where id=? for update",document);
                UUID event=UUID.randomUUID(),outbox=UUID.randomUUID(),step=(UUID)doc.get("current_route_step_id");
                String payload;
                try { payload=json.writeValueAsString(Map.of("document_id",document,"route_step_id",step,"tracking_mode",doc.get("tracking_mode"),"policy_version",doc.get("policy_version"),"expected_task_key","ORIGIN_READY")); }
                catch(Exception failure) { throw new IllegalStateException(failure); }
                long sequence=db.queryForObject("update documents set event_sequence=event_sequence+1,business_state='READY_FOR_PICKUP',version=version+1,process_sync_status='PENDING',latest_operation_id=? where id=? returning event_sequence",Long.class,operation,document);
                db.update("insert into document_events(id,document_id,sequence,event_type,actor_id,route_step_id,operation_id,payload) values(?,?,?,'UNIT_READY',?,?,?,?::jsonb)",event,document,sequence,owner.id(),step,operation,payload);
                db.update("insert into workflow_outbox(id,event_id,document_id,sequence,action,payload) values(?,?,?,?,'UNIT_READY',?::jsonb)",outbox,event,document,sequence,payload);
                deliveries.add(new Fixture(owner,document,step,event,operation,outbox));
                states.add(Map.of("document_id",document,"business_state","READY_FOR_PICKUP","version",1,"process_sync_status","PENDING"));
            }
            try {
                String result=json.writeValueAsString(Map.of("operation_id",operation,"document_ids",List.of(first.document,secondDocument),"accepted",List.of(first.document,secondDocument),"rejected",List.of(),"updated_states",states,"process_sync_status","PENDING","event_ids",deliveries.stream().map(Fixture::event).toList()));
                db.update("insert into tracking_operations(id,actor_id,document_id,result) values(?,?,null,?::jsonb)",operation,owner.id(),result);
                for(var delivery:deliveries) db.update("insert into operation_documents(operation_id,document_id) values(?,?)",operation,delivery.document);
            } catch(Exception failure) { throw new IllegalStateException(failure); }
        });
        return new BulkDelivery(operation,deliveries);
    }

    Actor accessActor(List<UUID> units,Set<UUID> stations,boolean courier) {
        UUID id=UUID.randomUUID();String email=id+"@mfu.ac.th";
        db.update("insert into users(id,email,display_name) values(?,?,'Operation access fixture')",id,email);
        for(UUID unit:units) db.update("insert into unit_memberships(user_id,unit_id,role) values(?,?,'STAFF')",id,unit);
        for(UUID station:stations) db.update("insert into user_station_permissions(user_id,station_id) values(?,?)",id,station);
        if(courier) db.update("insert into user_capabilities(user_id,capability) values(?,'COURIER')",id);
        return new Actor(id,email,"Operation access fixture",false,units.stream().map(unit->new Actor.Membership(unit,"STAFF")).toList(),stations,courier?Set.of("COURIER"):Set.of(),0);
    }

    @Test void multiOriginOperationRequiresEveryOriginUnlessActorSubmittedIt() throws Exception {
        var bulk=bulkReady(true);Actor owner=bulk.documents.getFirst().actor;
        var units=bulk.documents.stream().map(delivery->db.queryForObject("select origin_unit_id from documents where id=?",UUID.class,delivery.document)).toList();
        Actor firstOrigin=accessActor(List.of(units.getFirst()),Set.of(),false);
        assertThat(registry.detail(firstOrigin,bulk.documents.getFirst().document)).isNotEmpty();
        assertThatThrownBy(()->registry.operation(firstOrigin,bulk.operation)).isInstanceOfSatisfying(ApiException.class,failure->assertThat(failure.status()).isEqualTo(404));
        assertThat(registry.operation(owner,bulk.operation).get("process_sync_status")).isEqualTo("PENDING");
        for(var delivery:bulk.documents) worker.processDocument(delivery.document);
        Actor everyOrigin=accessActor(units,Set.of(),false);
        assertThat(registry.operation(everyOrigin,bulk.operation).get("process_sync_status")).isEqualTo("APPLIED");
    }

    @Test void rejectedUnknownSelectionRemainsVisibleOnlyToItsSubmitter() {
        Fixture f=fixture();worker.processDocument(f.document);
        var ready=new WorkflowCommandService.Step(f.step,0L);
        commands.execute(f.actor,"ready",UUID.randomUUID().toString(),ready,()->business.ready(f.actor,f.document,ready));worker.processDocument(f.document);
        UUID station=db.queryForObject("select station_id from route_steps where id=?",UUID.class,f.step),unknown=UUID.randomUUID();
        Actor courier=accessActor(List.of(),Set.of(station),true);
        var input=new ReservationService.Reserve(station,List.of(new ReservationService.Item(f.document,f.step,1L),new ReservationService.Item(unknown,UUID.randomUUID(),0L)));
        var response=commands.execute(courier,"reservation:create",UUID.randomUUID().toString(),input,()->reservations.reserve(courier,input));
        UUID operation=UUID.fromString(((Map<?,?>)response.body()).get("operation_id").toString());
        assertThat(registry.operation(courier,operation).get("rejected").toString()).contains(unknown.toString());
        assertThatThrownBy(()->registry.operation(f.actor,operation)).isInstanceOfSatisfying(ApiException.class,failure->assertThat(failure.status()).isEqualTo(404));
    }

    @Test void bulkOperationWaitsForEveryDeliveryAndRetriesOnlySelectedFailure() throws Exception {
        var bulk=bulkReady();Fixture first=bulk.documents.getFirst(),second=bulk.documents.getLast();
        worker.processDocument(first.document);
        assertThat(registry.operation(first.actor,bulk.operation).get("process_sync_status")).isEqualTo("PENDING");
        String process=instance(second);
        doThrow(new IllegalStateException("bulk dependency unavailable")).when(engine).apply(eq(process),eq(second.document),eq("UNIT_READY"),any(),nullable(UUID.class),nullable(UUID.class),nullable(UUID.class));
        worker.processDocument(second.document);
        assertThat(registry.operation(first.actor,bulk.operation).get("process_sync_status")).isEqualTo("FAILED_RETRYABLE");
        assertThat(db.queryForObject("select status from workflow_outbox where id=?",String.class,first.outbox)).isEqualTo("APPLIED");
        UUID admin=UUID.randomUUID();String email=admin+"@mfu.ac.th";
        db.update("insert into users(id,email,display_name,is_admin) values (?,?,'Bulk recovery admin',true)",admin,email);
        var cookie=IdentityIntegrationTest.cookie(login.finalizeLogin(IdentityIntegrationTest.google(email,admin.toString(),true,"mfu.ac.th")));
        String token=json.readTree(http.perform(get("/api/v1/auth/csrf").cookie(cookie)).andReturn().getResponse().getContentAsString()).get("token").asText();
        http.perform(get("/api/v1/admin/workflow-operations/"+bulk.operation).cookie(cookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deliveries.length()").value(2)).andExpect(jsonPath("$.version").doesNotExist());
        long version=db.queryForObject("select version from workflow_outbox where id=?",Long.class,second.outbox);
        String path="/api/v1/admin/workflow-operations/"+bulk.operation+"/retry";
        http.perform(post(path).cookie(cookie).header("X-CSRF-TOKEN",token).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(json.writeValueAsString(new WorkflowRecoveryController.Retry(version,"Retry without selecting"))))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DELIVERY_REQUIRED"));
        String foreignDelivery=json.writeValueAsString(new WorkflowRecoveryController.Retry(version,"Reject unrelated delivery",UUID.randomUUID()));
        http.perform(post(path).cookie(cookie).header("X-CSRF-TOKEN",token).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(foreignDelivery))
                .andExpect(status().isNotFound());
        String retryKey=UUID.randomUUID().toString(),body=json.writeValueAsString(new WorkflowRecoveryController.Retry(version,"Retry exact failed delivery",second.outbox));
        for(int i=0;i<2;i++) http.perform(post(path).cookie(cookie).header("X-CSRF-TOKEN",token).header("Idempotency-Key",retryKey).contentType("application/json").content(body)).andExpect(status().isAccepted());
        assertThat(registry.operation(first.actor,bulk.operation).get("process_sync_status")).isEqualTo("PENDING");
        assertThat(db.queryForObject("select count(*) from audit_events where resource_id=? and action='WORKFLOW_RETRY'",Long.class,bulk.operation)).isEqualTo(1);
        doCallRealMethod().when(engine).apply(eq(process),eq(second.document),eq("UNIT_READY"),any(),nullable(UUID.class),nullable(UUID.class),nullable(UUID.class));
        worker.processDocument(second.document);
        assertThat(registry.operation(first.actor,bulk.operation).get("process_sync_status")).isEqualTo("APPLIED");
        for(var delivery:bulk.documents) assertThat(tasks.createTaskQuery().processInstanceId(instance(delivery)).singleResult().getTaskDefinitionKey()).isEqualTo("COURIER_PICKUP");
    }

    @Test void concurrentBulkAcknowledgementsConvergeToApplied() throws Exception {
        var bulk=bulkReady();var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var pending=bulk.documents.stream().map(delivery->pool.submit(()->{gate.await();return worker.processDocument(delivery.document);})).toList();
            gate.countDown();for(var result:pending) assertThat(result.get(15,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(registry.operation(bulk.documents.getFirst().actor,bulk.operation).get("process_sync_status")).isEqualTo("APPLIED");
    }

    @Test void committedRegistrationRecoversWithNewWorkerAndReplayDoesNotStartTwice() {
        Fixture f=fixture();assertThat(state(f)).isEqualTo("PENDING");
        var restarted=new WorkflowOutboxWorker(db,json,engine,tasks,manager,false);
        assertThat(restarted.processDocument(f.document)).isTrue();String process=instance(f);
        assertThat(state(f)).isEqualTo("APPLIED");
        db.update("update workflow_outbox set status='PENDING' where id=?",f.outbox);
        assertThat(restarted.processDocument(f.document)).isTrue();
        assertThat(instance(f)).isEqualTo(process);
        assertThat(runtime.createProcessInstanceQuery().processInstanceBusinessKey(f.document.toString()).count()).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from workflow_inbox where event_id=?",Long.class,f.event)).isEqualTo(1);
        assertThat(tasks.createTaskQuery().processInstanceId(process).singleResult().getTaskDefinitionKey()).isEqualTo("ORIGIN_READY");
    }
    @Test void concurrentWorkersApplyExactlyOneRegistration() throws Exception {
        Fixture f=fixture();var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> apply=()->{gate.await();return worker.processDocument(f.document);};
            var first=pool.submit(apply);var second=pool.submit(apply);gate.countDown();
            assertThat(List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(runtime.createProcessInstanceQuery().processInstanceBusinessKey(f.document.toString()).count()).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from workflow_inbox where document_id=?",Long.class,f.document)).isEqualTo(1);
    }
    @Test void failureAfterEngineStartRollsBackProcessMappingAndInboxBeforeRetry() {
        Fixture f=fixture();
        doAnswer(call->{call.callRealMethod();throw new IllegalStateException("injected after engine start");}).when(engine).start(eq(f.document),anyString(),anyList());
        worker.processDocument(f.document);
        assertThat(state(f)).isEqualTo("FAILED_RETRYABLE");
        assertThat(runtime.createProcessInstanceQuery().processInstanceBusinessKey(f.document.toString()).count()).isZero();
        assertThat(db.queryForObject("select count(*) from workflow_instances where document_id=?",Long.class,f.document)).isZero();
        assertThat(db.queryForObject("select count(*) from workflow_inbox where event_id=?",Long.class,f.event)).isZero();
        assertThat(worker.processDocument(f.document)).isFalse(); // persisted backoff
        doCallRealMethod().when(engine).start(eq(f.document),anyString(),anyList());due(f.outbox);worker.processDocument(f.document);
        assertThat(state(f)).isEqualTo("APPLIED");
    }
    @Test void failureAfterTaskCompletionRollsBackTaskInboxAndAckTogether() {
        Fixture f=fixture();worker.processDocument(f.document);String process=instance(f);
        DataSource engineSource=configuration.getDataSource();
        if(engineSource instanceof TransactionAwareDataSourceProxy proxy)engineSource=proxy.getTargetDataSource();
        assertThat(engineSource).isSameAs(dataSource);assertThat(configuration.getTransactionManager()).isSameAs(manager);
        var ready=new WorkflowCommandService.Step(f.step,0L);
        commands.execute(f.actor,"ready",UUID.randomUUID().toString(),ready,()->business.ready(f.actor,f.document,ready));
        assertThat(registry.operation(f.actor,f.operation).get("process_sync_status")).isEqualTo("APPLIED");
        UUID event=db.queryForObject("select id from document_events where document_id=? and event_type='UNIT_READY'",UUID.class,f.document);
        UUID outbox=db.queryForObject("select id from workflow_outbox where event_id=?",UUID.class,event);
        String originalTask=tasks.createTaskQuery().processInstanceId(process).singleResult().getId();
        doAnswer(call->{call.callRealMethod();throw new IllegalStateException("injected after task complete");}).when(engine).apply(eq(process),eq(f.document),eq("UNIT_READY"),any(),nullable(UUID.class),nullable(UUID.class),nullable(UUID.class));
        worker.processDocument(f.document);
        assertThat(tasks.createTaskQuery().processInstanceId(process).singleResult().getId()).isEqualTo(originalTask);
        assertThat(db.queryForObject("select count(*) from workflow_inbox where event_id=?",Long.class,event)).isZero();
        assertThat(db.queryForObject("select status from workflow_outbox where id=?",String.class,outbox)).isEqualTo("RETRY_WAIT");
        doCallRealMethod().when(engine).apply(eq(process),eq(f.document),eq("UNIT_READY"),any(),nullable(UUID.class),nullable(UUID.class),nullable(UUID.class));
        due(outbox);worker.processDocument(f.document);
        assertThat(tasks.createTaskQuery().processInstanceId(process).singleResult().getTaskDefinitionKey()).isEqualTo("COURIER_PICKUP");
        db.update("update workflow_outbox set status='PENDING' where id=?",outbox);worker.processDocument(f.document);
        assertThat(tasks.createTaskQuery().processInstanceId(process).singleResult().getTaskDefinitionKey()).isEqualTo("COURIER_PICKUP");
    }
    @Test void mismatchedEnvelopeDeadLettersWithoutConsumingOrOpeningEngine() {
        Fixture f=fixture();
        db.update("update workflow_outbox set payload=jsonb_set(payload,'{route_step_id}',to_jsonb(?::text)) where id=?",UUID.randomUUID().toString(),f.outbox);
        worker.processDocument(f.document);
        assertThat(db.queryForObject("select status from workflow_outbox where id=?",String.class,f.outbox)).isEqualTo("DEAD_LETTER");
        assertThat(db.queryForObject("select count(*) from workflow_inbox where event_id=?",Long.class,f.event)).isZero();
        assertThat(runtime.createProcessInstanceQuery().processInstanceBusinessKey(f.document.toString()).count()).isZero();
    }
    @Test void laterWorkflowEventCannotOvertakeDelayedRegistration() throws Exception {
        Fixture f=fixture();UUID event=UUID.randomUUID(),operation=UUID.randomUUID();
        String payload=json.writeValueAsString(Map.of("document_id",f.document,"route_step_id",f.step,"tracking_mode","MODEL_B","policy_version",1,"expected_task_key","ORIGIN_READY"));
        db.update("insert into document_events(id,document_id,sequence,event_type,actor_id,route_step_id,operation_id,payload) values(?,?,2,'UNIT_READY',?,?,?,?::jsonb)",event,f.document,f.actor.id(),f.step,operation,payload);
        db.update("insert into workflow_outbox(id,event_id,document_id,sequence,action,payload) values(?,?,?,2,'UNIT_READY',?::jsonb)",UUID.randomUUID(),event,f.document,payload);
        db.update("update workflow_outbox set available_at=now()+interval '1 hour' where id=?",f.outbox);
        assertThat(worker.processDocument(f.document)).isFalse();
        assertThat(db.queryForObject("select count(*) from workflow_inbox where document_id=?",Long.class,f.document)).isZero();
        due(f.outbox);worker.processDocument(f.document);
        assertThat(tasks.createTaskQuery().processInstanceId(instance(f)).singleResult().getTaskDefinitionKey()).isEqualTo("ORIGIN_READY");
        worker.processDocument(f.document);
        assertThat(tasks.createTaskQuery().processInstanceId(instance(f)).singleResult().getTaskDefinitionKey()).isEqualTo("COURIER_PICKUP");
    }
    @Test void failedHeadBlocksLaterEventsAndAdminRetryUsesOriginalOperation() throws Exception {
        Fixture f=fixture();doThrow(new IllegalStateException("sensitive-provider-detail")).when(engine).start(eq(f.document),anyString(),anyList());
        for(int i=0;i<5;i++){due(f.outbox);worker.processDocument(f.document);}
        assertThat(db.queryForObject("select status from workflow_outbox where id=?",String.class,f.outbox)).isEqualTo("DEAD_LETTER");
        assertThat(registry.operation(f.actor,f.operation).get("process_sync_status")).isEqualTo("FAILED_RETRYABLE");
        assertThat(((Map<?,?>)((List<?>)registry.operation(f.actor,f.operation).get("updated_states")).getFirst()).get("process_sync_status")).isEqualTo("FAILED_RETRYABLE");
        assertThat(registry.operation(f.actor,f.operation).toString()).doesNotContain("sensitive-provider-detail");
        UUID admin=UUID.randomUUID();String email=admin+"@mfu.ac.th";
        db.update("insert into users(id,email,display_name,is_admin) values (?,?,'Recovery admin',true)",admin,email);
        var cookie=IdentityIntegrationTest.cookie(login.finalizeLogin(IdentityIntegrationTest.google(email,admin.toString(),true,"mfu.ac.th")));
        var csrf=http.perform(get("/api/v1/auth/csrf").cookie(cookie)).andReturn();String token=json.readTree(csrf.getResponse().getContentAsString()).get("token").asText();
        long version=db.queryForObject("select version from workflow_outbox where id=?",Long.class,f.outbox);
        http.perform(get("/api/v1/admin/workflow-operations/"+f.operation).cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(version)).andExpect(jsonPath("$.document_id").doesNotExist());
        String key=UUID.randomUUID().toString(),body=json.writeValueAsString(Map.of("expected_version",version,"reason","Restore engine dependency"));
        for(int i=0;i<2;i++)http.perform(post("/api/v1/admin/workflow-operations/"+f.operation+"/retry").cookie(cookie).header("X-CSRF-TOKEN",token).header("Idempotency-Key",key).contentType("application/json").content(body)).andExpect(status().isAccepted());
        assertThat(db.queryForObject("select count(*) from audit_events where resource_id=? and action='WORKFLOW_RETRY'",Long.class,f.operation)).isEqualTo(1);
        doCallRealMethod().when(engine).start(eq(f.document),anyString(),anyList());worker.processDocument(f.document);
        assertThat(state(f)).isEqualTo("APPLIED");assertThat(instance(f)).isNotBlank();
    }
}
