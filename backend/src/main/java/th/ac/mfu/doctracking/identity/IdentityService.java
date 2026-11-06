package th.ac.mfu.doctracking.identity;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.ac.mfu.doctracking.common.ApiException;

@Service
public class IdentityService implements ApplicationRunner {
    private final JdbcTemplate db;
    private final String bootstrapEmail;
    public IdentityService(JdbcTemplate db,@Value("${app.bootstrap-admin-email:}") String bootstrapEmail) {this.db=db;this.bootstrapEmail=bootstrapEmail;}
    @Override @Transactional public void run(ApplicationArguments args) {
        if(bootstrapEmail.isBlank()) return;
        db.execute("select pg_advisory_xact_lock(72410287)");
        String email=normalizeEmail(bootstrapEmail);
        var existing=db.queryForList("select is_admin from users where email=?",email);
        if(!existing.isEmpty()) {
            if(!Boolean.TRUE.equals(existing.getFirst().get("is_admin"))) throw new IllegalStateException("Bootstrap email already belongs to a non-admin; explicit migration required");
            return;
        }
        if(db.queryForObject("select count(*) from users where is_admin",Long.class)>0) throw new IllegalStateException("Bootstrap cannot add another admin");
        db.update("insert into users(id,email,display_name,is_admin) values (?,?,?,true)",UUID.randomUUID(),email,"Central Admin");
    }
    public static String normalizeEmail(String input) {
        String email=input==null?"":input.strip().toLowerCase(Locale.ROOT);
        if(!email.matches("^[^@\\s]+@mfu\\.ac\\.th$")) throw new ApiException(422,"INVALID_MFU_EMAIL","Use an @mfu.ac.th email");
        return email;
    }
    public void audit(Actor actor,String action,UUID resource,String reason) {db.update("insert into audit_events(actor_id,action,resource_id,reason) values (?,?,?,?)",actor.id(),action,resource,reason);}
    public Map<String,Object> unit(UUID id) {
        return db.query("select * from units where id=?",(r,n)->{
            Map<String,Object> m=new LinkedHashMap<>(); m.put("id",id);m.put("code",r.getString("code"));m.put("name",r.getString("name"));m.put("is_active",r.getBoolean("is_active"));m.put("provisioning_status",r.getBoolean("is_active")?"READY":"WAITING_UNIT_ADMIN");m.put("version",r.getLong("version"));return m;
        },id).stream().findFirst().orElseThrow(()->new ApiException(404,"NOT_FOUND","Unit not found"));
    }
    public Map<String,Object> user(UUID id) {
        var m=db.query("select * from users where id=?",(r,n)->{
            Map<String,Object> u=new LinkedHashMap<>();u.put("id",id);u.put("email",r.getString("email"));u.put("display_name",r.getString("display_name"));u.put("is_active",r.getBoolean("is_active"));u.put("identity_status",r.getString("google_sub")==null?"AWAITING_FIRST_GOOGLE_LOGIN":"GOOGLE_LINKED");u.put("version",r.getLong("version"));
            List<String> roles=new ArrayList<>();if(r.getBoolean("is_admin")) roles.add("ADMIN");if(db.queryForObject("select count(*) from user_capabilities where user_id=? and capability='COURIER'",Long.class,id)>0) roles.add("COURIER");u.put("system_roles",roles);return u;
        },id).stream().findFirst().orElseThrow(()->new ApiException(404,"NOT_FOUND","User not found"));
        m.put("memberships",db.query("select m.unit_id,m.role,u.is_active from unit_memberships m join units u on u.id=m.unit_id where m.user_id=? order by m.unit_id",(r,n)->Map.of("unit_id",r.getObject(1,UUID.class),"role",r.getString(2),"is_active",r.getBoolean(3)),id));m.put("station_ids",db.queryForList("select station_id from user_station_permissions where user_id=?",UUID.class,id));m.put("capabilities",db.queryForList("select capability from user_capabilities where user_id=?",String.class,id));return m;
    }
    public void lockUnit(UUID id) {if(db.queryForList("select id from units where id=? for update",id).isEmpty()) throw new ApiException(404,"NOT_FOUND","Unit not found");}
    public void lockUserUnits(UUID user,UUID requestedUnit,Set<UUID> requestedStations) {
        Set<UUID> units=new TreeSet<>(Comparator.comparing(UUID::toString));
        units.addAll(db.queryForList("select unit_id from unit_memberships where user_id=? union select s.unit_id from stations s join user_station_permissions p on p.station_id=s.id where p.user_id=?",UUID.class,user,user));
        if(requestedUnit!=null)units.add(requestedUnit);
        for(UUID station:requestedStations)units.addAll(db.queryForList("select unit_id from stations where id=?",UUID.class,station));
        for(UUID unit:units)lockUnit(unit);
    }
    public void outstanding(UUID user,UUID unit) {
        long count=db.queryForObject("select count(*) from outstanding_work where resolved_at is null and (user_id=? or unit_id=?)",Long.class,user,unit);
        if(count>0) throw new ApiException(409,"OUTSTANDING_WORK","Resolve outstanding work before disabling or moving access");
    }
    public void preserveLastAdmin(UUID user,UUID unit) {
        lockUnit(unit);
        var row=unit(unit);
        if(Boolean.TRUE.equals(row.get("is_active")) && db.queryForObject("select count(*) from unit_memberships m join users u on u.id=m.user_id where m.unit_id=? and m.role='UNIT_ADMIN' and u.is_active and m.user_id<>?",Long.class,unit,user)==0)
            throw new ApiException(409,"LAST_UNIT_ADMIN","Active unit requires an active Unit Admin");
    }
    public void revoke(UUID user) {db.update("update users set session_version=session_version+1 where id=?",user);}
}
