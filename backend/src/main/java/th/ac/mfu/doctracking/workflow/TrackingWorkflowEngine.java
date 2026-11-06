package th.ac.mfu.doctracking.workflow;

import java.util.*;

import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.flowable.task.service.delegate.DelegateTask;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import th.ac.mfu.doctracking.common.ApiException;

/** Internal orchestration only: domain commands and the transactional inbox own authorization/deduplication. */
@Component
public class TrackingWorkflowEngine {
    public static final String KEY = "document_tracking_v1";
    private final RepositoryService repository;
    private final RuntimeService runtime;
    private final TaskService tasks;
    private final JdbcTemplate db;
    private final ApplicationEventPublisher events;

    public TrackingWorkflowEngine(RepositoryService repository, RuntimeService runtime, TaskService tasks,
                                  JdbcTemplate db, ApplicationEventPublisher events) {
        this.repository=repository; this.runtime=runtime; this.tasks=tasks; this.db=db; this.events=events;
    }

    @Transactional
    public void deploy() {
        repository.createDeployment().name("document-tracking-v1").enableDuplicateFiltering()
                .addClasspathResource("processes/document_tracking_v1.bpmn20.xml").deploy();
    }

    @Transactional
    public ProcessInstance start(UUID documentId, String mode, List<UUID> routeSteps) {
        if (documentId==null || mode==null || !Set.of("MODEL_A","MODEL_B").contains(mode) || routeSteps==null || routeSteps.size()<2
                || routeSteps.stream().anyMatch(Objects::isNull) || new HashSet<>(routeSteps).size()!=routeSteps.size())
            throw mismatch("Invalid workflow snapshot");
        List<String> route=routeSteps.stream().map(UUID::toString).toList();
        checkSnapshot(documentId,mode,route);
        // Start by definition ID: later deployments never migrate instances already mapped in the app DB.
        var definition=repository.createProcessDefinitionQuery().processDefinitionKey(KEY).latestVersion().singleResult();
        if(definition==null) throw mismatch("Tracking process has not been deployed");
        return runtime.startProcessInstanceById(definition.getId(),documentId.toString(),
                Map.of("documentId",documentId.toString(),"trackingMode",mode,"routeStepIds",new ArrayList<>(route),
                        "stepIndex",0,"activeLegId","","lastLegId","","reminderCount",0));
    }

    @Transactional
    public void apply(String instanceId, UUID documentId, String eventType, UUID routeStepId,
                      UUID fromStepId, UUID toStepId, UUID legId) {
        if(instanceId==null || documentId==null || eventType==null || routeStepId==null) throw mismatch("Workflow correlation fields are required");
        var instance=runtime.createProcessInstanceQuery().processInstanceId(instanceId).singleResult();
        if(instance==null || !documentId.toString().equals(instance.getBusinessKey())) throw mismatch("Document/process mismatch");
        Map<String,Object> vars=runtime.getVariables(instanceId);
        if(!documentId.toString().equals(vars.get("documentId"))) throw mismatch("Document variable mismatch");
        List<String> route=route(vars);
        String mode=Objects.toString(vars.get("trackingMode"),"");
        checkSnapshot(documentId,mode,route);
        int index=((Number)vars.get("stepIndex")).intValue();
        if(index<0 || index>=route.size()) throw mismatch("Invalid route iterator");
        List<Task> active=tasks.createTaskQuery().processInstanceId(instanceId).active().list();
        if(active.size()!=1) throw mismatch("Expected exactly one waiting tracking task");
        Task task=active.getFirst();
        String key=task.getTaskDefinitionKey();
        String expected=switch(eventType) {
            case "UNIT_READY" -> index==0 ? "ORIGIN_READY" : "UNIT_PROCESS_AND_READY";
            case "COURIER_PICKED_UP" -> "COURIER_PICKUP";
            case "COURIER_DROPPED_OFF" -> "COURIER_DROPOFF";
            case "UNIT_RECEIPT_CONFIRMED" -> "UNIT_RECEIVE";
            case "ORIGIN_RETURN_CONFIRMED" -> "ORIGIN_RETURN_RECEIVE";
            default -> throw mismatch("Event is not a P2 workflow transition");
        };
        if(!expected.equals(key)) throw mismatch("Event does not match waiting task");
        if(mode.equals("MODEL_A") && !eventType.startsWith("COURIER_")) throw mismatch("Model A cannot accept unit events");
        Map<String,Object> local=tasks.getVariablesLocal(task.getId());
        int taskIndex=key.equals("COURIER_DROPOFF")?index+1:index;
        if(taskIndex>=route.size() || !route.get(taskIndex).equals(local.get("routeStepId"))) throw mismatch("Task route iterator mismatch");
        if(!documentId.toString().equals(local.get("documentId")) || !Objects.equals(string(routeStepId),local.get("routeStepId")))
            throw mismatch("Event does not match the exact document route step");
        boolean courier=eventType.startsWith("COURIER_");
        if(courier) {
            if(index+1>=route.size() || !route.get(index).equals(string(fromStepId)) || !route.get(index+1).equals(string(toStepId)) || legId==null)
                throw mismatch("Courier leg route mismatch");
            if(!route.get(index).equals(local.get("fromStepId")) || !route.get(index+1).equals(local.get("toStepId")))
                throw mismatch("Courier task binding mismatch");
            String legState=eventType.equals("COURIER_PICKED_UP")?"ended_at is null":"ended_at is not null";
            Integer count=db.queryForObject("select count(*) from courier_legs where id=? and document_id=? and from_step_id=? and to_step_id=? and "+legState,
                    Integer.class,legId,documentId,fromStepId,toStepId);
            if(count==null || count!=1) throw mismatch("Courier leg does not belong to this document and route");
            if(eventType.equals("COURIER_DROPPED_OFF") && !legId.toString().equals(local.get("legId")))
                throw mismatch("Drop-off does not match the pickup leg");
        } else {
            if(toStepId!=null && !route.get(index).equals(toStepId.toString())) throw mismatch("Receipt destination mismatch");
            if(fromStepId!=null && (index==0 || !route.get(index-1).equals(fromStepId.toString()))) throw mismatch("Receipt source mismatch");
            if(legId!=null && !legId.toString().equals(vars.get("lastLegId"))) throw mismatch("Receipt leg mismatch");
        }
        Map<String,Object> changes=new HashMap<>();
        if(eventType.equals("COURIER_PICKED_UP")) changes.put("activeLegId",legId.toString());
        if(eventType.equals("COURIER_DROPPED_OFF")) changes.put("lastLegId",legId.toString());
        tasks.complete(task.getId(),changes);
    }

    private void checkSnapshot(UUID documentId,String mode,List<String> route) {
        List<String> modes=db.queryForList("select tracking_mode from documents where id=?",String.class,documentId);
        List<String> stored=db.queryForList("select id::text from route_steps where document_id=? order by sequence",String.class,documentId);
        if(modes.size()!=1 || !modes.getFirst().equals(mode) || !stored.equals(route)) throw mismatch("Immutable document snapshot mismatch");
    }

    private static List<String> route(Map<String,Object> vars) {
        if(!(vars.get("routeStepIds") instanceof List<?> values) || values.stream().anyMatch(v -> !(v instanceof String)))
            throw mismatch("Invalid route snapshot variable");
        return values.stream().map(String.class::cast).toList();
    }

    /** BPMN create listener binds IDs to this task, including repeated visits to the same station. */
    public void bindTask(DelegateTask task) {
        var vars=task.getVariables();
        List<String> route=route(vars);
        int index=((Number)vars.get("stepIndex")).intValue();
        String key=task.getTaskDefinitionKey();
        int target=key.equals("COURIER_DROPOFF")?index+1:index;
        task.setVariableLocal("documentId",vars.get("documentId"));
        task.setVariableLocal("routeStepId",route.get(target));
        if(key.equals("COURIER_PICKUP") || key.equals("COURIER_DROPOFF")) {
            task.setVariableLocal("fromStepId",route.get(index));
            task.setVariableLocal("toStepId",route.get(index+1));
        }
        if(key.equals("COURIER_DROPOFF")) task.setVariableLocal("legId",vars.get("activeLegId"));
    }

    public void advance(DelegateExecution execution) {
        execution.setVariable("stepIndex",((Number)execution.getVariable("stepIndex")).intValue()+1);
        execution.setVariable("activeLegId","");
    }

    public record Reminder(String documentId,String taskKey,String routeStepId,String executionId) {}

    /** Noninterrupting timer: emit a reminder in this transaction and leave the human task open. */
    public void remind(DelegateExecution execution) {
        Task task=tasks.createTaskQuery().processInstanceId(execution.getProcessInstanceId()).active().singleResult();
        if(task==null) throw mismatch("Reminder has no waiting task");
        events.publishEvent(new Reminder((String)execution.getVariable("documentId"),task.getTaskDefinitionKey(),
                (String)tasks.getVariableLocal(task.getId(),"routeStepId"),execution.getId()));
        execution.setVariable("reminderCount",((Number)execution.getVariable("reminderCount")).intValue()+1);
    }

    private static String string(UUID value) { return value==null?null:value.toString(); }
    private static ApiException mismatch(String message) { return new ApiException(409,"WORKFLOW_MISMATCH",message); }
}
