package th.ac.mfu.doctracking.domain;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import th.ac.mfu.doctracking.common.ApiException;
import th.ac.mfu.doctracking.identity.Actor;

import java.time.Instant;
import java.util.*;

/** Removes only abandoned, unfinalized uploads. Finalized evidence has no retention deletion path. */
@Service
public class OrphanUploadCleanup {
    private final TrackingStore s;
    private final PrivatePhotoStorage storage;
    private final TransactionTemplate tx;
    private final TransactionTemplate batchTx;
    private final boolean enabled;
    private final long graceSeconds;
    public OrphanUploadCleanup(TrackingStore s, PrivatePhotoStorage storage, PlatformTransactionManager manager,
                               @Value("${app.evidence.orphan-cleanup-enabled:false}") boolean enabled,
                               @Value("${app.evidence.orphan-grace-seconds:0}") long graceSeconds) {
        if(graceSeconds<0 || (enabled && graceSeconds==0)) throw new IllegalArgumentException("Approved orphan grace required before enabling cleanup");
        this.s=s; this.storage=storage; this.enabled=enabled; this.graceSeconds=graceSeconds;
        batchTx=new TransactionTemplate(manager);
        tx=new TransactionTemplate(manager); tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }
    public Map<String,Object> run(Actor actor, boolean dryRun, int limit) {
        actor.requireAdmin();
        if(limit<1 || limit>100) throw new ApiException(422,"INVALID_CLEANUP_LIMIT","Cleanup limit must be 1..100");
        if(graceSeconds==0) throw new ApiException(409,"ORPHAN_POLICY_REQUIRED","Approve and configure an orphan upload grace before cleanup preview");
        if(!dryRun && !enabled) throw new ApiException(409,"ORPHAN_CLEANUP_DISABLED","Orphan upload cleanup is disabled");
        // Join CommandService so metadata deletes, audit and idempotency receipt commit together.
        return batchTx.execute(status->perform(dryRun,limit));
    }
    private Map<String,Object> perform(boolean dryRun,int limit) {
        Instant cutoff=Instant.now().minusSeconds(graceSeconds);
        var candidates=s.db.queryForList("select u.id from evidence_uploads u where u.status in ('INITIATED','STORED') and u.created_at<? and not exists(select 1 from event_evidence e where e.evidence_id=u.id) order by u.created_at,u.id limit ?",java.sql.Timestamp.from(cutoff),limit);
        int deleted=0,failed=0,skipped=0;
        for(var candidate:candidates) {
            if(dryRun) continue;
            try {
                boolean removed=Boolean.TRUE.equals(tx.execute(status->{
                    var rows=s.db.queryForList("select u.id,u.storage_key from evidence_uploads u where u.id=? and u.status in ('INITIATED','STORED') and u.created_at<? and not exists(select 1 from event_evidence e where e.evidence_id=u.id) for update skip locked",candidate.get("id"),java.sql.Timestamp.from(cutoff));
                    if(rows.isEmpty()) return false;
                    // content/finalize take this same row lock; finalized/linked rows can never enter here.
                    var row=rows.getFirst(); storage.delete((String)row.get("storage_key"));
                    s.db.update("delete from evidence_uploads where id=?",row.get("id")); return true;
                }));
                if(removed) deleted++; else skipped++;
            } catch(RuntimeException failure) {
                // Savepoint rollback preserves this metadata; an exact-key object delete is idempotent.
                failed++;
            }
        }
        return Map.of("dry_run",dryRun,"enabled",enabled,"policy_configured",true,"candidates",candidates.size(),"deleted",deleted,"skipped",skipped,"failed",failed);
    }
}
