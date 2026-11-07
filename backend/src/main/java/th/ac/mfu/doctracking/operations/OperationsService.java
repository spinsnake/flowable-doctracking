package th.ac.mfu.doctracking.operations;

import java.util.*;
import org.flowable.engine.TaskService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import th.ac.mfu.doctracking.common.ApiException;
import th.ac.mfu.doctracking.identity.Actor;

/** Technical read models deliberately omit mail addresses, document metadata, evidence and audit reasons. */
@Service
public class OperationsService {
    private final JdbcTemplate db;
    private final TaskService tasks;
    public OperationsService(JdbcTemplate db,TaskService tasks) {this.db=db;this.tasks=tasks;}
    public Map<String,Object> summary(Actor actor) {
        actor.requireAdmin();
        var workflow=db.queryForMap("""
            select count(*) filter(where status='PENDING') as pending,count(*) filter(where status='RETRY_WAIT') as retry_wait,
            count(*) filter(where status='DEAD_LETTER') as dead_letter,
            coalesce(extract(epoch from now()-min(created_at) filter(where status<>'APPLIED')),0)::bigint as oldest_pending_age_seconds from workflow_outbox
            """);
        var mail=db.queryForMap("""
            select count(*) filter(where status='PENDING') as pending,count(*) filter(where status='PROCESSING') as processing,
            count(*) filter(where status='RETRY_WAIT') as retry_wait,count(*) filter(where status='FAILED') as failed,
            coalesce(extract(epoch from now()-min(login_at) filter(where status<>'ACCEPTED_BY_PROVIDER')),0)::bigint as oldest_pending_age_seconds from notification_outbox
            """);
        long reconciliation=count("select count(*) from workflow_reconciliation_findings where state='OPEN'");
        var alerts=new ArrayList<Map<String,Object>>();
        alert(alerts,"WORKFLOW_DEAD_LETTER",((Number)workflow.get("dead_letter")).longValue());
        alert(alerts,"MAIL_DELIVERY_FAILED",((Number)mail.get("failed")).longValue());
        alert(alerts,"RECONCILIATION_MISMATCH",reconciliation);
        return Map.of("workflow",workflow,"mail",mail,"alerts",alerts,"open_exceptions",count("select count(*) from tracking_exceptions where resolved_at is null"),
            "waiting_tasks",count("select count(*) from act_ru_task"),
            "oldest_waiting_age_seconds",count("select coalesce(extract(epoch from now()-min(create_time_)),0)::bigint from act_ru_task"),
            "reconciliation_open",reconciliation);
    }
    public Map<String,Object> list(Actor actor,String kind,String cursor,int limit,String status) {
        actor.requireAdmin();if(limit<1 || limit>100)throw new ApiException(400,"INVALID_INPUT","Limit must be between 1 and 100");
        if(!kind.equals("tasks") && cursor!=null)try{UUID.fromString(cursor);}catch(IllegalArgumentException e){throw new ApiException(400,"INVALID_INPUT","Invalid cursor");}
        var args=new ArrayList<Object>();String sql;
        switch(kind) {
            case "workflow-deliveries" -> {
                if(status!=null && !Set.of("PENDING","RETRY_WAIT","DEAD_LETTER","APPLIED").contains(status))throw new ApiException(422,"INVALID_STATUS","Invalid status");
                sql="select o.id,o.document_id,e.operation_id,o.event_id,o.status,o.attempts,o.available_at as next_retry_at,o.last_error_code,o.version,o.created_at from workflow_outbox o join document_events e on e.id=o.event_id where "+(status==null?"o.status in ('RETRY_WAIT','DEAD_LETTER')":"o.status=?");if(status!=null)args.add(status);
                sql+=" and (?::uuid is null or o.id>?::uuid) order by o.id limit ?";
            }
            case "login-notifications" -> {
                if(status!=null && !Set.of("PENDING","PROCESSING","RETRY_WAIT","FAILED","ACCEPTED_BY_PROVIDER").contains(status))throw new ApiException(422,"INVALID_STATUS","Invalid status");
                sql="select id,status,attempt_count,next_retry_at,last_error_code,version,login_at from notification_outbox where "+(status==null?"status in ('FAILED','RETRY_WAIT','PROCESSING')":"status=?");if(status!=null)args.add(status);
                sql+=" and (?::uuid is null or id>?::uuid) order by id limit ?";
            }
            case "exceptions" -> sql="select id,document_id,opened_by,code,previous_state,opened_at from tracking_exceptions where resolved_at is null and (?::uuid is null or id>?::uuid) order by id limit ?";
            case "audit-events" -> sql="select id,actor_id,action,resource_id,recorded_at from audit_events where (?::uuid is null or id>?::uuid) order by id limit ?";
            case "reminders" -> sql="select id,document_id,task_key,route_step_id,recorded_at from workflow_reminders where (?::uuid is null or id>?::uuid) order by id limit ?";
            case "tasks" -> sql="select t.id_ as id,t.proc_inst_id_ as process_instance_id,t.task_def_key_ as task_key,t.create_time_ as created_at,w.document_id,greatest(extract(epoch from now()-t.create_time_),0)::bigint as age_seconds from act_ru_task t join workflow_instances w on w.process_instance_id=t.proc_inst_id_ where (?::text is null or t.id_>?::text) order by t.id_ limit ?";
            default -> throw new ApiException(404,"NOT_FOUND","Operations resource not found");
        }
        args.add(cursor);args.add(cursor);args.add(limit+1);var rows=db.queryForList(sql,args.toArray());
        rows.forEach(row->{if(row.containsKey("last_error_code"))row.computeIfPresent("last_error_code",(k,v)->safeError(v));});
        var result=new LinkedHashMap<String,Object>();result.put("items",rows.stream().limit(limit).toList());result.put("next_cursor",rows.size()>limit?rows.get(limit-1).get("id").toString():null);return result;
    }
    public Map<String,Object> engine(Actor actor,UUID document) {
        actor.requireAdmin();var docs=db.queryForList("select id as document_id,business_state,process_sync_status,current_route_step_id,reported_location_step_id,pending_receipt_step_id,version from documents where id=?",document);
        if(docs.isEmpty())throw new ApiException(404,"NOT_FOUND","Document not found");
        var result=new LinkedHashMap<String,Object>(docs.getFirst());var mappings=db.queryForList("select process_instance_id,process_definition_id,process_definition_version from workflow_instances where document_id=?",document);
        result.put("mapping",mappings.isEmpty()?null:mappings.getFirst());var active=new ArrayList<Map<String,Object>>();
        if(!mappings.isEmpty())for(var task:tasks.createTaskQuery().processInstanceId((String)mappings.getFirst().get("process_instance_id")).active().listPage(0,100)) {
            var row=new LinkedHashMap<String,Object>();row.put("id",task.getId());row.put("task_key",task.getTaskDefinitionKey());row.put("created_at",task.getCreateTime());
            var local=tasks.getVariablesLocal(task.getId(),List.of("documentId","routeStepId","fromStepId","toStepId","legId"));
            var safe=new LinkedHashMap<String,Object>();local.forEach((key,value)->{if(value instanceof String text && text.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))safe.put(key,text);});row.put("binding",safe);active.add(row);
        }
        result.put("tasks",active);return result;
    }
    private long count(String sql) {return db.queryForObject(sql,Long.class);}
    private void alert(List<Map<String,Object>> alerts,String code,long count) {if(count>0)alerts.add(Map.of("code",code,"count",count));}
    private static Object safeError(Object value) {return Set.of("ENGINE_APPLY_FAILED","WORKFLOW_CORRELATION_MISMATCH","SMTP_SEND_FAILED","ACK_UNCERTAIN").contains(value.toString())?value:"REDACTED_ERROR";}
}
