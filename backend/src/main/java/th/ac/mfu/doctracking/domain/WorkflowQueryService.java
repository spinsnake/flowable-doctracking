package th.ac.mfu.doctracking.domain;

import org.springframework.stereotype.Service;
import th.ac.mfu.doctracking.common.ApiException;
import th.ac.mfu.doctracking.identity.Actor;

import java.util.*;

/** Read models expose exact route occurrences without widening the origin-only field policy. */
@Service
public class WorkflowQueryService {
    private final TrackingStore s;
    public WorkflowQueryService(TrackingStore s) { this.s=s; }

    public Map<String,Object> unitWorklist(Actor actor,UUID station,UUID cursor,int limit) {
        RegistryService.checkLimit(limit);
        boolean allowed=s.db.queryForObject("select exists(select 1 from stations st join units u on u.id=st.unit_id join unit_memberships m on m.unit_id=u.id and m.user_id=? join user_station_permissions p on p.station_id=st.id and p.user_id=m.user_id join users a on a.id=m.user_id where st.id=? and st.is_active and u.is_active and a.is_active and a.session_version=?)",Boolean.class,actor.id(),station,actor.sessionVersion());
        if(!allowed) throw new ApiException(403,"STATION_SCOPE_REQUIRED","Active unit membership and station permission required");
        var rows=s.db.queryForList("select d.*,r.id as action_step_id from documents d join route_steps r on r.id=case when d.business_state in ('PENDING_UNIT_RECEIPT','PENDING_ORIGIN_RETURN_RECEIPT') then d.pending_receipt_step_id else d.current_route_step_id end where r.station_id=? and d.tracking_mode='MODEL_B' and d.business_state in ('WAIT_ORIGIN_READY','UNIT_PROCESSING','READY_FOR_PICKUP','PENDING_UNIT_RECEIPT','PENDING_ORIGIN_RETURN_RECEIPT') and (?::uuid is null or d.id>?::uuid) order by d.id limit ?",station,cursor,cursor,limit+1);
        return RegistryService.page(rows,limit,row->{
            boolean origin=s.db.queryForObject("select exists(select 1 from unit_memberships m join units u on u.id=m.unit_id where m.user_id=? and m.unit_id=? and u.is_active)",Boolean.class,actor.id(),row.get("origin_unit_id"));
            var result=s.view(row,!origin);
            result.put("action_route_step_id",row.get("action_step_id"));
            String action=null;
            if("APPLIED".equals(row.get("process_sync_status")) && row.get("active_leg_id")==null && row.get("active_reservation_id")==null) {
                action=switch(row.get("business_state").toString()) {
                    case "PENDING_UNIT_RECEIPT" -> "CONFIRM_RECEIPT";
                    case "WAIT_ORIGIN_READY","UNIT_PROCESSING" -> "READY";
                    case "PENDING_ORIGIN_RETURN_RECEIPT" -> origin?"CONFIRM_RETURN":null;
                    default -> null;
                };
            }
            result.put("action",action);return result;
        });
    }

    public Map<String,Object> legs(Actor actor) {
        actor.requireCapability("COURIER");
        boolean allowed=s.db.queryForObject("select exists(select 1 from user_capabilities c join users u on u.id=c.user_id where c.user_id=? and c.capability='COURIER' and u.is_active and u.session_version=?)",Boolean.class,actor.id(),actor.sessionVersion());
        if(!allowed) throw new ApiException(403,"FORBIDDEN","Active courier capability required");
        return Map.of("items",s.db.queryForList("select l.id,l.document_id,l.from_step_id,l.to_step_id,f.station_id as from_station_id,t.station_id as to_station_id,l.started_at,d.version as document_version,d.process_sync_status,d.business_state,d.tracking_mode,d.latest_operation_id from courier_legs l join documents d on d.id=l.document_id and d.active_leg_id=l.id join route_steps f on f.id=l.from_step_id join route_steps t on t.id=l.to_step_id join stations st on st.id=t.station_id join units u on u.id=st.unit_id join user_station_permissions p on p.station_id=st.id and p.user_id=l.courier_user_id where l.courier_user_id=? and l.ended_at is null and st.is_active and u.is_active order by l.started_at,l.id limit 100",actor.id()));
    }
}
