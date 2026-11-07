package th.ac.mfu.doctracking;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.*;
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
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class WorkflowPolicyIntegrationTest extends DatabaseTest {
    @Autowired JdbcTemplate db;
    @Autowired CommandService commands;
    @Autowired RegistryService registry;
    @Autowired WorkflowPolicyService policy;
    @Autowired WorkflowOutboxWorker worker;
    @Autowired RuntimeService runtime;
    @Autowired TaskService tasks;
    @Autowired LoginService login;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    Actor admin,origin;
    UUID unit,station,otherStation,template;
    String key() { return UUID.randomUUID().toString(); }
    Actor actor(boolean admin,UUID unit) {
        UUID id=UUID.randomUUID();String email=id+"@mfu.ac.th";
        db.update("insert into users(id,email,display_name,is_admin) values(?,?,?,?)",id,email,"Policy Test",admin);
        if(unit!=null) db.update("insert into unit_memberships(user_id,unit_id,role) values(?,?,'STAFF')",id,unit);
        return new Actor(id,email,"Policy Test",admin,unit==null?List.of():List.of(new Actor.Membership(unit,"STAFF")),Set.of(),Set.of(),0);
    }
    @BeforeEach void fixture() {
        db.update("update workflow_policy set default_tracking_mode='MODEL_B' where id=true");
        unit=UUID.randomUUID();station=UUID.randomUUID();otherStation=UUID.randomUUID();
        db.update("insert into units(id,code,name,is_active) values(?,?,?,true)",unit,key(),"Policy Unit");
        for(UUID id:List.of(station,otherStation)) db.update("insert into stations(id,unit_id,code,name) values(?,?,?,?)",id,unit,key(),"Policy Station");
        admin=actor(true,null);origin=actor(false,unit);
        var input=new RegistryService.TemplateInput("Policy route",unit,List.of(station,otherStation,station),null);
        var result=commands.execute(origin,"template:create",key(),input,()->registry.createTemplate(origin,input));
        template=UUID.fromString(((Map<?,?>)result.body()).get("id").toString());
    }
    @AfterEach void restoreDefault() { db.update("update workflow_policy set default_tracking_mode='MODEL_B' where id=true"); }
    long version() { return db.queryForObject("select version from workflow_policy where id=true",Long.class); }
    WorkflowPolicyService.Change changeInput(String mode) { return new WorkflowPolicyService.Change(version(),mode,"New registrations only"); }
    CommandResult change(WorkflowPolicyService.Change input,String key) {
        policy.authorize(admin);
        return commands.execute(admin,"workflow:policy",key,input,()->policy.change(admin,input));
    }
    RegistryService.Registration registration(int templateVersion) { return new RegistryService.Registration("Policy snapshot",null,station,template,templateVersion); }
    UUID register(int templateVersion) {
        var input=registration(templateVersion);
        var result=commands.execute(origin,"document:register",key(),input,()->registry.register(origin,input));
        UUID id=UUID.fromString(((List<?>)((Map<?,?>)result.body()).get("document_ids")).getFirst().toString());
        worker.processDocument(id);
        assertThat(db.queryForObject("select process_sync_status from documents where id=?",String.class,id)).isEqualTo("APPLIED");
        return id;
    }
    Map<String,Object> snapshot(UUID document) {
        var snapshot=new LinkedHashMap<>(db.queryForMap("select tracking_mode,policy_version,template_version,current_route_step_id,business_state,version from documents where id=?",document));
        var instance=db.queryForMap("select process_instance_id,process_definition_id,process_definition_version from workflow_instances where document_id=?",document);
        snapshot.putAll(instance);String process=instance.get("process_instance_id").toString();
        snapshot.put("task_id",tasks.createTaskQuery().processInstanceId(process).singleResult().getId());
        snapshot.put("route",db.queryForList("select id,sequence,station_id from route_steps where document_id=? order by sequence",document));
        snapshot.put("events",db.queryForList("select id,event_type from document_events where document_id=? order by sequence",document));
        snapshot.put("mode_variable",runtime.getVariable(process,"trackingMode"));
        return snapshot;
    }
    @Test void bothDefaultDirectionsLeaveConcurrentInstancesAndTemplateSnapshotsUnchanged() {
        long initialVersion=version();UUID b=register(1);var beforeB=snapshot(b);
        change(changeInput("MODEL_A"),key());UUID a=register(1);var beforeA=snapshot(a);
        assertThat(beforeB).containsEntry("tracking_mode","MODEL_B").containsEntry("policy_version",initialVersion).containsEntry("mode_variable","MODEL_B");
        assertThat(beforeA).containsEntry("tracking_mode","MODEL_A").containsEntry("policy_version",initialVersion+1).containsEntry("mode_variable","MODEL_A");
        assertThat(snapshot(b)).isEqualTo(beforeB);
        var revision=new RegistryService.TemplateInput("Revised route",unit,List.of(station,otherStation,otherStation,station),1);
        commands.execute(origin,"template:"+template,key(),revision,()->registry.reviseTemplate(origin,template,revision));
        change(changeInput("MODEL_B"),key());UUID newB=register(2);
        assertThat(snapshot(a)).isEqualTo(beforeA);assertThat(snapshot(b)).isEqualTo(beforeB);
        assertThat(snapshot(newB)).containsEntry("tracking_mode","MODEL_B").containsEntry("policy_version",initialVersion+2).containsEntry("template_version",2);
        assertThat(db.queryForObject("select count(*) from route_steps where document_id=?",Long.class,newB)).isEqualTo(4);
        assertThat(db.queryForObject("select count(*) from document_events where document_id in (?,?) and event_type in ('UNIT_READY','UNIT_RECEIPT_CONFIRMED')",Long.class,a,b)).isZero();
    }
    @Test void auditAndIdempotencyAreAtomicAndRetryAfterRollbackDoesNotDuplicateChanges() throws Exception {
        var input=changeInput("MODEL_A");String key=key();long before=version();
        assertThatThrownBy(()->commands.execute(admin,"workflow:policy",key,input,()->{
            policy.change(admin,input);throw new IllegalStateException("Injected audit/receipt rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(version()).isEqualTo(before);
        assertThat(db.queryForObject("select count(*) from audit_events where actor_id=? and action='WORKFLOW_POLICY_CHANGED'",Long.class,admin.id())).isZero();
        var changed=change(input,key);var replay=change(input,key);
        assertThat(json.readTree(json.writeValueAsString(replay.body()))).isEqualTo(json.readTree(json.writeValueAsString(changed.body())));
        assertThat(version()).isEqualTo(before+1);
        var audit=db.queryForMap("select actor_id,reason,recorded_at from audit_events where actor_id=? and action='WORKFLOW_POLICY_CHANGED'",admin.id());
        assertThat(audit).containsEntry("actor_id",admin.id());assertThat(audit.get("recorded_at")).isNotNull();
        var details=json.readTree(audit.get("reason").toString());
        assertThat(details.get("old_tracking_mode").asText()).isEqualTo("MODEL_B");
        assertThat(details.get("new_tracking_mode").asText()).isEqualTo("MODEL_A");
        assertThat(details.get("old_version").asLong()).isEqualTo(before);
        assertThat(details.get("new_version").asLong()).isEqualTo(before+1);
        assertThat(details.get("reason").asText()).isEqualTo(input.reason());
        assertThat(policy.get(admin)).containsEntry("changed_by",admin.id()).containsEntry("change_reason",input.reason());
        assertThatThrownBy(()->change(new WorkflowPolicyService.Change(before,"MODEL_B","Different request"),key)).isInstanceOf(ApiException.class).satisfies(e->assertThat(((ApiException)e).code()).isEqualTo("IDEMPOTENCY_MISMATCH"));
        assertThatThrownBy(()->change(input,key())).isInstanceOf(ApiException.class).satisfies(e->assertThat(((ApiException)e).code()).isEqualTo("STALE_VERSION"));
        assertThatThrownBy(()->change(new WorkflowPolicyService.Change(version(),"MODEL_A","No change"),key())).isInstanceOf(ApiException.class).satisfies(e->assertThat(((ApiException)e).code()).isEqualTo("POLICY_UNCHANGED"));
        db.update("update users set is_admin=false where id=?",admin.id());
        assertThatThrownBy(()->{policy.authorize(admin);change(input,key);}).isInstanceOf(ApiException.class);
    }
    @Test void registrationPolicyShareLockSerializesWithDefaultChange() throws Exception {
        var input=changeInput("MODEL_A");long old=version();
        var registered=new CountDownLatch(1);var commitRegistration=new CountDownLatch(1);var switchStarted=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var registration=pool.submit(()->{
                var request=registration(1);
                return commands.execute(origin,"document:register",key(),request,()->{
                    var result=registry.register(origin,request);registered.countDown();
                    try { if(!commitRegistration.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Registration release timeout"); }
                    catch(InterruptedException e) { Thread.currentThread().interrupt();throw new IllegalStateException(e); }
                    return result;
                });
            });
            assertThat(registered.await(10,TimeUnit.SECONDS)).isTrue();
            var switched=pool.submit(()->{switchStarted.countDown();return change(input,key());});
            assertThat(switchStarted.await(5,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->switched.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            commitRegistration.countDown();
            var result=registration.get(10,TimeUnit.SECONDS);switched.get(10,TimeUnit.SECONDS);
            UUID id=UUID.fromString(((List<?>)((Map<?,?>)result.body()).get("document_ids")).getFirst().toString());
            assertThat(db.queryForMap("select tracking_mode,policy_version from documents where id=?",id)).containsEntry("tracking_mode","MODEL_B").containsEntry("policy_version",old);
            UUID next=register(1);
            assertThat(db.queryForMap("select tracking_mode,policy_version from documents where id=?",next)).containsEntry("tracking_mode","MODEL_A").containsEntry("policy_version",old+1);
        } finally { commitRegistration.countDown();pool.shutdownNow(); }
    }
    Cookie session(Actor actor) { return IdentityIntegrationTest.cookie(login.finalizeLogin(IdentityIntegrationTest.google(actor.email(),actor.id().toString(),true,"mfu.ac.th"))); }
    String csrf(Cookie session) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/auth/csrf").cookie(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
    }
    @Test void httpEnforcesAdminCsrfValidationAndRejectsModeMassAssignment() throws Exception {
        String path="/api/v1/admin/workflow-policy";Cookie adminSession=session(admin),staffSession=session(origin);
        String token=csrf(adminSession);var input=changeInput("MODEL_A");String body=json.writeValueAsString(input);
        mvc.perform(get(path).cookie(staffSession)).andExpect(status().isForbidden());
        mvc.perform(put(path).cookie(staffSession).header("X-CSRF-TOKEN",csrf(staffSession)).header("Idempotency-Key",key()).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(put(path).cookie(adminSession).header("Idempotency-Key",key()).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(put(path).cookie(adminSession).header("X-CSRF-TOKEN",token).header("Idempotency-Key",key()).contentType("application/json").content(body.replace("New registrations only"," "))).andExpect(status().isUnprocessableEntity());
        mvc.perform(put(path).cookie(adminSession).header("X-CSRF-TOKEN",token).header("Idempotency-Key",key()).contentType("application/json").content(body.substring(0,body.length()-1)+",\"document_ids\":[]}" )).andExpect(status().isBadRequest());
        mvc.perform(put(path).cookie(adminSession).header("X-CSRF-TOKEN",token).header("Idempotency-Key",key()).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.default_tracking_mode").value("MODEL_A")).andExpect(jsonPath("$.changed_by").value(admin.id().toString()));
        UUID document=register(1);var before=snapshot(document);
        String registration=json.writeValueAsString(registration(1));
        mvc.perform(post("/api/v1/documents").cookie(staffSession).header("X-CSRF-TOKEN",csrf(staffSession)).header("Idempotency-Key",key()).contentType("application/json").content(registration.substring(0,registration.length()-1)+",\"tracking_mode\":\"MODEL_B\"}" )).andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/documents/"+document).cookie(adminSession).header("X-CSRF-TOKEN",token).header("Idempotency-Key",key()).contentType("application/json").content("{\"tracking_mode\":\"MODEL_B\"}")).andExpect(status().isMethodNotAllowed());
        assertThat(snapshot(document)).isEqualTo(before);
    }
}
