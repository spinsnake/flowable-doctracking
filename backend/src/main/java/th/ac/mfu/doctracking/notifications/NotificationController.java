package th.ac.mfu.doctracking.notifications;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import th.ac.mfu.doctracking.identity.*;
import th.ac.mfu.doctracking.common.*;

@RestController @RequestMapping("/api/v1/admin/login-notifications")
public class NotificationController {
    private final JdbcTemplate db;private final ActorService actors;private final CommandService commands;private final IdentityService ids;
    public NotificationController(JdbcTemplate db,ActorService actors,CommandService commands,IdentityService ids){this.db=db;this.actors=actors;this.commands=commands;this.ids=ids;}
    public record Retry(@NotNull @Min(0) Long expected_version,@NotBlank @Size(max=1000) String reason){}
    private static final String FIELDS="id,recipient,status,attempt_count,next_retry_at,last_error_code,version";
    @GetMapping Object list(@RequestParam(required=false) String status,@RequestParam(required=false) UUID cursor,@RequestParam(defaultValue="50") @Min(1) @Max(100) int limit){
        actors.current().requireAdmin();if(status!=null && !Set.of("PENDING","PROCESSING","RETRY_WAIT","ACCEPTED_BY_PROVIDER","FAILED").contains(status))throw new ApiException(422,"INVALID_STATUS","Invalid status");
        var items=db.queryForList("select "+FIELDS+" from notification_outbox where (?::text is null or status=?) and (?::uuid is null or id>?::uuid) order by id limit ?",status,status,cursor,cursor,limit+1);
        Map<String,Object> page=new LinkedHashMap<>();page.put("next_cursor",items.size()>limit?items.get(limit-1).get("id").toString():null);page.put("items",items.stream().limit(limit).toList());return page;
    }
    @PostMapping("/{id}/retry") Object retry(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Retry in){Actor a=actors.current();a.requireAdmin();var result=commands.execute(a,"notification-retry:"+id,key,in,()->{
        var jobs=db.queryForList("select * from notification_outbox where id=? for update",id);if(jobs.isEmpty())throw new ApiException(404,"NOT_FOUND","Notification not found");var job=jobs.getFirst();if(((Number)job.get("version")).longValue()!=in.expected_version())throw new ApiException(409,"STALE_VERSION","Reload notification");if(!Set.of("FAILED","RETRY_WAIT").contains(job.get("status")))throw new ApiException(409,"RETRY_NOT_ALLOWED","Only failed or waiting jobs can be retried");
        db.update("update notification_outbox set status='PENDING',next_retry_at=null,last_error_code=null,version=version+1 where id=?",id);ids.audit(a,"NOTIFICATION_RETRY",id,in.reason());return new CommandResult(202,db.queryForMap("select "+FIELDS+" from notification_outbox where id=?",id));
    });return ResponseEntity.status(result.status()).body(result.body());}
}
