package th.ac.mfu.doctracking.identity;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import th.ac.mfu.doctracking.common.ApiException;

@Service
public class ActorService {
    private final JdbcTemplate db;
    private final HttpServletRequest request;
    public ActorService(JdbcTemplate db, HttpServletRequest request) { this.db=db; this.request=request; }
    public Actor current() {
        var session=request.getSession(false);
        if (session==null || session.getAttribute("uid")==null) throw new ApiException(401,"UNAUTHENTICATED","Login required");
        UUID id=UUID.fromString(session.getAttribute("uid").toString());
        long version=((Number)session.getAttribute("session_version")).longValue();
        var actor=load(id);
        if(actor==null || actor.sessionVersion()!=version) { session.invalidate(); throw new ApiException(401,"SESSION_REVOKED","Login required"); }
        return actor;
    }
    public Actor load(UUID id) {
        var users=db.query("select * from users where id=? and is_active",(r,n)->new Object[]{r.getString("email"),r.getString("display_name"),r.getBoolean("is_admin"),r.getLong("session_version")},id);
        if(users.isEmpty()) return null;
        Object[] u=users.getFirst();
        var memberships=db.query("select m.unit_id,m.role from unit_memberships m join units u on u.id=m.unit_id where m.user_id=? and u.is_active",(r,n)->new Actor.Membership(r.getObject(1,UUID.class),r.getString(2)),id);
        var stations=new HashSet<>(db.query("select p.station_id from user_station_permissions p join stations s on s.id=p.station_id join units u on u.id=s.unit_id where p.user_id=? and s.is_active and u.is_active",(r,n)->r.getObject(1,UUID.class),id));
        var caps=new HashSet<>(db.queryForList("select capability from user_capabilities where user_id=?",String.class,id));
        if(!(Boolean)u[2] && memberships.isEmpty() && stations.isEmpty()) return null;
        return new Actor(id,(String)u[0],(String)u[1],(Boolean)u[2],memberships,stations,caps,(Long)u[3]);
    }
}
