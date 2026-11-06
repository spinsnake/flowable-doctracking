package th.ac.mfu.doctracking.identity;

import jakarta.servlet.http.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.SerializationUtils;

@Component
public class OAuthCorrelationRepository implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {
    private final JdbcTemplate db;private final boolean secure;
    public OAuthCorrelationRepository(JdbcTemplate db,@Value("${app.session-secure:true}") boolean secure){this.db=db;this.secure=secure;}
    private String state(HttpServletRequest req){String state=req.getParameter("state");if(state==null || state.length()>512 || req.getCookies()==null)return null;boolean match=Arrays.stream(req.getCookies()).anyMatch(c->c.getName().equals("OAUTH_STATE") && c.getValue().equals(state));return match?state:null;}
    @Override public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest req){String state=state(req);if(state==null)return null;return db.query("select payload from oauth_login_requests where state=? and expires_at>now()",(r,n)->(OAuth2AuthorizationRequest)SerializationUtils.deserialize(r.getBytes(1)),state).stream().findFirst().orElse(null);}
    @Override public void saveAuthorizationRequest(OAuth2AuthorizationRequest value,HttpServletRequest req,HttpServletResponse res){if(value==null){removeAuthorizationRequest(req,res);return;}db.update("delete from oauth_login_requests where expires_at<now()");db.update("insert into oauth_login_requests(state,payload,expires_at) values (?,?,?)",value.getState(),SerializationUtils.serialize(value),Timestamp.from(Instant.now().plusSeconds(600)));cookie(res,value.getState(),600);}
    @Override public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest req,HttpServletResponse res){String state=state(req);cookie(res,"",0);if(state==null)return null;return db.query("delete from oauth_login_requests where state=? and expires_at>now() returning payload",(r,n)->(OAuth2AuthorizationRequest)SerializationUtils.deserialize(r.getBytes(1)),state).stream().findFirst().orElse(null);}
    private void cookie(HttpServletResponse res,String value,int age){res.addHeader("Set-Cookie","OAUTH_STATE="+value+"; Path=/; HttpOnly; SameSite=Lax; Max-Age="+age+(secure?"; Secure":""));}
}
