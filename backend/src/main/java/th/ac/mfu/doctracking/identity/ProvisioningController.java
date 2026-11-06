package th.ac.mfu.doctracking.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import th.ac.mfu.doctracking.common.*;

@RestController @RequestMapping("/api/v1")
public class ProvisioningController {
    private final JdbcTemplate db; private final ActorService actors; private final IdentityService ids; private final CommandService commands;
    public ProvisioningController(JdbcTemplate db,ActorService actors,IdentityService ids,CommandService commands){this.db=db;this.actors=actors;this.ids=ids;this.commands=commands;}
    public record UnitInput(@NotBlank @Size(max=50) String code,@NotBlank @Size(max=255) String name){}
    public record UnitChange(@NotNull @Min(0) Long expected_version,@Size(min=1,max=255) String name,Boolean is_active,@NotBlank @Size(max=1000) String reason){}
    public record UserInput(@NotBlank String email,@NotBlank @Size(max=255) String display_name,@NotNull UUID unit_id,@NotBlank @Pattern(regexp="UNIT_ADMIN|STAFF") String unit_role){}
    public record UserChange(@NotNull @Min(0) Long expected_version,@Size(min=1,max=255) String display_name,UUID unit_id,@Pattern(regexp="UNIT_ADMIN|STAFF") String unit_role,Boolean is_active,@NotBlank @Size(max=1000) String reason){}
    public record StationInput(@NotNull UUID unit_id,@NotBlank @Size(max=50) String code,@NotBlank @Size(max=255) String name){}
    public record Grants(@NotNull @Min(0) Long expected_version,@NotNull Set<UUID> station_ids,@NotNull Set<@Pattern(regexp="COURIER|COORDINATOR|RESERVATION_RESOLVE") String> capabilities,@NotBlank @Size(max=1000) String reason){}
    private ResponseEntity<Object> response(CommandResult r){return ResponseEntity.status(r.status()).body(r.body());}
    @GetMapping("/units") Object units(@RequestParam(defaultValue="50") @Min(1) @Max(100) int limit,@RequestParam(required=false) UUID cursor){
        Actor a=actors.current();var rows=db.query("select id from units where (? or (is_active and id in (select unit_id from unit_memberships where user_id=?))) and (?::uuid is null or id>?::uuid) order by id limit ?",(r,n)->r.getObject(1,UUID.class),a.admin(),a.id(),cursor,cursor,limit+1);return page(rows,limit,ids::unit);
    }
    @PostMapping("/units") Object createUnit(@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody UnitInput in){
        Actor a=actors.current();a.requireAdmin();return response(commands.execute(a,"create-unit",key,in,()->{UUID id=UUID.randomUUID();db.update("insert into units(id,code,name) values (?,?,?)",id,in.code().strip(),in.name().strip());ids.audit(a,"UNIT_CREATED",id,"Create unit waiting for administrator");return new CommandResult(201,ids.unit(id));}));
    }
    @PatchMapping("/units/{id}") Object updateUnit(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody UnitChange in){
        Actor a=actors.current();a.requireAdmin();return response(commands.execute(a,"update-unit:"+id,key,in,()->{
            ids.lockUnit(id);version(ids.unit(id),in.expected_version());
            if(Boolean.FALSE.equals(in.is_active())) ids.outstanding(null,id);
            if(Boolean.TRUE.equals(in.is_active()) && db.queryForObject("select count(*) from unit_memberships m join users u on u.id=m.user_id where m.unit_id=? and m.role='UNIT_ADMIN' and u.is_active",Long.class,id)==0) throw new ApiException(409,"UNIT_ADMIN_REQUIRED","Appoint an active administrator first");
            db.update("update units set name=coalesce(?,name),is_active=coalesce(?,is_active),version=version+1 where id=?",clean(in.name()),in.is_active(),id);
            db.update("update users set session_version=session_version+1 where id in (select user_id from unit_memberships where unit_id=?)",id);ids.audit(a,"UNIT_UPDATED",id,in.reason());return new CommandResult(200,ids.unit(id));
        }));
    }
    @GetMapping("/users") Object users(@RequestParam(required=false) UUID unit_id,@RequestParam(defaultValue="50") @Min(1) @Max(100) int limit,@RequestParam(required=false) UUID cursor){
        Actor a=actors.current();if(!a.admin() && (unit_id==null || !a.unitAdmin(unit_id))) throw new ApiException(403,"FORBIDDEN","Select an administered unit");
        var rows=db.query("select id from users where (?::uuid is null or id in (select user_id from unit_memberships where unit_id=?)) and (?::uuid is null or id>?::uuid) order by id limit ?",(r,n)->r.getObject(1,UUID.class),unit_id,unit_id,cursor,cursor,limit+1);return page(rows,limit,id->scopedUser(a,id));
    }
    @PostMapping("/users") Object createUser(@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody UserInput in){
        Actor a=actors.current();provisionScope(a,in.unit_id(),in.unit_role());String email=IdentityService.normalizeEmail(in.email());if(email.equals(a.email())) throw new ApiException(403,"SELF_ESCALATION","Cannot provision your own privileges");
        return response(commands.execute(a,"create-user",key,in,()->{
            ids.lockUnit(in.unit_id()); UUID id=UUID.randomUUID();db.update("insert into users(id,email,display_name) values (?,?,?)",id,email,in.display_name().strip());
            db.update("insert into unit_memberships(user_id,unit_id,role) values (?,?,?)",id,in.unit_id(),in.unit_role());
            if(in.unit_role().equals("UNIT_ADMIN")) db.update("update units set is_active=true,version=version+1 where id=?",in.unit_id());
            else if(!Boolean.TRUE.equals(ids.unit(in.unit_id()).get("is_active"))) throw new ApiException(409,"UNIT_INACTIVE","Appoint a unit administrator first");
            ids.audit(a,"USER_PROVISIONED",id,"Provision "+in.unit_role());return new CommandResult(201,scopedUser(a,id));
        }));
    }
    @PatchMapping("/users/{id}") Object updateUser(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody UserChange in){
        Actor a=actors.current();checkUserScope(a,id,in.unit_id());if(id.equals(a.id()) && (in.unit_role()!=null || in.unit_id()!=null || in.is_active()!=null)) throw new ApiException(403,"SELF_ESCALATION","Cannot change your own access");
        if(in.unit_role()!=null && in.unit_id()==null) throw new ApiException(422,"UNIT_REQUIRED","unit_id required with role");
        if(!a.admin() && in.unit_role()!=null && !in.unit_role().equals("STAFF")) throw new ApiException(403,"FORBIDDEN","Cannot appoint Unit Admin");
        return response(commands.execute(a,"update-user:"+id,key,in,()->{
            ids.lockUserUnits(id,in.unit_id(),Set.of());db.queryForList("select id from users where id=? for update",id);Map<String,Object> before=ids.user(id);version(before,in.expected_version());
            if(Boolean.FALSE.equals(in.is_active()) || in.unit_role()!=null){
                ids.outstanding(id,null);
                for(var m:db.queryForList("select unit_id from unit_memberships where user_id=? and role='UNIT_ADMIN'",id)){
                    UUID unit=(UUID)m.get("unit_id");if(Boolean.FALSE.equals(in.is_active()) || (unit.equals(in.unit_id()) && "STAFF".equals(in.unit_role()))) ids.preserveLastAdmin(id,unit);
                }
            }
            if(in.unit_role()!=null){ids.lockUnit(in.unit_id());db.update("insert into unit_memberships(user_id,unit_id,role) values (?,?,?) on conflict(user_id,unit_id) do update set role=excluded.role",id,in.unit_id(),in.unit_role());if("UNIT_ADMIN".equals(in.unit_role()) && !Boolean.FALSE.equals(in.is_active())) db.update("update units set is_active=true,version=version+1 where id=?",in.unit_id());}
            db.update("update users set display_name=coalesce(?,display_name),is_active=coalesce(?,is_active),version=version+1,session_version=session_version+1 where id=?",clean(in.display_name()),in.is_active(),id);ids.audit(a,"USER_UPDATED",id,in.reason());return new CommandResult(200,scopedUser(a,id));
        }));
    }
    private void provisionScope(Actor a,UUID unit,String role){if(!a.unitAdmin(unit) || (!a.admin() && !role.equals("STAFF"))) throw new ApiException(403,"FORBIDDEN","Unit provisioning denied");}
    private void checkUserScope(Actor a,UUID id,UUID unit){if(a.admin()) return;if(unit==null || !a.unitAdmin(unit) || db.queryForObject("select count(*) from unit_memberships where user_id=? and unit_id=? and role='STAFF'",Long.class,id,unit)==0 || db.queryForObject("select count(*) from users where id=? and is_admin",Long.class,id)>0 || db.queryForObject("select count(*) from unit_memberships where user_id=? and unit_id<>?",Long.class,id,unit)>0 || db.queryForObject("select count(*) from user_capabilities where user_id=?",Long.class,id)>0) throw new ApiException(403,"FORBIDDEN","Own-unit STAFF without external privileges required");}
    private Map<String,Object> scopedUser(Actor a,UUID id){Map<String,Object> user=ids.user(id);if(!a.admin()) user.put("memberships",db.query("select m.unit_id,m.role,u.is_active from unit_memberships m join units u on u.id=m.unit_id where m.user_id=?",(r,n)->Map.of("unit_id",r.getObject(1,UUID.class),"role",r.getString(2),"is_active",r.getBoolean(3)),id).stream().filter(m->a.unitAdmin((UUID)m.get("unit_id"))).toList());return user;}
    static void version(Map<String,Object> entity,long expected){if(((Number)entity.get("version")).longValue()!=expected) throw new ApiException(409,"STALE_VERSION","Reload the latest version");}
    private static String clean(String value){if(value==null)return null;String result=value.strip();if(result.isEmpty())throw new ApiException(422,"BLANK_VALUE","A nonblank value is required");return result;}
    private Object page(List<UUID> ids,int limit,java.util.function.Function<UUID,Map<String,Object>> view){var page=ids.stream().limit(limit).toList();Map<String,Object> out=new LinkedHashMap<>();out.put("items",page.stream().map(view).toList());out.put("next_cursor",ids.size()>limit?page.getLast().toString():null);return out;}
    @PostMapping("/stations") Object createStation(@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody StationInput in){Actor a=actors.current();a.requireAdmin();return response(commands.execute(a,"create-station",key,in,()->{ids.lockUnit(in.unit_id());UUID id=UUID.randomUUID();db.update("insert into stations(id,unit_id,code,name) values (?,?,?,?)",id,in.unit_id(),in.code(),in.name());ids.audit(a,"STATION_CREATED",id,"Explicit station configuration");return new CommandResult(201,db.queryForMap("select * from stations where id=?",id));}));}
    @PatchMapping("/users/{id}/grants") Object grants(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Grants in){Actor a=actors.current();a.requireAdmin();if(a.id().equals(id)) throw new ApiException(403,"SELF_ESCALATION","Cannot change own grants");return response(commands.execute(a,"grants:"+id,key,in,()->{ids.lockUserUnits(id,null,in.station_ids());db.queryForList("select id from users where id=? for update",id);version(ids.user(id),in.expected_version());ids.outstanding(id,null);db.update("delete from user_station_permissions where user_id=?",id);db.update("delete from user_capabilities where user_id=?",id);for(UUID s:in.station_ids()){if(db.queryForObject("select count(*) from stations s join units u on u.id=s.unit_id where s.id=? and s.is_active and u.is_active",Long.class,s)==0) throw new ApiException(422,"INVALID_STATION","Active station required");db.update("insert into user_station_permissions values (?,?)",id,s);}for(String c:in.capabilities())db.update("insert into user_capabilities values (?,?)",id,c);db.update("update users set version=version+1,session_version=session_version+1 where id=?",id);ids.audit(a,"GRANTS_UPDATED",id,in.reason());return new CommandResult(200,ids.user(id));}));}
}

