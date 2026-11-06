package th.ac.mfu.doctracking.domain;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.ac.mfu.doctracking.common.ApiException;
import th.ac.mfu.doctracking.common.CommandResult;
import th.ac.mfu.doctracking.identity.Actor;

import java.util.*;

@Service
public class RegistryService {
    private final TrackingStore s;
    private final EvidenceService evidence;
    public RegistryService(TrackingStore s,EvidenceService evidence) { this.s=s;this.evidence=evidence; }
    public record TemplateInput(String name,UUID origin_unit_id,List<UUID> station_ids,Integer expected_version) {}
    public record Registration(String title,String document_number,UUID origin_station_id,UUID template_id,Integer template_version) {}
    public record EvidenceAttachment(Long expected_version,List<UUID> evidence_upload_ids) {}

    public List<Map<String,Object>> templates(Actor actor) {
        return s.db.queryForList("select t.id,t.origin_unit_id,t.current_version as version,v.name,v.station_ids::text as station_ids from route_templates t join route_template_versions v on v.template_id=t.id and v.version=t.current_version order by v.created_at desc")
                .stream().filter(r -> actor.hasUnit(TrackingStore.id(r,"origin_unit_id"))).map(this::templateView).toList();
    }
    private Map<String,Object> templateView(Map<String,Object> row) {
        row.put("station_ids",s.stationIds((String)row.get("station_ids"))); return row;
    }
    private void validate(Actor actor,TemplateInput input) {
        if(input.origin_unit_id()==null || TrackingStore.clean(input.name(),255)==null || input.station_ids()==null || input.station_ids().size()<2 || input.station_ids().size()>100 || input.station_ids().stream().anyMatch(Objects::isNull))
            throw new ApiException(422,"INVALID_ROUTE","A named origin route with 2 to 100 stations is required");
        s.origin(actor,input.origin_unit_id());
        for(UUID station:input.station_ids()) s.one("select s.id from stations s join units u on u.id=s.unit_id where s.id=? and s.is_active and u.is_active for share of s,u",station);
        var first=s.one("select unit_id from stations where id=? and is_active=true",input.station_ids().getFirst());
        if(!input.origin_unit_id().equals(first.get("unit_id")) || !input.station_ids().getFirst().equals(input.station_ids().getLast()))
            throw new ApiException(422,"INVALID_ROUTE","Route must start and return to an origin station");
    }
    public CommandResult createTemplate(Actor actor, TemplateInput input) {
        validate(actor,input); UUID id=UUID.randomUUID();
        s.db.update("insert into route_templates(id,origin_unit_id,current_version) values(?,?,1)",id,input.origin_unit_id());
        return templateVersion(actor,id,1,input,201);
    }
    public CommandResult reviseTemplate(Actor actor,UUID id,TemplateInput input) {
        validate(actor,input);
        var old=s.one("select * from route_templates where id=? for update",id);
        s.origin(actor,TrackingStore.id(old,"origin_unit_id"));
        if(!input.origin_unit_id().equals(old.get("origin_unit_id"))) throw new ApiException(422,"IMMUTABLE_ORIGIN","Template origin cannot change");
        if(input.expected_version()==null || input.expected_version()!=((Number)old.get("current_version")).intValue()) throw new ApiException(409,"STALE_VERSION","Template changed");
        int version=input.expected_version()+1;
        s.db.update("update route_templates set current_version=? where id=?",version,id);
        return templateVersion(actor,id,version,input,200);
    }
    private CommandResult templateVersion(Actor actor,UUID id,int version,TemplateInput input,int status) {
        s.db.update("insert into route_template_versions(template_id,version,name,station_ids,created_by) values(?,?,?,?::jsonb,?)",id,version,input.name().trim(),s.encode(input.station_ids()),actor.id());
        return new CommandResult(status,Map.of("id",id,"version",version,"name",input.name().trim(),"origin_unit_id",input.origin_unit_id(),"station_ids",input.station_ids()));
    }
    public CommandResult register(Actor actor,Registration input) {
        String title=TrackingStore.clean(input.title(),500), number=TrackingStore.clean(input.document_number(),255);
        if((title==null && number==null) || input.origin_station_id()==null || input.template_id()==null || input.template_version()==null)
            throw new ApiException(422,"INVALID_REGISTRATION","Title or document number and a route are required");
        var template=s.one("select t.origin_unit_id,v.station_ids::text as station_ids from route_templates t join route_template_versions v on v.template_id=t.id where t.id=? and v.version=?",input.template_id(),input.template_version());
        UUID unit=TrackingStore.id(template,"origin_unit_id"); s.origin(actor,unit);
        var origin=s.one("select unit_id from stations where id=? and is_active=true",input.origin_station_id());
        List<UUID> stations=s.stationIds((String)template.get("station_ids"));
        if(!unit.equals(origin.get("unit_id")) || !input.origin_station_id().equals(stations.getFirst())) throw new ApiException(422,"ORIGIN_MISMATCH","Origin must match selected route");
        for(UUID station:stations) s.one("select s.id from stations s join units u on u.id=s.unit_id where s.id=? and s.is_active and u.is_active for share of s,u",station);
        var policy=s.one("select * from workflow_policy where id=true for share");
        UUID id=UUID.randomUUID(), firstStep=UUID.randomUUID(),operation=UUID.randomUUID();
        String mode=(String)policy.get("default_tracking_mode"), state=mode.equals("MODEL_A")?"AWAIT_PHYSICAL_PICKUP":"WAIT_ORIGIN_READY";
        s.db.update("insert into documents(id,title,document_number,origin_unit_id,origin_station_id,template_id,template_version,tracking_mode,policy_version,business_state,current_route_step_id,reported_location_step_id,created_by,latest_operation_id) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id,title,number,unit,input.origin_station_id(),input.template_id(),input.template_version(),mode,policy.get("version"),state,firstStep,firstStep,actor.id(),operation);
        for(int i=0;i<stations.size();i++) s.db.update("insert into route_steps(id,document_id,sequence,station_id) values(?,?,?,?)",i==0?firstStep:UUID.randomUUID(),id,i,stations.get(i));
        s.db.update("insert into outstanding_work(unit_id,kind,resource_id) select distinct s.unit_id,'DOCUMENT',? from route_steps r join stations s on s.id=r.station_id where r.document_id=?",id,id);
        UUID event=s.event(id,actor.id(),firstStep,"DOCUMENT_REGISTERED",operation,Map.of("document_id",id,"route_step_id",firstStep,"tracking_mode",mode,"policy_version",policy.get("version")),true);
        Map<String,Object> result=s.outcome(operation,id,event,"PENDING");
        s.db.update("insert into tracking_operations(id,actor_id,document_id,result) values(?,?,?,?::jsonb)",operation,actor.id(),id,s.encode(result));
        return new CommandResult(202,result);
    }
    public Map<String,Object> documents(Actor actor,String scope,UUID cursor,int limit) {
        if(!"origin".equals(scope)) throw new ApiException(422,"INVALID_SCOPE","Use origin scope");
        checkLimit(limit);
        var rows=s.db.queryForList("select d.* from documents d join unit_memberships m on m.unit_id=d.origin_unit_id join units u on u.id=m.unit_id where m.user_id=? and u.is_active and (?::uuid is null or d.id>?::uuid) order by d.id limit ?",actor.id(),cursor,cursor,limit+1);
        return page(rows,limit,r->s.view(r,false));
    }
    static void checkLimit(int limit) { if(limit<1 || limit>100) throw new ApiException(422,"INVALID_LIMIT","Limit must be 1 to 100"); }
    static Map<String,Object> page(List<Map<String,Object>> rows,int limit,java.util.function.Function<Map<String,Object>,Map<String,Object>> mapper) {
        boolean more=rows.size()>limit; var result=new LinkedHashMap<String,Object>();
        var chosen=rows.subList(0,Math.min(limit,rows.size())); result.put("items",chosen.stream().map(mapper).toList());
        result.put("next_cursor",more?chosen.getLast().get("id"):null); return result;
    }
    public Map<String,Object> detail(Actor actor,UUID id) { var row=s.document(id,false);s.visible(actor,row);return s.view(row,false); }
    public Map<String,Object> timeline(Actor actor,UUID id,long after,int limit) {
        s.visible(actor,s.document(id,false));checkLimit(limit);
        if(after<0) throw new ApiException(422,"INVALID_CURSOR","Cursor must be nonnegative");
        var rows=s.db.queryForList("select id,event_type,document_id,sequence,recorded_at,actor_id,route_step_id,from_step_id,to_step_id from document_events where document_id=? and sequence>? order by sequence limit ?",id,after,limit+1);
        boolean more=rows.size()>limit;var chosen=rows.subList(0,Math.min(rows.size(),limit));
        for(var row:chosen) { row.put("event_id",row.remove("id")); row.put("evidence_ids",s.db.queryForList("select evidence_id from event_evidence where event_id=?",UUID.class,row.get("event_id"))); }
        var result=new LinkedHashMap<String,Object>(); result.put("items",chosen); result.put("next_cursor",more?chosen.getLast().get("sequence"):null);return result;
    }
    public Map<String,Object> worklist(Actor actor,UUID station,UUID cursor,int limit) {
        checkLimit(limit);
        if(!actor.stationIds().contains(station)) throw new ApiException(403,"STATION_SCOPE_REQUIRED","Station permission required");
        actor.requireCapability("COURIER");
        var rows=s.db.queryForList("select d.* from documents d join route_steps r on r.id=d.current_route_step_id where r.station_id=? and d.process_sync_status='APPLIED' and d.active_leg_id is null and d.active_reservation_id is null and ((d.tracking_mode='MODEL_B' and d.business_state='READY_FOR_PICKUP') or (d.tracking_mode='MODEL_A' and d.business_state in ('AWAIT_PHYSICAL_PICKUP','DROPPED_OFF_UNCONFIRMED'))) and exists(select 1 from route_steps n where n.document_id=d.id and n.sequence=r.sequence+1) and (?::uuid is null or d.id>?::uuid) order by d.id limit ?",station,cursor,cursor,limit+1);
        return page(rows,limit,r->s.view(r,true));
    }
    public Map<String,Object> operation(Actor actor,UUID id) {
        var row=s.one("select * from tracking_operations where id=?",id);
        var document=s.document(TrackingStore.id(row,"document_id"),false);
        if(!actor.id().equals(row.get("actor_id"))) s.visible(actor,document);
        var result=s.decode(row.get("result").toString());result.put("process_sync_status",document.get("process_sync_status"));return result;
    }
    public CommandResult attachEvidence(Actor actor,UUID document,EvidenceAttachment input) {
        var doc=s.document(document,true);s.visible(actor,doc);
        if(input.expected_version()==null) throw new ApiException(422,"VERSION_REQUIRED","Expected version required");
        TrackingStore.checkVersion(doc,input.expected_version());
        evidence.requireFinalized(actor,input.evidence_upload_ids(),document,TrackingStore.id(doc,"origin_station_id"));
        UUID operation=UUID.randomUUID();
        s.db.update("update documents set version=version+1,latest_operation_id=? where id=?",operation,document);
        UUID event=s.event(document,actor.id(),TrackingStore.id(doc,"current_route_step_id"),"EVIDENCE_ATTACHED",operation,Map.of("evidence_ids",input.evidence_upload_ids()),false);
        for(UUID photo:input.evidence_upload_ids()) s.db.update("insert into event_evidence(event_id,evidence_id) values(?,?)",event,photo);
        var result=s.outcome(operation,document,event,doc.get("process_sync_status").toString());
        s.db.update("insert into tracking_operations(id,actor_id,document_id,result) values(?,?,?,?::jsonb)",operation,actor.id(),document,s.encode(result));
        return new CommandResult(200,result);
    }
}
