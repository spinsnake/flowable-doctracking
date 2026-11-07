package th.ac.mfu.doctracking.domain;

import org.springframework.stereotype.Service;
import th.ac.mfu.doctracking.common.*;
import th.ac.mfu.doctracking.identity.Actor;

import java.util.*;

@Service
public class WorkflowPolicyService {
    private final TrackingStore s;
    public WorkflowPolicyService(TrackingStore s) { this.s=s; }
    public record Change(Long expected_version,String default_tracking_mode,String reason) {}

    public void authorize(Actor actor) {
        actor.requireAdmin();
        if(s.db.queryForList("select id from users where id=? and is_active and is_admin and session_version=?",actor.id(),actor.sessionVersion()).isEmpty())
            throw new ApiException(403,"FORBIDDEN","Current Central Admin permission required");
    }
    public Map<String,Object> get(Actor actor) {
        authorize(actor);
        return view();
    }
    private Map<String,Object> view() {
        return s.one("""
                select p.default_tracking_mode,p.version,p.changed_at,a.actor_id as changed_by,
                       a.reason::jsonb->>'reason' as change_reason
                from workflow_policy p left join audit_events a on a.action='WORKFLOW_POLICY_CHANGED'
                  and ((case when a.action='WORKFLOW_POLICY_CHANGED' then a.reason::jsonb end)->>'new_version')::bigint=p.version
                where p.id=true
                """);
    }
    public CommandResult change(Actor actor,Change input) {
        authorize(actor);
        if(input.expected_version()==null || input.expected_version()<0 || !Set.of("MODEL_A","MODEL_B").contains(input.default_tracking_mode()==null?"":input.default_tracking_mode()) || TrackingStore.clean(input.reason(),1000)==null)
            throw new ApiException(422,"INVALID_POLICY_CHANGE","A valid mode, expected version and reason are required");
        var before=s.one("select * from workflow_policy where id=true for update");
        TrackingStore.checkVersion(before,input.expected_version());
        if(input.default_tracking_mode().equals(before.get("default_tracking_mode")))
            throw new ApiException(409,"POLICY_UNCHANGED","Choose a different default tracking mode");
        s.db.update("update workflow_policy set default_tracking_mode=?,version=version+1,changed_at=now() where id=true",input.default_tracking_mode());
        // Keep the existing append-only audit schema; this action's reason is a JSON audit envelope.
        var audit=Map.of("reason",input.reason().trim(),"old_tracking_mode",before.get("default_tracking_mode"),
                "new_tracking_mode",input.default_tracking_mode(),"old_version",before.get("version"),"new_version",input.expected_version()+1);
        s.db.update("insert into audit_events(actor_id,action,reason) values(?,'WORKFLOW_POLICY_CHANGED',?)",actor.id(),s.encode(audit));
        return new CommandResult(200,view());
    }
}
