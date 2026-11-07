package th.ac.mfu.doctracking.domain;

import org.springframework.stereotype.Service;
import th.ac.mfu.doctracking.common.ApiException;
import th.ac.mfu.doctracking.common.CommandResult;
import th.ac.mfu.doctracking.identity.Actor;

import java.util.*;

/** Observations are local evidence; unapproved route reconciliation never completes an engine task. */
@Service
public class ObservationService {
    private final TrackingStore s;
    private final EvidenceService evidence;
    public ObservationService(TrackingStore s,EvidenceService evidence) { this.s=s;this.evidence=evidence; }
    public record Observation(Long expected_version,UUID observed_station_id,UUID proposed_route_step_id,
                              List<UUID> evidence_upload_ids,String reason,Boolean physical_presence_confirmed) {}

    public void authorize(Actor actor,UUID document,Observation input) {
        if(input==null || input.observed_station_id()==null) throw new ApiException(422,"STATION_REQUIRED","Observed station required");
        actor.requireCapability("COURIER");
        if(!actor.hasStation(input.observed_station_id())) throw new ApiException(403,"STATION_SCOPE_REQUIRED","Courier station permission required");
        s.one("select u.id from users u join user_capabilities c on c.user_id=u.id and c.capability='COURIER' join user_station_permissions p on p.user_id=u.id join stations t on t.id=p.station_id join units n on n.id=t.unit_id where u.id=? and u.is_active and u.session_version=? and t.id=? and t.is_active and n.is_active for share of u,t,n",actor.id(),actor.sessionVersion(),input.observed_station_id());
        s.document(document,false);
    }
    private void require(boolean condition,String code,String message) {
        if(!condition) throw new ApiException(409,code,message);
    }
    public CommandResult observe(Actor actor,UUID document,Observation input) {
        authorize(actor,document,input);
        if(input.expected_version()==null || input.expected_version()<0) throw new ApiException(422,"VERSION_REQUIRED","Expected version required");
        if(!Boolean.TRUE.equals(input.physical_presence_confirmed())) throw new ApiException(422,"PHYSICAL_PRESENCE_REQUIRED","Confirm that the document was physically observed");
        String reason=TrackingStore.clean(input.reason(),1000);
        if(reason==null) throw new ApiException(422,"REASON_REQUIRED","Observation reason required");
        var doc=s.document(document,true);TrackingStore.checkVersion(doc,input.expected_version());
        require("MODEL_A".equals(doc.get("tracking_mode")),"MODEL_MISMATCH","Only Model A supports courier observations");
        require("APPLIED".equals(doc.get("process_sync_status")),"WORKFLOW_SYNC_PENDING","Wait for workflow synchronization");
        require(doc.get("active_leg_id")==null && doc.get("active_reservation_id")==null && doc.get("pending_receipt_step_id")==null
                && !s.db.queryForObject("select exists(select 1 from direct_transfers where document_id=? and received_at is null)",Boolean.class,document),"CUSTODY_CONFLICT","Observation cannot replace active transport or reservation");
        require(Set.of("AWAIT_PHYSICAL_PICKUP","DROPPED_OFF_UNCONFIRMED").contains(doc.get("business_state")),"INVALID_STATE","Observation requires a nonterminal document awaiting pickup");
        var occurrences=s.db.queryForList("select id from route_steps where document_id=? and station_id=? order by sequence",UUID.class,document,input.observed_station_id());
        UUID proposed=input.proposed_route_step_id();
        if(occurrences.isEmpty()?proposed!=null:proposed==null || !occurrences.contains(proposed))
            throw new ApiException(422,"EXACT_STEP_REQUIRED","Choose the exact route occurrence; off-route observations must omit the proposed step");
        evidence.requireFinalized(actor,input.evidence_upload_ids(),document,input.observed_station_id());
        for(UUID photo:input.evidence_upload_ids()) {
            var upload=s.one("select station_id from evidence_uploads where id=?",photo);
            if(!input.observed_station_id().equals(upload.get("station_id"))) throw new ApiException(422,"EVIDENCE_NOT_USABLE","Observation requires photographs of the observed station context");
        }
        UUID current=TrackingStore.id(doc,"current_route_step_id"),reported=TrackingStore.id(doc,"reported_location_step_id"),operation=UUID.randomUUID();
        boolean gap=!Objects.equals(current,proposed);
        var payload=new LinkedHashMap<String,Object>();
        payload.put("observed_station_id",input.observed_station_id());payload.put("proposed_route_step_id",proposed);
        payload.put("previous_route_step_id",current);payload.put("previous_reported_location_step_id",reported);
        payload.put("reason",reason);payload.put("physical_presence_confirmed",true);
        payload.put("reconciliation_status",gap?"REQUIRES_REVIEW":"NOT_REQUIRED");
        s.db.update("update documents set business_state=?,version=version+1,latest_operation_id=? where id=?",gap?"EXCEPTION":doc.get("business_state"),operation,document);
        UUID observed=s.event(document,actor.id(),proposed,"OBSERVED_AT",operation,payload,false);
        for(UUID photo:input.evidence_upload_ids()) s.db.update("insert into event_evidence(event_id,evidence_id) values(?,?)",observed,photo);
        var eventIds=new ArrayList<UUID>();eventIds.add(observed);
        UUID exception=null;
        if(gap) {
            var missing=new LinkedHashMap<>(payload);
            missing.put("transfer_actor_id",null);missing.put("transfer_started_at",null);missing.put("transfer_arrived_at",null);
            missing.put("missing_transfer_evidence",true);
            // Null actor is deliberate: the observer is known, the unobserved carrier is not.
            eventIds.add(s.event(document,null,proposed,reported,proposed,"UNOBSERVED_TRANSFER",operation,missing,false));
            exception=UUID.randomUUID();payload.put("exception_id",exception);payload.put("previous_state",doc.get("business_state"));
            payload.put("code",occurrences.isEmpty()?"WRONG_STATION":"UNOBSERVED_TRANSFER");
            s.db.update("insert into tracking_exceptions(id,document_id,opened_by,code,previous_state) values(?,?,?,?,?)",exception,document,actor.id(),payload.get("code"),doc.get("business_state"));
            eventIds.add(s.event(document,actor.id(),proposed,"EXCEPTION_OPENED",operation,payload,false));
        }
        var result=s.outcome(operation,document,observed,"APPLIED");result.put("event_ids",eventIds);
        result.put("reconciliation_status",gap?"REQUIRES_REVIEW":"NOT_REQUIRED");result.put("exception_id",exception);
        s.db.update("insert into tracking_operations(id,actor_id,document_id,result) values(?,?,?,?::jsonb)",operation,actor.id(),document,s.encode(result));
        return new CommandResult(200,result);
    }
}
