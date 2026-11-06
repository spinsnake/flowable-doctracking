package th.ac.mfu.doctracking.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import th.ac.mfu.doctracking.common.ApiException;
import th.ac.mfu.doctracking.identity.Actor;

import java.util.*;

@Component
public class TrackingStore {
    final JdbcTemplate db;
    final ObjectMapper json;
    public TrackingStore(JdbcTemplate db, ObjectMapper json) { this.db = db; this.json = json; }
    String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot encode tracking data", e); }
    }
    Map<String,Object> decode(String value) {
        try { return json.readValue(value, new TypeReference<>() {}); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot decode tracking data", e); }
    }
    List<UUID> stationIds(String value) {
        try { return json.readValue(value, new TypeReference<List<UUID>>() {}); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot decode route", e); }
    }
    Map<String,Object> one(String sql, Object... args) {
        var rows = db.queryForList(sql, args);
        if (rows.isEmpty()) throw new ApiException(404,"NOT_FOUND","Resource not found");
        return rows.getFirst();
    }
    static UUID id(Map<String,Object> row,String key) { return (UUID) row.get(key); }
    static long version(Map<String,Object> row) { return ((Number) row.get("version")).longValue(); }
    Map<String,Object> document(UUID id,boolean lock) {
        return one("select * from documents where id=?"+(lock?" for update":""), id);
    }
    void origin(Actor actor, UUID unit) {
        if (!actor.hasUnit(unit)) throw new ApiException(403,"ORIGIN_SCOPE_REQUIRED","Origin unit membership required");
    }
    void visible(Actor actor, Map<String,Object> document) {
        // P0 field/evidence scope is pending: fail closed for full detail outside the origin.
        if (!actor.hasUnit(id(document,"origin_unit_id"))) throw new ApiException(404,"NOT_FOUND","Resource not found");
    }
    Map<String,Object> view(Map<String,Object> row, boolean minimal) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (String key : List.of("id","title","document_number","origin_unit_id","origin_station_id","tracking_mode","policy_version",
                "business_state","version","current_route_step_id","pending_receipt_step_id","reported_location_step_id",
                "active_leg_id","active_reservation_id","process_sync_status","latest_operation_id")) result.put(key,row.get(key));
        if(minimal) { result.put("title",null); result.put("document_number",null); }
        result.put("route_snapshot",db.queryForList("select id as step_id,sequence,station_id from route_steps where document_id=? order by sequence",row.get("id")));
        return result;
    }
    static String clean(String value,int limit) {
        if(value==null || value.isBlank()) return null;
        value=value.trim();
        if(value.length()>limit) throw new ApiException(422,"INVALID_INPUT","Text exceeds allowed length");
        return value;
    }
    static void checkVersion(Map<String,Object> row,long expected) {
        if(expected<0 || version(row)!=expected) throw new ApiException(409,"STALE_VERSION","Resource version changed");
    }
    UUID event(UUID documentId,UUID actorId,UUID step,String type,UUID operation,Object payload,boolean workflow) {
        long seq = db.queryForObject("update documents set event_sequence=event_sequence+1 where id=? returning event_sequence", Long.class, documentId);
        UUID event=UUID.randomUUID();
        db.update("insert into document_events(id,document_id,sequence,event_type,actor_id,route_step_id,operation_id,payload) values(?,?,?,?,?,?,?,?::jsonb)",
                event,documentId,seq,type,actorId,step,operation,encode(payload));
        if(workflow) db.update("insert into workflow_outbox(id,event_id,document_id,sequence,action,payload) values(?,?,?,?,?,?::jsonb)",
                UUID.randomUUID(),event,documentId,seq,type,encode(payload));
        return event;
    }
    Map<String,Object> outcome(UUID operation, UUID document, UUID event, String sync) {
        var row=document(document,false);
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("operation_id",operation); result.put("document_ids",List.of(document)); result.put("accepted",List.of(document));
        result.put("rejected",List.of()); result.put("event_ids",List.of(event)); result.put("process_sync_status",sync); result.put("correlation_id",operation.toString());
        result.put("updated_states",List.of(Map.of("document_id",document,"version",row.get("version"),"business_state",row.get("business_state"),"process_sync_status",sync)));
        return result;
    }
}
