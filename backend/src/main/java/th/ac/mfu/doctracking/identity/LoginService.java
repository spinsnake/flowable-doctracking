package th.ac.mfu.doctracking.identity;

import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import th.ac.mfu.doctracking.common.ApiException;

@Service
public class LoginService {
    private final JdbcTemplate db; private final SessionRepository<Session> sessions; private final TransactionTemplate tx; private final ActorService actors; private final String hostedDomain;
    @SuppressWarnings("unchecked")
    public LoginService(JdbcTemplate db,JdbcIndexedSessionRepository sessions,PlatformTransactionManager manager,ActorService actors,@Value("${app.google-hosted-domain:mfu.ac.th}") String hd){this.db=db;this.sessions=(SessionRepository<Session>)(SessionRepository<?>)sessions;this.tx=new TransactionTemplate(manager);this.actors=actors;this.hostedDomain=hd;}
    public Session finalizeLogin(OidcUser google) {
        return finalizeLogin(google,List.of());
    }
    public Session finalizeLogin(OidcUser google,List<String> previousSessions) {
        // OIDC signature/issuer/audience/expiry/nonce/code+PKCE are verified by Spring Security before this boundary.
        var token=google.getIdToken();String rawEmail=token.getEmail();String subject=token.getSubject();
        String email=rawEmail==null?null:rawEmail.strip().toLowerCase(Locale.ROOT);
        if(!Boolean.TRUE.equals(token.getEmailVerified()) || email==null || !email.matches("^[^@\\s]+@mfu\\.ac\\.th$") || !hostedDomain.equals(token.getClaimAsString("hd")) || subject==null || subject.isBlank()) throw new ApiException(403,"IDENTITY_DENIED","Authorized MFU account required");
        return tx.execute(status->{
            var rows=db.queryForList("select id,google_sub,is_active from users where email=? for update",email);
            if(rows.isEmpty() || !Boolean.TRUE.equals(rows.getFirst().get("is_active"))) throw new ApiException(403,"NOT_ALLOWLISTED","Account is not enabled");
            var row=rows.getFirst(); UUID uid=(UUID)row.get("id");String sub=(String)row.get("google_sub");
            if(sub!=null && !sub.equals(subject)) throw new ApiException(403,"IDENTITY_MISMATCH","Google identity differs from linked account");
            Actor actor=actors.load(uid);if(actor==null)throw new ApiException(403,"NO_ACTIVE_SCOPE","No active access");
            if(sub==null) {db.update("update users set google_sub=?,email_verified_at=now(),version=version+1 where id=?",subject,uid);db.update("insert into auth_events(id,user_id,event_type) values (?,?,'GOOGLE_IDENTITY_LINKED')",UUID.randomUUID(),uid);}
            UUID event=UUID.randomUUID();Instant now=Instant.now();
            db.update("insert into auth_events(id,user_id,event_type,recorded_at) values (?,?,'LOGIN_SUCCEEDED',?)",event,uid,java.sql.Timestamp.from(now));
            db.update("insert into notification_outbox(id,login_event_id,recipient,login_at) values (?,?,?,?)",UUID.randomUUID(),event,email,java.sql.Timestamp.from(now));
            var session=sessions.createSession();session.setAttribute("uid",uid.toString());session.setAttribute("session_version",actor.sessionVersion());session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME,uid.toString());
            // Explicit save joins REQUIRED JDBC transaction; filter-delayed save is not the atomicity guarantee.
            for(String previous:previousSessions)sessions.deleteById(previous);
            sessions.save(session);return session;
        });
    }
}
