package th.ac.mfu.doctracking.notifications;

import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class NotificationWorker {
    private final JdbcTemplate db;private final TransactionTemplate tx;private final JavaMailSender mail;
    private final boolean workerEnabled,mailEnabled;private final String from,itContact;
    public NotificationWorker(JdbcTemplate db,PlatformTransactionManager manager,JavaMailSender mail,@Value("${app.mail-worker-enabled:true}") boolean workerEnabled,@Value("${app.mail-enabled:false}") boolean mailEnabled,@Value("${app.mail-from:no-reply@example.invalid}") String from,@Value("${app.it-contact:Contact your IT service desk}") String itContact){this.db=db;this.tx=new TransactionTemplate(manager);this.mail=mail;this.workerEnabled=workerEnabled;this.mailEnabled=mailEnabled;this.from=from;this.itContact=itContact;}
    @Scheduled(fixedDelayString="${app.mail-poll-ms:5000}") public void scheduled(){if(workerEnabled && mailEnabled)deliverOne();}
    /** Claims with a token so an old worker cannot ACK a lease reclaimed by another worker. */
    public boolean deliverOne(){
        if(!mailEnabled)return false;
        var job=tx.execute(status->{
            var jobs=db.queryForList("select * from notification_outbox where (status in ('PENDING','RETRY_WAIT') and (next_retry_at is null or next_retry_at<=now())) or (status='PROCESSING' and lease_until<now()) order by login_at,id for update skip locked limit 1");
            if(jobs.isEmpty())return null;var j=jobs.getFirst();UUID id=(UUID)j.get("id");UUID token=UUID.randomUUID();int attempt=((Number)j.get("attempt_count")).intValue()+1;
            db.update("update notification_delivery_attempts set status='LEASE_EXPIRED',error_code='ACK_UNCERTAIN',finished_at=now() where notification_id=? and finished_at is null",id);
            db.update("update notification_outbox set status='PROCESSING',attempt_count=?,lease_token=?,lease_until=now()+interval '2 minutes',version=version+1 where id=?",attempt,token,id);
            db.update("insert into notification_delivery_attempts(notification_id,attempt,status) values (?,?,'PROCESSING')",id,attempt);j.put("lease_token",token);j.put("attempt_count",attempt);return j;
        });
        if(job==null)return false;
        UUID id=(UUID)job.get("id");int attempt=((Number)job.get("attempt_count")).intValue();boolean accepted=false;
        try{
            MimeMessage message=mail.createMimeMessage();var helper=new MimeMessageHelper(message,"UTF-8");helper.setFrom(from);helper.setTo((String)job.get("recipient"));helper.setSubject("MFU Document Tracking: login notification");
            Instant at=((Timestamp)job.get("login_at")).toInstant();helper.setText(body(at,itContact));message.saveChanges();message.setHeader("Message-ID",messageId(id));mail.send(message);accepted=true;
        }catch(Exception ignored){/* Never persist provider responses, credentials or raw exception messages. */}
        boolean delivered=accepted;tx.executeWithoutResult(status->{
            String state=delivered?"ACCEPTED_BY_PROVIDER":attempt>=5?"FAILED":"RETRY_WAIT";
            int changed=db.update("update notification_outbox set status=?,lease_token=null,lease_until=null,next_retry_at=?,last_error_code=?,version=version+1 where id=? and lease_token=?",state,delivered?null:Timestamp.from(Instant.now().plusSeconds(Math.min(3600,30L << Math.min(attempt-1,7)))),delivered?null:"SMTP_SEND_FAILED",id,job.get("lease_token"));
            if(changed==1)db.update("update notification_delivery_attempts set status=?,error_code=?,finished_at=now() where notification_id=? and attempt=?",state,delivered?null:"SMTP_SEND_FAILED",id,attempt);
        });return true;
    }
    public static String messageId(UUID id){return "<login-"+id+"@notifications.mfu.ac.th>";}
    public static String body(Instant loginAt,String contact){return "A new login to MFU Document Tracking was completed at "+DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm:ss z").withZone(ZoneId.of("Asia/Bangkok")).format(loginAt)+" (Asia/Bangkok).\nIf this was not you, contact IT: "+contact+"\nThis message contains no document information.\n";}
}
