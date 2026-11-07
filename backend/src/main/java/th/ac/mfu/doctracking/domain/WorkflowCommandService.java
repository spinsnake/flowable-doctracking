package th.ac.mfu.doctracking.domain;

import org.springframework.stereotype.Service;
import th.ac.mfu.doctracking.common.ApiException;
import th.ac.mfu.doctracking.common.CommandResult;
import th.ac.mfu.doctracking.identity.Actor;

import java.util.*;

/** Business mutations run in CommandService's transaction; Flowable consumes their committed outbox. */
@Service
public class WorkflowCommandService {
    private final TrackingStore s;
    private final EvidenceService evidence;
    private final BulkValidation bulk;
    public WorkflowCommandService(TrackingStore s,EvidenceService evidence,BulkValidation bulk) { this.s=s;this.evidence=evidence;this.bulk=bulk; }
    public record Step(UUID route_step_id,Long expected_version) {}
    public record Item(UUID document_id,UUID route_step_id,Long expected_version,UUID leg_id) {}
    public record Batch(UUID station_id,List<Item> items) {}
    public record Pickup(UUID station_id,List<Item> items,List<UUID> evidence_upload_ids,UUID reservation_id,Boolean physical_presence_confirmed) {}
    public record Dropoff(UUID station_id,List<Item> items,List<UUID> evidence_upload_ids) {}

    private void require(boolean condition,String code,String message) {
        if(!condition) throw new ApiException(409,code,message);
    }
    private Map<String,Object> step(UUID document,UUID step) {
        if(document==null || step==null) throw new ApiException(422,"STEP_REQUIRED","Document and exact route step required");
        return s.one("select r.*,t.unit_id from route_steps r join stations t on t.id=r.station_id where r.document_id=? and r.id=?",document,step);
    }
    private void activeStation(UUID station) {
        s.one("select s.id from stations s join units u on u.id=s.unit_id where s.id=? and s.is_active and u.is_active for share of s,u",station);
    }
    private void staff(Actor actor,Map<String,Object> step) {
        if(!actor.hasUnit(TrackingStore.id(step,"unit_id")) || !actor.hasStation(TrackingStore.id(step,"station_id")))
            throw new ApiException(403,"STATION_SCOPE_REQUIRED","Unit membership and station permission required");
        activeStation(TrackingStore.id(step,"station_id"));
    }
    private void courier(Actor actor,UUID station) {
        actor.requireCapability("COURIER");
        if(station==null || !actor.hasStation(station)) throw new ApiException(403,"STATION_SCOPE_REQUIRED","Courier station permission required");
        activeStation(station);
    }
    private List<Item> items(List<Item> input) {
        var items=bulk.items(input,Item::document_id);
        for(Item item:items) if(item.route_step_id()==null || item.expected_version()==null || item.expected_version()<0)
            throw new ApiException(422,"INVALID_ITEM","Document, exact route step and version required");
        return items;
    }
    private CommandResult finish(Actor actor,UUID operation,List<UUID> documents,List<UUID> events,List<Map<String,Object>> rejected) {
        var result=s.bulkOutcome(operation,documents,events,rejected,documents.isEmpty()?"APPLIED":"PENDING");
        s.storeOperation(operation,actor.id(),result,documents);
        return new CommandResult(documents.isEmpty()?200:202,result);
    }
    private Map<String,Object> mutation(UUID document,Long expected) {
        if(expected==null || expected<0) throw new ApiException(422,"VERSION_REQUIRED","Expected version required");
        var doc=s.document(document,true);TrackingStore.checkVersion(doc,expected);
        require("APPLIED".equals(doc.get("process_sync_status")),"WORKFLOW_SYNC_PENDING","Wait for workflow synchronization");
        return doc;
    }
    public void authorizeReady(Actor actor,UUID document,Step input) {
        var doc=s.document(document,false);var step=step(document,input.route_step_id());
        if(((Number)step.get("sequence")).intValue()==0) s.origin(actor,TrackingStore.id(doc,"origin_unit_id"));
        else staff(actor,step);
    }
    public CommandResult ready(Actor actor,UUID document,Step input) {
        authorizeReady(actor,document,input);
        var doc=mutation(document,input.expected_version());var step=step(document,input.route_step_id());
        require("MODEL_B".equals(doc.get("tracking_mode")),"MODEL_MISMATCH","Model A has no unit ready action");
        require(input.route_step_id().equals(doc.get("current_route_step_id")),"STALE_STEP","Ready must reference current route occurrence");
        boolean origin=((Number)step.get("sequence")).intValue()==0;
        require((origin?"WAIT_ORIGIN_READY":"UNIT_PROCESSING").equals(doc.get("business_state")),"INVALID_STATE","Unit cannot mark this document ready");
        require(doc.get("active_leg_id")==null && doc.get("active_reservation_id")==null && doc.get("pending_receipt_step_id")==null,"CUSTODY_CONFLICT","Document still has outstanding custody");
        s.one("select id from route_steps where document_id=? and sequence=?",document,((Number)step.get("sequence")).intValue()+1);
        UUID operation=UUID.randomUUID();
        s.db.update("update documents set business_state='READY_FOR_PICKUP',version=version+1,process_sync_status='PENDING',latest_operation_id=? where id=?",operation,document);
        return queue(actor,doc,input.route_step_id(),null,null,"UNIT_READY",origin?"ORIGIN_READY":"UNIT_PROCESS_AND_READY",operation,Map.of(),null);
    }
    public void authorizeReturn(Actor actor,UUID document,Step input) {
        var doc=s.document(document,false);s.origin(actor,TrackingStore.id(doc,"origin_unit_id"));step(document,input.route_step_id());
    }
    public CommandResult confirmReturn(Actor actor,UUID document,Step input) {
        authorizeReturn(actor,document,input);var doc=mutation(document,input.expected_version());var step=step(document,input.route_step_id());
        require("MODEL_B".equals(doc.get("tracking_mode")),"MODEL_MISMATCH","Model A has no origin receipt action");
        require("PENDING_ORIGIN_RETURN_RECEIPT".equals(doc.get("business_state")) && input.route_step_id().equals(doc.get("pending_receipt_step_id")) && finalStep(document,step),"STALE_STEP","Final pending return occurrence required");
        require(doc.get("origin_station_id").equals(step.get("station_id")),"RETURN_MISMATCH","Final step must return to origin station");
        UUID operation=UUID.randomUUID();
        s.db.update("update documents set business_state='COMPLETED',current_route_step_id=?,reported_location_step_id=?,pending_receipt_step_id=null,version=version+1,process_sync_status='PENDING',latest_operation_id=? where id=?",input.route_step_id(),input.route_step_id(),operation,document);
        resolveDocument(document);
        return queue(actor,doc,input.route_step_id(),null,null,"ORIGIN_RETURN_CONFIRMED","ORIGIN_RETURN_RECEIVE",operation,Map.of(),null);
    }
    public void authorizeReceipt(Actor actor,UUID station,Batch input) {
        var items=items(input.items());
        if(!Objects.equals(station,input.station_id())) throw new ApiException(422,"STATION_MISMATCH","Path and body station must match");
        var stationRow=s.one("select id as station_id,unit_id from stations where id=?",station);staff(actor,stationRow);
        if(items.size()==1) authorizeReceiptItem(actor,station,items.getFirst());
    }
    private void authorizeReceiptItem(Actor actor,UUID station,Item item) {
        var step=step(item.document_id(),item.route_step_id());
        if(!station.equals(step.get("station_id"))) throw new ApiException(422,"STEP_MISMATCH","Receipt step must belong to station");
        staff(actor,step);
    }
    public CommandResult confirmReceipt(Actor actor,UUID station,Batch input) {
        authorizeReceipt(actor,station,input);var items=items(input.items());
        bulk.lockDocuments(items.stream().map(Item::document_id).toList());
        UUID operation=UUID.randomUUID();List<UUID> accepted=new ArrayList<>(),events=new ArrayList<>();List<Map<String,Object>> rejected=new ArrayList<>();
        for(Item item:items) {
            UUID event=bulk.attempt(item.document_id(),items.size()==1,rejected,()->{
                authorizeReceiptItem(actor,station,item);var doc=mutation(item.document_id(),item.expected_version());var step=step(item.document_id(),item.route_step_id());
                require("MODEL_B".equals(doc.get("tracking_mode")),"MODEL_MISMATCH","Model A has no unit receipt action");
                require("PENDING_UNIT_RECEIPT".equals(doc.get("business_state")) && item.route_step_id().equals(doc.get("pending_receipt_step_id")) && !finalStep(item.document_id(),step),"STALE_STEP","Pending intermediate route occurrence required");
                s.db.update("update documents set business_state='UNIT_PROCESSING',current_route_step_id=?,reported_location_step_id=?,pending_receipt_step_id=null,version=version+1,process_sync_status='PENDING',latest_operation_id=? where id=?",item.route_step_id(),item.route_step_id(),operation,item.document_id());
                return queueEvent(actor,doc,item.route_step_id(),null,null,"UNIT_RECEIPT_CONFIRMED","UNIT_RECEIVE",operation,Map.of(),null);
            });
            if(event!=null) {accepted.add(item.document_id());events.add(event);}
        }
        return finish(actor,operation,accepted,events,rejected);
    }
    public void authorizePickup(Actor actor,Pickup input) {
        var items=items(input.items());courier(actor,input.station_id());
        if(input.reservation_id()==null) throw new ApiException(422,"RESERVATION_REQUIRED","Reservation required");
        var reservation=s.one("select owner_user_id,station_id from reservations where id=?",input.reservation_id());
        if(!actor.id().equals(reservation.get("owner_user_id"))) throw new ApiException(403,"RESERVATION_OWNER_REQUIRED","Reservation owner required");
        if(!input.station_id().equals(reservation.get("station_id"))) throw new ApiException(422,"STATION_MISMATCH","Pickup source must match reservation station");
        if(items.size()==1) authorizePickupItem(input,items.getFirst());
    }
    private void authorizePickupItem(Pickup input,Item item) {
        if(!input.station_id().equals(step(item.document_id(),item.route_step_id()).get("station_id"))) throw new ApiException(422,"STATION_MISMATCH","Pickup source must match reservation station");
    }
    public CommandResult pickup(Actor actor,Pickup input) {
        authorizePickup(actor,input);var items=items(input.items());
        if(!Boolean.TRUE.equals(input.physical_presence_confirmed())) throw new ApiException(422,"PHYSICAL_PRESENCE_REQUIRED","Confirm physical possession before pickup");
        bulk.lockDocuments(items.stream().map(Item::document_id).toList());
        var reservation=s.one("select * from reservations where id=? for update",input.reservation_id());
        UUID operation=UUID.randomUUID();List<UUID> accepted=new ArrayList<>(),events=new ArrayList<>();List<Map<String,Object>> rejected=new ArrayList<>();
        for(Item item:items) {
            UUID event=bulk.attempt(item.document_id(),items.size()==1,rejected,()->{
                authorizePickupItem(input,item);var doc=mutation(item.document_id(),item.expected_version());
                require("ACTIVE".equals(reservation.get("status")) && Boolean.TRUE.equals(s.db.queryForObject("select expires_at>clock_timestamp() from reservations where id=?",Boolean.class,input.reservation_id())),"RESERVATION_EXPIRED","Unexpired active reservation required");
                var reserved=s.one("select * from reservation_items where reservation_id=? and document_id=?",input.reservation_id(),item.document_id());
                require("PICKUP_RESERVED".equals(doc.get("business_state")) && input.reservation_id().equals(doc.get("active_reservation_id")) && Boolean.TRUE.equals(reserved.get("is_active")) && item.route_step_id().equals(doc.get("current_route_step_id")) && item.route_step_id().equals(reserved.get("route_step_id")) && doc.get("active_leg_id")==null,"CUSTODY_CONFLICT","Reservation and current route occurrence must match");
                String previous=reserved.get("previous_state").toString();boolean modelB="MODEL_B".equals(doc.get("tracking_mode"));
                require(modelB?"READY_FOR_PICKUP".equals(previous):Set.of("AWAIT_PHYSICAL_PICKUP","DROPPED_OFF_UNCONFIRMED").contains(previous),"INVALID_STATE","Reservation source state is not eligible");
                var from=step(item.document_id(),item.route_step_id());var to=s.one("select * from route_steps where document_id=? and sequence=?",item.document_id(),((Number)from.get("sequence")).intValue()+1);
                evidence.requireFinalized(actor,input.evidence_upload_ids(),item.document_id(),input.station_id());
                UUID leg=UUID.randomUUID();
                s.db.update("update reservation_items set is_active=false where reservation_id=? and document_id=?",input.reservation_id(),item.document_id());
                s.db.update("insert into courier_legs(id,document_id,courier_user_id,from_step_id,to_step_id) values(?,?,?,?,?)",leg,item.document_id(),actor.id(),item.route_step_id(),to.get("id"));
                s.db.update("insert into outstanding_work(user_id,unit_id,kind,resource_id) select ?,unit_id,'LEG',? from stations where id=?",actor.id(),leg,input.station_id());
                s.db.update("update documents set business_state='IN_TRANSIT',active_reservation_id=null,active_leg_id=?,version=version+1,process_sync_status='PENDING',latest_operation_id=? where id=?",leg,operation,item.document_id());
                return queueEvent(actor,doc,item.route_step_id(),item.route_step_id(),TrackingStore.id(to,"id"),"COURIER_PICKED_UP","COURIER_PICKUP",operation,Map.of("leg_id",leg,"reservation_id",input.reservation_id(),"physical_presence_confirmed",true),input.evidence_upload_ids());
            });
            if(event!=null) {accepted.add(item.document_id());events.add(event);}
        }
        if(!accepted.isEmpty()) {
            boolean remaining=s.db.queryForObject("select exists(select 1 from reservation_items where reservation_id=? and is_active)",Boolean.class,input.reservation_id());
            s.db.update("update reservations set status=?,version=version+1 where id=?",remaining?"ACTIVE":"CONSUMED",input.reservation_id());
            if(!remaining) s.db.update("update outstanding_work set resolved_at=now() where kind='RESERVATION' and resource_id=? and resolved_at is null",input.reservation_id());
        }
        return finish(actor,operation,accepted,events,rejected);
    }
    public void authorizeDropoff(Actor actor,Dropoff input) {
        var items=items(input.items());courier(actor,input.station_id());
        if(items.size()==1) authorizeDropoffItem(actor,input,items.getFirst());
    }
    private void authorizeDropoffItem(Actor actor,Dropoff input,Item item) {
        if(item.leg_id()==null) throw new ApiException(422,"LEG_REQUIRED","Exact courier leg required");
        var leg=s.one("select * from courier_legs where id=? and document_id=?",item.leg_id(),item.document_id());
        if(!actor.id().equals(leg.get("courier_user_id"))) throw new ApiException(403,"LEG_OWNER_REQUIRED","Active leg owner required");
        if(!input.station_id().equals(step(item.document_id(),item.route_step_id()).get("station_id"))) throw new ApiException(422,"STEP_MISMATCH","Dropoff destination must belong to station");
    }
    public CommandResult dropoff(Actor actor,Dropoff input) {
        authorizeDropoff(actor,input);var items=items(input.items());bulk.lockDocuments(items.stream().map(Item::document_id).toList());
        UUID operation=UUID.randomUUID();List<UUID> accepted=new ArrayList<>(),events=new ArrayList<>();List<Map<String,Object>> rejected=new ArrayList<>();
        for(Item item:items) {
            UUID event=bulk.attempt(item.document_id(),items.size()==1,rejected,()->{
                authorizeDropoffItem(actor,input,item);var doc=mutation(item.document_id(),item.expected_version());
                var leg=s.one("select * from courier_legs where id=? for update",item.leg_id());var to=step(item.document_id(),item.route_step_id());
                require("IN_TRANSIT".equals(doc.get("business_state")) && item.leg_id().equals(doc.get("active_leg_id")) && leg.get("ended_at")==null && item.route_step_id().equals(leg.get("to_step_id")) && leg.get("from_step_id").equals(doc.get("current_route_step_id")),"CUSTODY_CONFLICT","Active leg and exact destination occurrence required");
                evidence.requireFinalized(actor,input.evidence_upload_ids(),item.document_id(),input.station_id());
                boolean modelB="MODEL_B".equals(doc.get("tracking_mode")), last=finalStep(item.document_id(),to);
                String state=modelB?(last?"PENDING_ORIGIN_RETURN_RECEIPT":"PENDING_UNIT_RECEIPT"):(last?"COURIER_TRACKING_COMPLETE":"DROPPED_OFF_UNCONFIRMED");
                s.db.update("update courier_legs set ended_at=now() where id=?",item.leg_id());
                s.db.update("update outstanding_work set resolved_at=now() where kind='LEG' and resource_id=? and resolved_at is null",item.leg_id());
                s.db.update("update documents set business_state=?,current_route_step_id=?,reported_location_step_id=?,pending_receipt_step_id=?,active_leg_id=null,version=version+1,process_sync_status='PENDING',latest_operation_id=? where id=?",state,modelB?doc.get("current_route_step_id"):item.route_step_id(),item.route_step_id(),modelB?item.route_step_id():null,operation,item.document_id());
                if(!modelB && last) resolveDocument(item.document_id());
                return queueEvent(actor,doc,item.route_step_id(),TrackingStore.id(leg,"from_step_id"),item.route_step_id(),"COURIER_DROPPED_OFF","COURIER_DROPOFF",operation,Map.of("leg_id",item.leg_id()),input.evidence_upload_ids());
            });
            if(event!=null) {accepted.add(item.document_id());events.add(event);}
        }
        return finish(actor,operation,accepted,events,rejected);
    }
    private boolean finalStep(UUID document,Map<String,Object> step) {
        return !s.db.queryForObject("select exists(select 1 from route_steps where document_id=? and sequence>?)",Boolean.class,document,step.get("sequence"));
    }
    private void resolveDocument(UUID document) { s.db.update("update outstanding_work set resolved_at=now() where kind='DOCUMENT' and resource_id=? and resolved_at is null",document); }
    private CommandResult queue(Actor actor,Map<String,Object> doc,UUID step,UUID from,UUID to,String type,String task,UUID operation,Map<String,Object> details,List<UUID> photos) {
        UUID document=TrackingStore.id(doc,"id");
        UUID event=queueEvent(actor,doc,step,from,to,type,task,operation,details,photos);
        var result=s.outcome(operation,document,event,"PENDING");
        s.storeOperation(operation,actor.id(),result,List.of(document));
        return new CommandResult(202,result);
    }
    private UUID queueEvent(Actor actor,Map<String,Object> doc,UUID step,UUID from,UUID to,String type,String task,UUID operation,Map<String,Object> details,List<UUID> photos) {
        UUID document=TrackingStore.id(doc,"id");var payload=new LinkedHashMap<String,Object>(details);
        payload.put("document_id",document);payload.put("route_step_id",step);payload.put("tracking_mode",doc.get("tracking_mode"));payload.put("policy_version",doc.get("policy_version"));payload.put("expected_task_key",task);
        if(from!=null) payload.put("from_step_id",from);if(to!=null) payload.put("to_step_id",to);
        UUID event=s.event(document,actor.id(),step,from,to,type,operation,payload,true);
        if(photos!=null) for(UUID photo:photos) s.db.update("insert into event_evidence(event_id,evidence_id) values(?,?)",event,photo);
        return event;
    }
}
