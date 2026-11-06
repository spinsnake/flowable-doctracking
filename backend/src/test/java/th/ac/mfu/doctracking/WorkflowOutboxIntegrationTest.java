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
