package th.ac.mfu.doctracking.domain;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.ac.mfu.doctracking.common.ApiException;
import th.ac.mfu.doctracking.common.CommandResult;
import th.ac.mfu.doctracking.identity.Actor;

import java.time.Instant;
import java.util.*;

@Service
public class ReservationService {
    private final TrackingStore s;
    private final long ttl;
    public ReservationService(TrackingStore s,@Value("${app.reservation-ttl-seconds:600}") long ttl) {
        if(ttl<1 || ttl>86400) throw new IllegalArgumentException("Reservation TTL outside supported bounds");
        this.s=s;this.ttl=ttl;
    }
    public record Item(UUID document_id,UUID route_step_id,Long expected_version) {}
    public record Reserve(UUID station_id,List<Item> items) {}
    public record Cancel(String reason) {}
    public void authorizeStation(Actor actor,UUID station) {
        actor.requireCapability("COURIER");
        if(station==null || !actor.stationIds().contains(station)) throw new ApiException(403,"STATION_SCOPE_REQUIRED","Courier station permission required");
        s.one("select s.id from stations s join units u on u.id=s.unit_id where s.id=? and s.is_active and u.is_active for share of s,u",station);
    }
    public CommandResult reserve(Actor actor,Reserve input) {
        authorizeStation(actor,input.station_id());
        if(input.items()==null || input.items().size()!=1) throw new ApiException(422,"SINGLE_ITEM_REQUIRED","P1 accepts one document per reservation");
        var item=input.items().getFirst();
        if(item==null || item.document_id()==null || item.route_step_id()==null || item.expected_version()==null) throw new ApiException(422,"INVALID_ITEM","Document, exact step and version are required");
        var doc=s.document(item.document_id(),true);
        TrackingStore.checkVersion(doc,item.expected_version());
        var step=s.one("select * from route_steps where document_id=? and id=?",item.document_id(),item.route_step_id());
        if(!input.station_id().equals(step.get("station_id"))) throw new ApiException(422,"STEP_MISMATCH","Step must belong to reservation station");
        if(!item.route_step_id().equals(doc.get("current_route_step_id"))) throw new ApiException(409,"STALE_STEP","Document is at another route step");
        String state=(String)doc.get("business_state");
        boolean eligible="MODEL_B".equals(doc.get("tracking_mode"))?"READY_FOR_PICKUP".equals(state):Set.of("AWAIT_PHYSICAL_PICKUP","DROPPED_OFF_UNCONFIRMED").contains(state);
        boolean direct=s.db.queryForObject("select exists(select 1 from direct_transfers where document_id=? and received_at is null)",Boolean.class,item.document_id());
        boolean next=s.db.queryForObject("select exists(select 1 from route_steps where document_id=? and sequence=?)",Boolean.class,item.document_id(),((Number)step.get("sequence")).intValue()+1);
        if(!"APPLIED".equals(doc.get("process_sync_status"))) throw new ApiException(409,"WORKFLOW_SYNC_PENDING","Wait for workflow synchronization");
        if(!eligible || !next || direct || doc.get("active_leg_id")!=null || doc.get("active_reservation_id")!=null)
            throw new ApiException(409,"RESERVATION_CONFLICT","Document cannot be reserved");
        UUID reservation=UUID.randomUUID(),operation=UUID.randomUUID();
        Instant expires=s.db.queryForObject("select clock_timestamp() + (? * interval '1 second')",java.sql.Timestamp.class,ttl).toInstant();
        s.db.update("insert into reservations(id,owner_user_id,station_id,status,expires_at,operation_id,correlation_id) values(?,?,?,'ACTIVE',?,?,?)",reservation,actor.id(),input.station_id(),java.sql.Timestamp.from(expires),operation,operation.toString());
        s.db.update("insert into reservation_items(reservation_id,document_id,route_step_id,previous_state,reserved_version) values(?,?,?,?,?)",reservation,item.document_id(),item.route_step_id(),state,item.expected_version()+1);
        s.db.update("update documents set business_state='PICKUP_RESERVED',active_reservation_id=?,version=version+1,latest_operation_id=? where id=?",reservation,operation,item.document_id());
        s.db.update("insert into outstanding_work(user_id,unit_id,kind,resource_id) select ?,unit_id,'RESERVATION',? from stations where id=?",actor.id(),reservation,input.station_id());
        UUID event=s.event(item.document_id(),actor.id(),item.route_step_id(),"PICKUP_RESERVED",operation,Map.of("reservation_id",reservation,"expires_at",expires.toString()),false);
        var result=view(reservation); result.putAll(s.outcome(operation,item.document_id(),event,"APPLIED"));
        s.db.update("insert into tracking_operations(id,actor_id,document_id,result) values(?,?,?,?::jsonb)",operation,actor.id(),item.document_id(),s.encode(result));
        return new CommandResult(201,result);
    }
    public void authorizeCancel(Actor actor,UUID reservation,String reason) {
        var row=s.one("select * from reservations where id=?",reservation);
        if(!actor.id().equals(row.get("owner_user_id"))) {
            actor.requireCapability("RESERVATION_RESOLVE");
            if(!actor.stationIds().contains(TrackingStore.id(row,"station_id"))) throw new ApiException(403,"STATION_SCOPE_REQUIRED","Resolution station scope required");
            if(TrackingStore.clean(reason,1000)==null) throw new ApiException(422,"REASON_REQUIRED","Cancelling another user's reservation requires a reason");
        } else authorizeStation(actor,TrackingStore.id(row,"station_id"));
    }
    public CommandResult cancel(Actor actor,UUID reservation,long version,String reason) {
        authorizeCancel(actor,reservation,reason);
        // Every custody command locks the document before the reservation, including the expiry worker.
        var item=s.one("select * from reservation_items where reservation_id=?",reservation);
        var doc=s.document(TrackingStore.id(item,"document_id"),true);
        var row=s.one("select * from reservations where id=? for update",reservation);
        TrackingStore.checkVersion(row,version);
        if(!"ACTIVE".equals(row.get("status"))) throw new ApiException(409,"RESERVATION_CONFLICT","Reservation is no longer active");
        if(expired(row)) throw new ApiException(409,"RESERVATION_EXPIRED","Reservation expired; refresh worklist");
        UUID operation=UUID.randomUUID();
        UUID event=release(doc,item,reservation,actor.id(),operation,"CANCELLED","PICKUP_RESERVATION_CANCELLED",reason);
        var result=view(reservation);result.putAll(s.outcome(operation,TrackingStore.id(item,"document_id"),event,"APPLIED"));
        s.db.update("insert into tracking_operations(id,actor_id,document_id,result) values(?,?,?,?::jsonb)",operation,actor.id(),item.get("document_id"),s.encode(result));
        return new CommandResult(200,result);
    }
    private boolean expired(Map<String,Object> row) {
        return s.db.queryForObject("select expires_at<=clock_timestamp() from reservations where id=?",Boolean.class,row.get("id"));
    }
    private UUID release(Map<String,Object> doc,Map<String,Object> item,UUID reservation,UUID actor,UUID operation,String status,String event,String reason) {
        if(!reservation.equals(doc.get("active_reservation_id")) || doc.get("active_leg_id")!=null || !"PICKUP_RESERVED".equals(doc.get("business_state")))
            throw new ApiException(409,"CUSTODY_CONFLICT","Reservation cannot release active custody");
        s.db.update("update reservations set status=?,version=version+1 where id=?",status,reservation);
        s.db.update("update reservation_items set is_active=false where reservation_id=?",reservation);
        s.db.update("update documents set business_state=?,active_reservation_id=null,version=version+1,latest_operation_id=? where id=?",item.get("previous_state"),operation,doc.get("id"));
        s.db.update("update outstanding_work set resolved_at=now() where resource_id=? and kind='RESERVATION' and resolved_at is null",reservation);
        Map<String,Object> payload=new LinkedHashMap<>();payload.put("reservation_id",reservation);payload.put("reason",TrackingStore.clean(reason,1000));
        return s.event(TrackingStore.id(doc,"id"),actor,TrackingStore.id(item,"route_step_id"),event,operation,payload,false);
    }
    @Scheduled(fixedDelayString="${app.reservation.expiry-interval-ms:30000}")
    @Transactional
    public int expire() {
        var candidates=s.db.queryForList("select i.document_id,i.reservation_id from reservation_items i join reservations r on r.id=i.reservation_id where i.is_active and r.status='ACTIVE' and r.expires_at<=clock_timestamp() order by i.document_id limit 100");
        int count=0;
        for(var candidate:candidates) {
            var doc=s.document(TrackingStore.id(candidate,"document_id"),true);
            UUID reservation=TrackingStore.id(candidate,"reservation_id");
            var row=s.one("select * from reservations where id=? for update",reservation);
            if(!"ACTIVE".equals(row.get("status")) || !expired(row)) continue;
            // Consumed/in-transit custody is never returned to the available queue by a timer.
            if(!reservation.equals(doc.get("active_reservation_id")) || doc.get("active_leg_id")!=null || !"PICKUP_RESERVED".equals(doc.get("business_state"))) continue;
            var item=s.one("select * from reservation_items where reservation_id=?",reservation);
            UUID operation=UUID.randomUUID();
            UUID event=release(doc,item,reservation,null,operation,"EXPIRED","PICKUP_RESERVATION_EXPIRED",null);
            var result=s.outcome(operation,TrackingStore.id(doc,"id"),event,doc.get("process_sync_status").toString());
            s.db.update("insert into tracking_operations(id,actor_id,document_id,result) values(?,?,?,?::jsonb)",operation,row.get("owner_user_id"),doc.get("id"),s.encode(result));
            count++;
        }
        return count;
    }
    public Map<String,Object> list(Actor actor) {
        actor.requireCapability("COURIER");
        boolean allowed=s.db.queryForObject("select exists(select 1 from user_capabilities c join users u on u.id=c.user_id where c.user_id=? and c.capability='COURIER' and u.is_active and u.session_version=?)",Boolean.class,actor.id(),actor.sessionVersion());
        if(!allowed) throw new ApiException(403,"FORBIDDEN","Active courier capability required");
        return Map.of("items",s.db.queryForList("select r.id from reservations r join stations st on st.id=r.station_id join units u on u.id=st.unit_id join user_station_permissions p on p.station_id=st.id and p.user_id=r.owner_user_id where r.owner_user_id=? and r.status='ACTIVE' and st.is_active and u.is_active order by r.created_at desc limit 100",UUID.class,actor.id()).stream().map(this::view).toList());
    }
    private Map<String,Object> view(UUID id) {
        var row=s.one("select id,owner_user_id,station_id,status,expires_at,version,operation_id,correlation_id from reservations where id=?",id);
        row.put("items",s.db.queryForList("select i.document_id,i.route_step_id,i.reserved_version,d.version as expected_version,d.version as document_version,d.business_state,d.process_sync_status,d.latest_operation_id,d.tracking_mode from reservation_items i join documents d on d.id=i.document_id where i.reservation_id=?",id));return row;
    }
}
