package th.ac.mfu.doctracking.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.flowable.engine.TaskService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import th.ac.mfu.doctracking.common.ApiException;

/** Document row locks serialize commands and deliveries. A crash rolls back the engine and ACK together. */
@Component
public class WorkflowOutboxWorker {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final TrackingWorkflowEngine engine;
    private final TaskService tasks;
    private final TransactionTemplate tx;
    private final boolean enabled;
    private volatile boolean ready;

    public WorkflowOutboxWorker(JdbcTemplate db,ObjectMapper json,TrackingWorkflowEngine engine,TaskService tasks,
                                PlatformTransactionManager manager,@Value("${app.workflow-worker-enabled:true}") boolean enabled) {
        this.db=db;this.json=json;this.engine=engine;this.tasks=tasks;this.tx=new TransactionTemplate(manager);this.enabled=enabled;
    }
    @Bean ApplicationRunner deployTrackingWorkflow() {return args->engine.deploy();}
    @EventListener(ApplicationReadyEvent.class) public void applicationReady() {ready=true;}
    @Scheduled(fixedDelayString="${app.workflow-poll-ms:1000}")
    public void scheduled() {if(enabled && ready)for(int i=0;i<25 && processOne();i++) { /* bounded batch */ }}
    public boolean processOne() {return processDocument(null);}

    /** Also supports deterministic recovery checks without a scheduler or in-memory queue. */
    public boolean processDocument(UUID document) {
        UUID[] delivery={null};
        try {
            return Boolean.TRUE.equals(tx.execute(status->{
                var docs=db.queryForList("""
                    select d.id from documents d where (?::uuid is null or d.id=?::uuid)
                    and exists(select 1 from workflow_outbox o where o.document_id=d.id
                      and o.status in ('PENDING','RETRY_WAIT') and o.available_at<=now()
                      and not exists(select 1 from workflow_outbox p where p.document_id=o.document_id
                        and p.sequence<o.sequence and p.status<>'APPLIED'))
                    order by d.id for update skip locked limit 1
                    """,document,document);
                if(docs.isEmpty())return false;
                UUID id=(UUID)docs.getFirst().get("id");
                var row=db.queryForMap("select * from workflow_outbox where document_id=? and status<>'APPLIED' order by sequence limit 1 for update",id);
                delivery[0]=(UUID)row.get("id");
                apply(row);return true;
            }));
        } catch(RuntimeException failure) {
            if(delivery[0]==null)throw failure;
            // Persist only a safe code after the shared apply transaction has rolled back.
            boolean rejected=failure instanceof Rejected || failure instanceof ApiException api && "WORKFLOW_MISMATCH".equals(api.code());
            recordFailure(delivery[0],rejected?"WORKFLOW_CORRELATION_MISMATCH":"ENGINE_APPLY_FAILED",rejected);
            return true;
        }
    }
    private void apply(Map<String,Object> row) {
        UUID document=(UUID)row.get("document_id"),event=(UUID)row.get("event_id");
        var ledger=db.queryForMap("select * from document_events where id=?",event);
        var payload=decode(row.get("payload").toString());
        require(document.equals(ledger.get("document_id")) && row.get("sequence").equals(ledger.get("sequence"))
                && row.get("action").equals(ledger.get("event_type")) && payload.equals(decode(ledger.get("payload").toString())));
        String hash=hash(row.get("action").toString(),payload);
        var consumed=db.queryForList("select payload_hash from workflow_inbox where event_id=?",event);
        if(!consumed.isEmpty()) {
            require(hash.equals(consumed.getFirst().get("payload_hash")));ack(row,ledger);return;
        }
        var doc=db.queryForMap("select * from documents where id=?",document);
        require(document.equals(uuid(payload,"document_id")) && doc.get("tracking_mode").equals(payload.get("tracking_mode"))
                && ((Number)doc.get("policy_version")).longValue()==number(payload.get("policy_version"))
                && Objects.equals(ledger.get("route_step_id"),uuid(payload,"route_step_id"))
                && Objects.equals(ledger.get("from_step_id"),uuid(payload,"from_step_id"))
                && Objects.equals(ledger.get("to_step_id"),uuid(payload,"to_step_id")));
        UUID step=(UUID)ledger.get("route_step_id"),from=(UUID)ledger.get("from_step_id"),to=(UUID)ledger.get("to_step_id"),leg=uuid(payload,"leg_id");
        String action=(String)row.get("action");
        db.update("insert into workflow_inbox(event_id,document_id,payload_hash) values (?,?,?)",event,document,hash);
        var mappings=db.queryForList("select * from workflow_instances where document_id=?",document);
        if(action.equals("DOCUMENT_REGISTERED")) {
            require(mappings.isEmpty());
            var steps=db.queryForList("select id from route_steps where document_id=? order by sequence",UUID.class,document);
            require(!steps.isEmpty() && steps.getFirst().equals(step));
            var process=engine.start(document,(String)doc.get("tracking_mode"),steps);
            db.update("insert into workflow_instances(document_id,process_instance_id,process_definition_id,process_definition_version) values(?,?,?,?)",
                    document,process.getId(),process.getProcessDefinitionId(),process.getProcessDefinitionVersion());
        } else {
            require(mappings.size()==1);
            String instance=(String)mappings.getFirst().get("process_instance_id");
            String key=expectedTask(action,document,step);
            require(key.equals(payload.get("expected_task_key")));
            var task=tasks.createTaskQuery().processInstanceId(instance).singleResult();
            require(task!=null && key.equals(task.getTaskDefinitionKey()));
            if(leg!=null) {
                var legs=db.queryForList("select from_step_id,to_step_id from courier_legs where id=? and document_id=?",leg,document);
                require(legs.size()==1 && Objects.equals(from,legs.getFirst().get("from_step_id")) && Objects.equals(to,legs.getFirst().get("to_step_id")));
            }
            engine.apply(instance,document,action,step,from,to,leg);
        }
        ack(row,ledger);
    }
    private String expectedTask(String action,UUID document,UUID step) {
        return switch(action) {
            case "UNIT_READY" -> db.queryForObject("select sequence from route_steps where document_id=? and id=?",Integer.class,document,step)==0?"ORIGIN_READY":"UNIT_PROCESS_AND_READY";
            case "COURIER_PICKED_UP" -> "COURIER_PICKUP";
            case "COURIER_DROPPED_OFF" -> "COURIER_DROPOFF";
            case "UNIT_RECEIPT_CONFIRMED" -> "UNIT_RECEIVE";
            case "ORIGIN_RETURN_CONFIRMED" -> "ORIGIN_RETURN_RECEIVE";
            default -> throw new Rejected();
        };
    }
    private void ack(Map<String,Object> row,Map<String,Object> ledger) {
        db.update("update workflow_outbox set status='APPLIED',attempts=attempts+1,last_error_code=null,version=version+1 where id=?",row.get("id"));
        db.update("""
            update documents set process_sync_status=case when exists(
              select 1 from workflow_outbox where document_id=? and status<>'APPLIED') then 'PENDING' else 'APPLIED' end where id=?
            """,row.get("document_id"),row.get("document_id"));
        db.update("update tracking_operations set result=jsonb_set(result,'{process_sync_status}','\"APPLIED\"'::jsonb) where id=?",ledger.get("operation_id"));
    }
    private void recordFailure(UUID outbox,String code,boolean permanent) {
        tx.executeWithoutResult(status->{
            UUID document=db.queryForObject("select document_id from workflow_outbox where id=?",UUID.class,outbox);
            db.queryForList("select id from documents where id=? for update",document);
            var row=db.queryForMap("select status,attempts from workflow_outbox where id=? for update",outbox);
            if("APPLIED".equals(row.get("status")))return;
            int attempt=((Number)row.get("attempts")).intValue()+1;
            db.update("update workflow_outbox set status=?,attempts=?,last_error_code=?,available_at=now()+(? * interval '1 second'),version=version+1 where id=?",
                    permanent || attempt>=5?"DEAD_LETTER":"RETRY_WAIT",attempt,code,Math.min(300,1L << Math.min(attempt,8)),outbox);
            db.update("update documents set process_sync_status='FAILED_RETRYABLE' where id=?",document);
        });
    }
    private Map<String,Object> decode(String value) {
        try{return json.readValue(value,new TypeReference<>(){});}catch(Exception e){throw new Rejected();}
    }
    private UUID uuid(Map<String,Object> payload,String key) {
        try{return payload.get(key)==null?null:UUID.fromString(payload.get(key).toString());}catch(IllegalArgumentException e){throw new Rejected();}
    }
    private long number(Object value) {if(value instanceof Number n)return n.longValue();throw new Rejected();}
    private String hash(String action,Object payload) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((action+json.writeValueAsString(payload)).getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    private void require(boolean condition) {if(!condition)throw new Rejected();}
    private static class Rejected extends RuntimeException {}
}
