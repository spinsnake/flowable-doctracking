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
    private final BulkValidation bulk;
    public ReservationService(TrackingStore s,BulkValidation bulk,@Value("${app.reservation-ttl-seconds:600}") long ttl) {
        if(ttl<1 || ttl>86400) throw new IllegalArgumentException("Reservation TTL outside supported bounds");
        this.s=s;this.bulk=bulk;this.ttl=ttl;
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
        var items=bulk.items(input.items(),Item::document_id);
        for(var item:items) if(item.route_step_id()==null || item.expected_version()==null || item.expected_version()<0)
            throw new ApiException(422,"INVALID_ITEM","Document, exact step and version are required");
        bulk.lockDocuments(items.stream().map(Item::document_id).toList());
        UUID reservation=UUID.randomUUID(),operation=UUID.randomUUID();
        Instant expires=s.db.queryForObject("select clock_timestamp() + (? * interval '1 second')",java.sql.Timestamp.class,ttl).toInstant();
        s.db.update("insert into reservations(id,owner_user_id,station_id,status,expires_at,operation_id,correlation_id) values(?,?,?,'ACTIVE',?,?,?)",reservation,actor.id(),input.station_id(),java.sql.Timestamp.from(expires),operation,operation.toString());
        List<UUID> accepted=new ArrayList<>(),events=new ArrayList<>();List<Map<String,Object>> rejected=new ArrayList<>();
        for(var item:items) {
            UUID event=bulk.attempt(item.document_id(),items.size()==1,rejected,()->{
                var doc=s.document(item.document_id(),false);
                var step=s.one("select * from route_steps where document_id=? and id=?",item.document_id(),item.route_step_id());
                if(!input.station_id().equals(step.get("station_id"))) throw new ApiException(422,"STEP_MISMATCH","Step must belong to reservation station");
                if(doc.get("active_reservation_id")!=null) throw new ApiException(409,"RESERVATION_CONFLICT","Already reserved by another command");
                boolean direct=s.db.queryForObject("select exists(select 1 from direct_transfers where document_id=? and received_at is null)",Boolean.class,item.document_id());
                if(doc.get("active_leg_id")!=null || direct) throw new ApiException(409,"CUSTODY_CONFLICT","Already received or in active transport");
                TrackingStore.checkVersion(doc,item.expected_version());
                if(!item.route_step_id().equals(doc.get("current_route_step_id"))) throw new ApiException(409,"STALE_STEP","Document is at another route step");
                String state=(String)doc.get("business_state");
                boolean eligible="MODEL_B".equals(doc.get("tracking_mode"))?"READY_FOR_PICKUP".equals(state):Set.of("AWAIT_PHYSICAL_PICKUP","DROPPED_OFF_UNCONFIRMED").contains(state);
                boolean next=s.db.queryForObject("select exists(select 1 from route_steps where document_id=? and sequence=?)",Boolean.class,item.document_id(),((Number)step.get("sequence")).intValue()+1);
                if(!"APPLIED".equals(doc.get("process_sync_status"))) throw new ApiException(409,"WORKFLOW_SYNC_PENDING","Wait for workflow synchronization");
                if(!eligible || !next || direct || doc.get("active_leg_id")!=null || doc.get("active_reservation_id")!=null)
                    throw new ApiException(409,"RESERVATION_CONFLICT","Document cannot be reserved");
                s.db.update("insert into reservation_items(reservation_id,document_id,route_step_id,previous_state,reserved_version) values(?,?,?,?,?)",reservation,item.document_id(),item.route_step_id(),state,item.expected_version()+1);
                s.db.update("update documents set business_state='PICKUP_RESERVED',active_reservation_id=?,version=version+1,latest_operation_id=? where id=?",reservation,operation,item.document_id());
                return s.event(item.document_id(),actor.id(),item.route_step_id(),"PICKUP_RESERVED",operation,Map.of("reservation_id",reservation,"expires_at",expires.toString()),false);
            });
            if(event!=null) {accepted.add(item.document_id());events.add(event);}
        }
        Map<String,Object> result;
        if(accepted.isEmpty()) {s.db.update("delete from reservations where id=?",reservation);result=new LinkedHashMap<>();}
        else {
            s.db.update("insert into outstanding_work(user_id,unit_id,kind,resource_id) select ?,unit_id,'RESERVATION',? from stations where id=?",actor.id(),reservation,input.station_id());
            result=view(reservation);
        }
        result.putAll(s.bulkOutcome(operation,accepted,events,rejected,"APPLIED"));
        s.storeOperation(operation,actor.id(),result,accepted);
        return new CommandResult(accepted.isEmpty()?200:201,result);
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
        var items=activeItems(reservation);bulk.lockDocuments(items.stream().map(i->TrackingStore.id(i,"document_id")).toList());
        var row=s.one("select * from reservations where id=? for update",reservation);
        TrackingStore.checkVersion(row,version);
        if(!"ACTIVE".equals(row.get("status"))) throw new ApiException(409,"RESERVATION_CONFLICT","Reservation is no longer active");
        if(expired(row)) throw new ApiException(409,"RESERVATION_EXPIRED","Reservation expired; refresh worklist");
        items=activeItems(reservation);
        UUID operation=UUID.randomUUID();var events=release(items,reservation,actor.id(),operation,"CANCELLED","PICKUP_RESERVATION_CANCELLED",reason);
        var documents=items.stream().map(i->TrackingStore.id(i,"document_id")).toList();
        var result=view(reservation);result.putAll(s.bulkOutcome(operation,documents,events,List.of(),"APPLIED"));
        s.storeOperation(operation,actor.id(),result,documents);return new CommandResult(200,result);
    }
    private List<Map<String,Object>> activeItems(UUID reservation) {
        return s.db.queryForList("select * from reservation_items where reservation_id=? and is_active order by document_id",reservation);
    }
    private boolean expired(Map<String,Object> row) {
        return s.db.queryForObject("select expires_at<=clock_timestamp() from reservations where id=?",Boolean.class,row.get("id"));
    }
    private boolean releasable(Map<String,Object> doc,UUID reservation) {
        return reservation.equals(doc.get("active_reservation_id")) && doc.get("active_leg_id")==null && "PICKUP_RESERVED".equals(doc.get("business_state"));
    }
    private List<UUID> release(List<Map<String,Object>> items,UUID reservation,UUID actor,UUID operation,String status,String event,String reason) {
        // A pickup may have consumed a subset while this request waited for document locks.
        items=items.stream().filter(item->Boolean.TRUE.equals(s.one("select is_active from reservation_items where reservation_id=? and document_id=?",reservation,item.get("document_id")).get("is_active"))).toList();
        for(var item:items) if(!releasable(s.document(TrackingStore.id(item,"document_id"),false),reservation))
            throw new ApiException(409,"CUSTODY_CONFLICT","Reservation cannot release active custody");
        s.db.update("update reservations set status=?,version=version+1 where id=?",status,reservation);
        s.db.update("update reservation_items set is_active=false where reservation_id=? and is_active",reservation);
        s.db.update("update outstanding_work set resolved_at=now() where resource_id=? and kind='RESERVATION' and resolved_at is null",reservation);
        List<UUID> events=new ArrayList<>();
        for(var item:items) {
            UUID document=TrackingStore.id(item,"document_id");
            s.db.update("update documents set business_state=?,active_reservation_id=null,version=version+1,latest_operation_id=? where id=?",item.get("previous_state"),operation,document);
            Map<String,Object> payload=new LinkedHashMap<>();payload.put("reservation_id",reservation);payload.put("reason",TrackingStore.clean(reason,1000));
            events.add(s.event(document,actor,TrackingStore.id(item,"route_step_id"),event,operation,payload,false));
        }
        return events;
    }
    @Scheduled(fixedDelayString="${app.reservation.expiry-interval-ms:30000}")
    @Transactional
    public int expire() {
        var candidates=s.db.queryForList("select id from reservations where status='ACTIVE' and expires_at<=clock_timestamp() order by id limit 100",UUID.class);
        // Lock every candidate's documents once, in global order, before any reservation.
        var allItems=candidates.stream().flatMap(id->activeItems(id).stream()).toList();
        bulk.lockDocuments(allItems.stream().map(i->TrackingStore.id(i,"document_id")).toList());
        int count=0;
        for(UUID reservation:candidates) {
            var row=s.one("select * from reservations where id=? for update",reservation);
            if(!"ACTIVE".equals(row.get("status")) || !expired(row)) continue;
            var items=activeItems(reservation);
            if(items.stream().anyMatch(item->!releasable(s.document(TrackingStore.id(item,"document_id"),false),reservation))) continue;
            UUID operation=UUID.randomUUID();
            var events=release(items,reservation,null,operation,"EXPIRED","PICKUP_RESERVATION_EXPIRED",null);
            var documents=items.stream().map(i->TrackingStore.id(i,"document_id")).toList();
            var result=s.bulkOutcome(operation,documents,events,List.of(),"APPLIED");
            s.storeOperation(operation,TrackingStore.id(row,"owner_user_id"),result,documents);count++;
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
        row.put("items",s.db.queryForList("select i.document_id,i.route_step_id,i.reserved_version,d.version as expected_version,d.version as document_version,d.business_state,d.process_sync_status,d.latest_operation_id,d.tracking_mode from reservation_items i join documents d on d.id=i.document_id where i.reservation_id=? and i.is_active order by i.document_id",id));return row;
    }
}
