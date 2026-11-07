package th.ac.mfu.doctracking.workflow;

import java.util.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import th.ac.mfu.doctracking.common.ApiException;
import th.ac.mfu.doctracking.common.CommandResult;
import th.ac.mfu.doctracking.identity.Actor;

/** Read business/engine state under the worker's document lock; write diagnostics only. */
@Service
public class WorkflowReconciliationService {
    private static final Logger log=LoggerFactory.getLogger(WorkflowReconciliationService.class);
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final RuntimeService runtime;
    private final TaskService tasks;
    private final HistoryService history;
    private final RepositoryService repository;
    private final TransactionTemplate tx;
    private final boolean enabled;
    private volatile boolean ready;
    private UUID cursor;

    public WorkflowReconciliationService(JdbcTemplate db,ObjectMapper json,RuntimeService runtime,TaskService tasks,
            HistoryService history,RepositoryService repository,PlatformTransactionManager manager,
            @Value("${app.reconciliation-enabled:true}") boolean enabled) {
        this.db=db;this.json=json;this.runtime=runtime;this.tasks=tasks;this.history=history;
        this.repository=repository;this.tx=new TransactionTemplate(manager);this.enabled=enabled;
    }
    public record Scan(String reason,UUID document_id,Integer limit) {}
    public void authorize(Actor actor) {
        actor.requireAdmin();
        if(db.queryForList("select id from users where id=? and is_active and is_admin and session_version=?",actor.id(),actor.sessionVersion()).isEmpty())
            throw new ApiException(403,"FORBIDDEN","Current Central Admin permission required");
    }
    public Object findings(Actor actor,String state,UUID document,int limit) {
        authorize(actor);
        if(!Set.of("OPEN","RESOLVED").contains(state) || limit<1 || limit>200)
            throw new ApiException(422,"INVALID_FILTER","Use OPEN or RESOLVED and limit 1 to 200");
        return Map.of("items",db.queryForList("""
            select document_id,code,state,first_seen_at,last_seen_at,resolved_at,sightings
            from workflow_reconciliation_findings where state=? and (?::uuid is null or document_id=?::uuid)
            order by last_seen_at desc,document_id,code limit ?
            """,state,document,document,limit));
    }
    /** Called inside CommandService: permission, CSRF, idempotency and audit apply to rescans too. */
    public CommandResult scan(Actor actor,Scan input) {
        authorize(actor);
        int limit=input==null || input.limit()==null?100:input.limit();
        if(input==null || input.reason()==null || input.reason().isBlank() || input.reason().length()>1000 || limit<1 || limit>200)
            throw new ApiException(422,"INVALID_SCAN","A reason and limit 1 to 200 are required");
        var ids=db.queryForList("select id from documents where (?::uuid is null or id=?::uuid) order by id limit ?",UUID.class,input.document_id(),input.document_id(),limit);
        if(input.document_id()!=null && ids.isEmpty()) throw new ApiException(404,"NOT_FOUND","Document not found");
        int scanned=0,pending=0,mismatches=0;
        for(UUID id:ids) {
            var outcome=check(id);
            if(outcome==null)continue;
            scanned++;if(outcome.pending())pending++;if(!outcome.codes().isEmpty())mismatches++;
        }
        db.update("insert into audit_events(actor_id,action,resource_id,reason) values(?,'WORKFLOW_RECONCILIATION_SCANNED',?,?)",
                actor.id(),input.document_id(),input.reason().trim());
        return new CommandResult(200,Map.of("scanned",scanned,"pending",pending,"mismatches",mismatches,"limit",limit));
    }
    @EventListener(ApplicationReadyEvent.class) public void applicationReady() {ready=true;}
    @Scheduled(fixedDelayString="${app.reconciliation-poll-ms:60000}")
    public synchronized void scheduled() {
        if(!enabled || !ready)return;
        var ids=db.queryForList("select id from documents where (?::uuid is null or id>?::uuid) order by id limit 100",UUID.class,cursor,cursor);
        if(ids.isEmpty()){cursor=null;return;}
        // Each bounded transaction releases the lock before checking another document. Other
        // instances/workers skip the locked document instead of seeing intermediate projections.
        for(UUID id:ids) {
            try {tx.executeWithoutResult(status->check(id));}
            catch(RuntimeException failure) {
                // Never log engine/provider exception messages or serialized process variables.
                log.warn("event=workflow_reconciliation_failed document_id={} code=RECONCILIATION_CHECK_FAILED",id);
                // The failed transaction rolled back, so it cannot resolve existing findings.
                // Isolate a safe operational finding; even DB unavailability must not pin the
                // cursor to one permanently corrupt document and starve later documents.
                try {tx.executeWithoutResult(status->recordFinding(id,"RECONCILIATION_CHECK_FAILED"));}
                catch(RuntimeException unavailable) { /* The fixed-code log remains the diagnostic. */ }
            }
            cursor=id;
        }
    }
    private record Result(boolean pending,Set<String> codes) {}
    private Result check(UUID id) {
        var rows=db.queryForList("select * from documents where id=? for update skip locked",id);
        if(rows.isEmpty())return null;
        var doc=rows.getFirst();var codes=new TreeSet<String>();
        var route=db.queryForList("select id::text from route_steps where document_id=? order by sequence",String.class,id);
        var ledger=db.queryForList("select * from document_events where document_id=? order by sequence",id);
        boolean pending=db.queryForObject("select exists(select 1 from workflow_outbox where document_id=? and status<>'APPLIED')",Boolean.class,id);
        if(ledger.isEmpty() || ((Number)doc.get("event_sequence")).longValue()!=ledger.size())codes.add("LEDGER_SEQUENCE_MISMATCH");
        long sequence=0;
        for(var event:ledger)if(((Number)event.get("sequence")).longValue()!=++sequence)codes.add("LEDGER_SEQUENCE_MISMATCH");
        try {checkProjection(doc,route,ledger,codes);} catch(IllegalArgumentException error){codes.add("LEDGER_INVALID");}
        for(var delivery:db.queryForList("""
                select o.*,e.document_id as ledger_document,e.sequence as ledger_sequence,e.event_type,e.payload as ledger_payload,
                       i.event_id as inbox_event from workflow_outbox o join document_events e on e.id=o.event_id
                left join workflow_inbox i on i.event_id=o.event_id where o.document_id=?
                """,id)) {
            boolean canonical;
            try {
                canonical=id.equals(delivery.get("ledger_document")) && delivery.get("sequence").equals(delivery.get("ledger_sequence"))
                        && delivery.get("action").equals(delivery.get("event_type"))
                        && decode(delivery.get("payload")).equals(decode(delivery.get("ledger_payload")));
            } catch(IllegalArgumentException invalid) {canonical=false;}
            if(!canonical)codes.add("DELIVERY_LEDGER_MISMATCH");
            if("APPLIED".equals(delivery.get("status")) && delivery.get("inbox_event")==null)codes.add("DELIVERY_INBOX_MISMATCH");
        }
        if(!pending && !"APPLIED".equals(doc.get("process_sync_status")))codes.add("PROJECTION_SYNC_MISMATCH");
        if(pending && "APPLIED".equals(doc.get("process_sync_status")))codes.add("PROJECTION_SYNC_MISMATCH");
        checkCustody(doc,codes);
        if(!pending)checkEngine(doc,route,codes);
        for(String code:codes)recordFinding(id,code);
        for(var finding:db.queryForList("select code from workflow_reconciliation_findings where document_id=? and state='OPEN'",id)) {
            String code=(String)finding.get("code");
            if(!codes.contains(code) && !(pending && code.startsWith("ENGINE_")))
                db.update("update workflow_reconciliation_findings set state='RESOLVED',resolved_at=now() where document_id=? and code=?",id,code);
        }
        return new Result(pending,codes);
    }
    private void checkProjection(Map<String,Object> doc,List<String> route,List<Map<String,Object>> ledger,Set<String> codes) {
        if(route.size()<2){codes.add("PROJECTION_ROUTE_MISMATCH");return;}
        boolean b="MODEL_B".equals(doc.get("tracking_mode"));
        String state=b?"WAIT_ORIGIN_READY":"AWAIT_PHYSICAL_PICKUP",previous=null;
        String current=route.getFirst(),reported=current,pending=null,reservation=null,leg=null;
        boolean registered=false;
        for(var event:ledger) {
            String type=(String)event.get("event_type"),step=str(event.get("route_step_id"));
            var payload=decode(event.get("payload"));
            switch(type) {
                case "DOCUMENT_REGISTERED" -> {
                    registered=true;
                    if(!current.equals(step) || !str(doc.get("id")).equals(str(payload.get("document_id")))
                            || !doc.get("tracking_mode").equals(payload.get("tracking_mode"))
                            || !str(doc.get("policy_version")).equals(str(payload.get("policy_version"))))codes.add("LEDGER_SNAPSHOT_MISMATCH");
                }
                case "UNIT_READY" -> state="READY_FOR_PICKUP";
                case "PICKUP_RESERVED" -> {previous=state;state="PICKUP_RESERVED";reservation=str(payload.get("reservation_id"));}
                case "PICKUP_RESERVATION_CANCELLED","PICKUP_RESERVATION_EXPIRED" -> {state=previous;reservation=null;}
                case "COURIER_PICKED_UP" -> {state="IN_TRANSIT";reservation=null;leg=str(payload.get("leg_id"));}
                case "COURIER_DROPPED_OFF" -> {
                    reported=step;leg=null;
                    boolean last=route.getLast().equals(step);
                    state=b?(last?"PENDING_ORIGIN_RETURN_RECEIPT":"PENDING_UNIT_RECEIPT"):(last?"COURIER_TRACKING_COMPLETE":"DROPPED_OFF_UNCONFIRMED");
                    if(b)pending=step;else current=step;
                }
                case "UNIT_RECEIPT_CONFIRMED" -> {state="UNIT_PROCESSING";current=step;reported=step;pending=null;}
                case "ORIGIN_RETURN_CONFIRMED" -> {state="COMPLETED";current=step;reported=step;pending=null;}
                case "EXCEPTION_OPENED" -> {
                    if(!Objects.equals(state,payload.get("previous_state")))codes.add("LEDGER_EXCEPTION_MISMATCH");
                    state="EXCEPTION";
                }
                case "OBSERVED_AT","UNOBSERVED_TRANSFER","EVIDENCE_ATTACHED" -> { /* local evidence never changes accepted custody */ }
                default -> codes.add("LEDGER_UNSUPPORTED_EVENT");
            }
        }
        if(!registered)codes.add("LEDGER_REGISTRATION_MISSING");
        if(!Objects.equals(state,doc.get("business_state")) || !Objects.equals(current,str(doc.get("current_route_step_id")))
                || !Objects.equals(reported,str(doc.get("reported_location_step_id"))) || !Objects.equals(pending,str(doc.get("pending_receipt_step_id")))
                || !Objects.equals(leg,str(doc.get("active_leg_id"))) || !Objects.equals(reservation,str(doc.get("active_reservation_id"))))
            codes.add("PROJECTION_LEDGER_MISMATCH");
    }
    private void checkCustody(Map<String,Object> doc,Set<String> codes) {
        Object id=doc.get("id");
        var legs=db.queryForList("select * from courier_legs where document_id=? and ended_at is null",id);
        var reservations=db.queryForList("select i.*,r.status from reservation_items i join reservations r on r.id=i.reservation_id where i.document_id=? and i.is_active",id);
        var direct=db.queryForList("select id from direct_transfers where document_id=? and received_at is null",id);
        if(legs.size()+reservations.size()+direct.size()>1)codes.add("CUSTODY_CHANNEL_CONFLICT");
        if(legs.isEmpty()?doc.get("active_leg_id")!=null:legs.size()!=1 || !legs.getFirst().get("id").equals(doc.get("active_leg_id"))
                || !legs.getFirst().get("from_step_id").equals(doc.get("current_route_step_id")))codes.add("CUSTODY_LEG_MISMATCH");
        if(reservations.isEmpty()?doc.get("active_reservation_id")!=null:reservations.size()!=1
                || !reservations.getFirst().get("reservation_id").equals(doc.get("active_reservation_id"))
                || !"ACTIVE".equals(reservations.getFirst().get("status"))
                || !reservations.getFirst().get("route_step_id").equals(doc.get("current_route_step_id")))codes.add("CUSTODY_RESERVATION_MISMATCH");
        if(!direct.isEmpty())codes.add("CUSTODY_UNSUPPORTED_DIRECT_TRANSFER"); // P5 direct workflow is not implemented.
    }
    private void checkEngine(Map<String,Object> doc,List<String> route,Set<String> codes) {
        var mappings=db.queryForList("select * from workflow_instances where document_id=?",doc.get("id"));
        if(mappings.size()!=1){codes.add("ENGINE_MAPPING_MISSING");return;}
        var mapping=mappings.getFirst();String instance=(String)mapping.get("process_instance_id"),definition=(String)mapping.get("process_definition_id");
        var pinned=repository.createProcessDefinitionQuery().processDefinitionId(definition).singleResult();
        if(pinned==null || !TrackingWorkflowEngine.KEY.equals(pinned.getKey())
                || pinned.getVersion()!=((Number)mapping.get("process_definition_version")).intValue())codes.add("ENGINE_DEFINITION_MISMATCH");
        var process=runtime.createProcessInstanceQuery().processInstanceId(instance).singleResult();
        String state=(String)doc.get("business_state");
        var cases=db.queryForList("select previous_state from tracking_exceptions where document_id=? and resolved_at is null",doc.get("id"));
        if("EXCEPTION".equals(state)) {
            if(cases.size()!=1){codes.add("PROJECTION_EXCEPTION_MISMATCH");return;}
            state=(String)cases.getFirst().get("previous_state");
            var opened=db.queryForList("select payload->>'previous_state' as previous_state from document_events where document_id=? and event_type='EXCEPTION_OPENED' order by sequence desc limit 1",doc.get("id"));
            if(opened.size()!=1 || !Objects.equals(state,opened.getFirst().get("previous_state")))codes.add("PROJECTION_EXCEPTION_MISMATCH");
        } else if(!cases.isEmpty())codes.add("PROJECTION_EXCEPTION_MISMATCH");
        if("PICKUP_RESERVED".equals(state)) {
            var reserved=db.queryForList("select previous_state from reservation_items where document_id=? and is_active",doc.get("id"));
            if(reserved.size()!=1){codes.add("ENGINE_TASK_MISMATCH");return;}
            state=(String)reserved.getFirst().get("previous_state");
        }
        boolean terminal=Set.of("COMPLETED","COURIER_TRACKING_COMPLETE").contains(state);
        var active=tasks.createTaskQuery().processInstanceId(instance).active().list();
        if(terminal) {
            var ended=history.createHistoricProcessInstanceQuery().processInstanceId(instance).finished().singleResult();
            if(process!=null || !active.isEmpty() || ended==null || ended.getDeleteReason()!=null
                    || !definition.equals(ended.getProcessDefinitionId()) || !str(doc.get("id")).equals(ended.getBusinessKey()))codes.add("ENGINE_TERMINAL_MISMATCH");
            String end="COMPLETED".equals(state)?"COMPLETED":"COURIER_TRACKING_COMPLETE";
            if(history.createHistoricActivityInstanceQuery().processInstanceId(instance).activityId(end).finished().count()!=1)
                codes.add("ENGINE_TERMINAL_MISMATCH");
            return;
        }
        if(process==null){codes.add("ENGINE_INSTANCE_MISSING");return;}
        if(!definition.equals(process.getProcessDefinitionId()) || !str(doc.get("id")).equals(process.getBusinessKey()))codes.add("ENGINE_DEFINITION_MISMATCH");
        var vars=runtime.getVariables(instance);
        if(!str(doc.get("id")).equals(vars.get("documentId")) || !doc.get("tracking_mode").equals(vars.get("trackingMode"))
                || !route.equals(vars.get("routeStepIds")))codes.add("ENGINE_SNAPSHOT_MISMATCH");
        String taskKey=switch(state) {
            case "WAIT_ORIGIN_READY" -> "ORIGIN_READY";
            case "AWAIT_PHYSICAL_PICKUP","READY_FOR_PICKUP","DROPPED_OFF_UNCONFIRMED" -> "COURIER_PICKUP";
            case "IN_TRANSIT" -> "COURIER_DROPOFF";
            case "PENDING_UNIT_RECEIPT" -> "UNIT_RECEIVE";
            case "PENDING_ORIGIN_RETURN_RECEIPT" -> "ORIGIN_RETURN_RECEIVE";
            case "UNIT_PROCESSING" -> "UNIT_PROCESS_AND_READY";
            default -> "UNSUPPORTED";
        };
        String step=str(doc.get("pending_receipt_step_id")!=null?doc.get("pending_receipt_step_id"):doc.get("current_route_step_id"));
        int index=route.indexOf(step);
        if(index<0 || !(vars.get("stepIndex") instanceof Number n) || n.intValue()!=index)codes.add("ENGINE_ROUTE_MISMATCH");
        if(active.size()!=1 || !taskKey.equals(active.getFirst().getTaskDefinitionKey())){codes.add("ENGINE_TASK_MISMATCH");return;}
        var local=tasks.getVariablesLocal(active.getFirst().getId());
        String taskStep=taskKey.equals("COURIER_DROPOFF") && index+1<route.size()?route.get(index+1):step;
        if(!str(doc.get("id")).equals(local.get("documentId")) || !Objects.equals(taskStep,local.get("routeStepId")))codes.add("ENGINE_TASK_BINDING_MISMATCH");
        if(taskKey.startsWith("COURIER_")) {
            if(index<0 || index+1>=route.size() || !route.get(index).equals(local.get("fromStepId"))
                    || !route.get(index+1).equals(local.get("toStepId")))codes.add("ENGINE_TASK_BINDING_MISMATCH");
        }
        String activeLeg=doc.get("active_leg_id")==null?"":str(doc.get("active_leg_id"));
        if(!activeLeg.equals(vars.get("activeLegId")) || taskKey.equals("COURIER_DROPOFF") && !activeLeg.equals(local.get("legId")))codes.add("ENGINE_CUSTODY_MISMATCH");
    }
    private Map<String,Object> decode(Object value) {
        try {
            Map<String,Object> result=json.readValue(value.toString(),new TypeReference<>(){});
            if(result==null)throw new IllegalArgumentException("Object required");
            return result;
        } catch(Exception error){throw new IllegalArgumentException("Invalid ledger JSON");}
    }
    private void recordFinding(UUID id,String code) {
        db.update("""
            insert into workflow_reconciliation_findings(document_id,code,state) values(?,?,'OPEN')
            on conflict(document_id,code) do update set state='OPEN',last_seen_at=now(),resolved_at=null,
              sightings=workflow_reconciliation_findings.sightings+1
            """,id,code);
    }
    private static String str(Object value) {return value==null?null:value.toString();}
}
