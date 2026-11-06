package th.ac.mfu.doctracking;

import java.nio.charset.StandardCharsets;
import java.util.*;

import org.flowable.engine.*;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import th.ac.mfu.doctracking.common.ApiException;
import th.ac.mfu.doctracking.common.CommandResult;
import th.ac.mfu.doctracking.domain.RegistryService;
import th.ac.mfu.doctracking.identity.Actor;
import th.ac.mfu.doctracking.workflow.TrackingWorkflowEngine;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties="app.workflow-worker-enabled=false")
class TrackingWorkflowEngineIntegrationTest extends DatabaseTest {
    @Autowired TrackingWorkflowEngine engine;
    @Autowired RuntimeService runtime;
    @Autowired RepositoryService repository;
    @Autowired TaskService tasks;
    @Autowired HistoryService history;
    @Autowired ManagementService management;
    @Autowired JdbcTemplate db;
    @Autowired RegistryService registry;
    @Autowired PlatformTransactionManager manager;

    record Fixture(UUID document,UUID user,List<UUID> steps,ProcessInstance process) {}

    Fixture fixture(String mode) {
        return new TransactionTemplate(manager).execute(tx -> {
            UUID unit=UUID.randomUUID(),user=UUID.randomUUID();
            db.update("insert into units(id,code,name,is_active) values(?,?,?,true)",unit,unit.toString(),"Engine Test Unit");
            db.update("insert into users(id,email,display_name) values(?,?,?)",user,user+"@mfu.ac.th","Engine Test User");
            db.update("insert into unit_memberships(user_id,unit_id,role) values(?,?,'STAFF')",user,unit);
            List<UUID> stations=new ArrayList<>();
            for(int i=0;i<3;i++) {
                UUID station=UUID.randomUUID();stations.add(station);
                db.update("insert into stations(id,unit_id,code,name) values(?,?,?,?)",station,unit,station.toString(),"Station "+i);
            }
            stations.add(stations.getFirst());
            Actor actor=new Actor(user,user+"@mfu.ac.th","Engine Test User",false,
                    List.of(new Actor.Membership(unit,"STAFF")),Set.of(),Set.of(),0);
            var template=registry.createTemplate(actor,new RegistryService.TemplateInput("A B C A",unit,stations,null));
            UUID templateId=UUID.fromString(((Map<?,?>)template.body()).get("id").toString());
            String prior=db.queryForObject("select default_tracking_mode from workflow_policy where id=true",String.class);
            db.update("update workflow_policy set default_tracking_mode=? where id=true",mode);
            CommandResult registration=registry.register(actor,new RegistryService.Registration("Engine book",null,stations.getFirst(),templateId,1));
            db.update("update workflow_policy set default_tracking_mode=? where id=true",prior);
            UUID doc=UUID.fromString(((List<?>)((Map<?,?>)registration.body()).get("document_ids")).getFirst().toString());
            List<UUID> steps=db.queryForList("select id from route_steps where document_id=? order by sequence",UUID.class,doc);
            return new Fixture(doc,user,steps,engine.start(doc,mode,steps));
        });
    }

    Task task(Fixture f,String key,UUID step) {
        Task task=tasks.createTaskQuery().processInstanceId(f.process.getId()).singleResult();
        assertThat(task).isNotNull();
        assertThat(task.getTaskDefinitionKey()).isEqualTo(key);
        assertThat(tasks.getVariableLocal(task.getId(),"routeStepId")).isEqualTo(step.toString());
        return task;
    }
    void apply(Fixture f,String event,int route,UUID from,UUID to,UUID leg) {
        engine.apply(f.process.getId(),f.document,event,f.steps.get(route),from,to,leg);
    }
    UUID leg(Fixture f,int index) {
        UUID id=UUID.randomUUID();
        db.update("insert into courier_legs(id,document_id,courier_user_id,from_step_id,to_step_id) values(?,?,?,?,?)",
                id,f.document,f.user,f.steps.get(index),f.steps.get(index+1));
        return id;
    }
    void transport(Fixture f,int index) {
        task(f,"COURIER_PICKUP",f.steps.get(index));
        UUID leg=leg(f,index);
        apply(f,"COURIER_PICKED_UP",index,f.steps.get(index),f.steps.get(index+1),leg);
        task(f,"COURIER_DROPOFF",f.steps.get(index+1));
        db.update("update courier_legs set ended_at=now() where id=?",leg);
        apply(f,"COURIER_DROPPED_OFF",index+1,f.steps.get(index),f.steps.get(index+1),leg);
    }

    @Test void modelACompletesRepeatedStationRouteWithoutAnyUnitReadyOrReceipt() {
        Fixture f=fixture("MODEL_A");
        for(int i=0;i<f.steps.size()-1;i++) transport(f,i);
        assertThat(runtime.createProcessInstanceQuery().processInstanceId(f.process.getId()).count()).isZero();
        var keys=history.createHistoricTaskInstanceQuery().processInstanceId(f.process.getId()).list().stream().map(t->t.getTaskDefinitionKey()).toList();
        assertThat(keys).containsOnly("COURIER_PICKUP","COURIER_DROPOFF").hasSize(6);
        assertThat(history.createHistoricActivityInstanceQuery().processInstanceId(f.process.getId()).activityId("COURIER_TRACKING_COMPLETE").count()).isEqualTo(1);
    }

    @Test void modelBRequiresEveryReceiptAndReadyIncludingDistinctFinalOriginReturn() {
        Fixture f=fixture("MODEL_B");
        task(f,"ORIGIN_READY",f.steps.getFirst());
        apply(f,"UNIT_READY",0,null,null,null);
        for(int i=0;i<f.steps.size()-1;i++) {
            transport(f,i);
            if(i+1==f.steps.size()-1) {
                task(f,"ORIGIN_RETURN_RECEIVE",f.steps.getLast());
                assertThatThrownBy(()->apply(f,"ORIGIN_RETURN_CONFIRMED",0,null,null,null)).isInstanceOf(ApiException.class);
                apply(f,"ORIGIN_RETURN_CONFIRMED",i+1,null,null,null);
            } else {
                task(f,"UNIT_RECEIVE",f.steps.get(i+1));
                apply(f,"UNIT_RECEIPT_CONFIRMED",i+1,null,null,null);
                task(f,"UNIT_PROCESS_AND_READY",f.steps.get(i+1));
                apply(f,"UNIT_READY",i+1,null,null,null);
            }
        }
        assertThat(runtime.createProcessInstanceQuery().processInstanceId(f.process.getId()).count()).isZero();
        assertThat(history.createHistoricActivityInstanceQuery().processInstanceId(f.process.getId()).activityId("COMPLETED").count()).isEqualTo(1);
    }

    @Test void rejectsWrongDocumentStaleRouteWrongActionWrongLegAndSnapshotTampering() {
        Fixture f=fixture("MODEL_A");
        Task before=task(f,"COURIER_PICKUP",f.steps.getFirst());
        UUID leg=leg(f,0);
        assertThatThrownBy(()->engine.apply(f.process.getId(),UUID.randomUUID(),"COURIER_PICKED_UP",f.steps.getFirst(),f.steps.getFirst(),f.steps.get(1),leg)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->apply(f,"UNIT_READY",0,null,null,null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->apply(f,"COURIER_PICKED_UP",1,f.steps.getFirst(),f.steps.get(1),leg)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->apply(f,"COURIER_PICKED_UP",0,f.steps.getFirst(),f.steps.get(1),UUID.randomUUID())).isInstanceOf(ApiException.class);
        assertThat(task(f,"COURIER_PICKUP",f.steps.getFirst()).getId()).isEqualTo(before.getId());
        runtime.setVariable(f.process.getId(),"trackingMode","MODEL_B");
        assertThatThrownBy(()->apply(f,"COURIER_PICKED_UP",0,f.steps.getFirst(),f.steps.get(1),leg)).isInstanceOf(ApiException.class);
        runtime.setVariable(f.process.getId(),"trackingMode","MODEL_A");
        apply(f,"COURIER_PICKED_UP",0,f.steps.getFirst(),f.steps.get(1),leg);
        Task drop=task(f,"COURIER_DROPOFF",f.steps.get(1));
        assertThatThrownBy(()->apply(f,"COURIER_DROPPED_OFF",1,f.steps.getFirst(),f.steps.get(1),UUID.randomUUID())).isInstanceOf(ApiException.class);
        assertThat(task(f,"COURIER_DROPOFF",f.steps.get(1)).getId()).isEqualTo(drop.getId());
    }

    @Test void realNoninterruptingTimerEmitsReminderWithoutCompletingReceipt() {
        Fixture f=fixture("MODEL_B");
        apply(f,"UNIT_READY",0,null,null,null);transport(f,0);
        Task receipt=task(f,"UNIT_RECEIVE",f.steps.get(1));
        var timers=management.createTimerJobQuery().processInstanceId(f.process.getId()).list();
        assertThat(timers).hasSize(1);
        var job=management.moveTimerToExecutableJob(timers.getFirst().getId());
        management.executeJob(job.getId());
        assertThat(task(f,"UNIT_RECEIVE",f.steps.get(1)).getId()).isEqualTo(receipt.getId());
        assertThat(runtime.getVariable(f.process.getId(),"stepIndex")).isEqualTo(1);
        assertThat(runtime.getVariable(f.process.getId(),"reminderCount")).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from workflow_reminders where document_id=?",Integer.class,f.document)).isEqualTo(1);
    }

    @Test void duplicateDeploymentDoesNotCreateVersionAndNewVersionDoesNotMigrateExistingInstance() throws Exception {
        engine.deploy();
        long before=repository.createProcessDefinitionQuery().processDefinitionKey(TrackingWorkflowEngine.KEY).count();
        engine.deploy();
        assertThat(repository.createProcessDefinitionQuery().processDefinitionKey(TrackingWorkflowEngine.KEY).count()).isEqualTo(before);
        Fixture old=fixture("MODEL_B");
        String xml;
        try(var input=getClass().getResourceAsStream("/processes/document_tracking_v1.bpmn20.xml")) {
            xml=new String(Objects.requireNonNull(input).readAllBytes(),StandardCharsets.UTF_8);
        }
        try {
            repository.createDeployment().name("document-tracking-v1").enableDuplicateFiltering()
                    .addString("processes/document_tracking_v1.bpmn20.xml",xml.replace("Document tracking A/B v1","Document tracking A/B version test")).deploy();
            Fixture newer=fixture("MODEL_B");
            assertThat(newer.process.getProcessDefinitionId()).isNotEqualTo(old.process.getProcessDefinitionId());
            assertThat(runtime.createProcessInstanceQuery().processInstanceId(old.process.getId()).singleResult().getProcessDefinitionId()).isEqualTo(old.process.getProcessDefinitionId());
            apply(old,"UNIT_READY",0,null,null,null);
            task(old,"COURIER_PICKUP",old.steps.getFirst());
        } finally { engine.deploy(); }
    }
}
