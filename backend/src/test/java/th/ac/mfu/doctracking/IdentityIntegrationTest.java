package th.ac.mfu.doctracking;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.*;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;
import th.ac.mfu.doctracking.identity.*;
import th.ac.mfu.doctracking.notifications.NotificationWorker;

@SpringBootTest(properties={"app.mail-enabled=true"}) @AutoConfigureMockMvc
class IdentityIntegrationTest extends DatabaseTest {
    @Autowired JdbcTemplate db;@Autowired LoginService login;@Autowired IdentityService ids;@Autowired ActorService actors;@Autowired JdbcIndexedSessionRepository sessions;@Autowired MockMvc http;@Autowired ObjectMapper json;@Autowired NotificationWorker worker;
    @MockitoBean JavaMailSender mail;
    UUID admin;String adminEmail;Cookie cookie;
    @BeforeEach void fixture(){admin=UUID.randomUUID();adminEmail="admin-"+admin+"@mfu.ac.th";db.update("insert into users(id,email,display_name,is_admin) values (?,?,?,true)",admin,adminEmail,"Admin");cookie=cookie(login.finalizeLogin(google(adminEmail,admin.toString(),true,"mfu.ac.th")));when(mail.createMimeMessage()).thenAnswer(call->new MimeMessage(Session.getInstance(new Properties())));}
    static OidcUser google(String email,String sub,boolean verified,String hd){Map<String,Object> claims=new HashMap<>();claims.put("sub",sub);claims.put("email",email);claims.put("email_verified",verified);claims.put("hd",hd);return new DefaultOidcUser(List.of(),new OidcIdToken("test-verified-provider-token",Instant.now().minusSeconds(5),Instant.now().plusSeconds(300),claims));}
    static Cookie cookie(org.springframework.session.Session session){return new Cookie("SESSION",Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));}
    String csrf(Cookie c)throws Exception{var result=http.perform(get("/api/v1/auth/csrf").cookie(c)).andExpect(status().isOk()).andReturn();return json.readTree(result.getResponse().getContentAsString()).get("token").asText();}
    String key(){return UUID.randomUUID().toString();}
    UUID unit(){UUID id=UUID.randomUUID();db.update("insert into units(id,code,name,is_active) values (?,?,?,true)",id,"U"+id,"Unit");return id;}
    UUID member(UUID unit,String role){UUID uid=UUID.randomUUID();db.update("insert into users(id,email,display_name) values (?,?,?)",uid,"user-"+uid+"@mfu.ac.th","Member");db.update("insert into unit_memberships values (?,?,?)",uid,unit,role);return uid;}
    Cookie memberCookie(UUID uid){return cookie(login.finalizeLogin(google(db.queryForObject("select email from users where id=?",String.class,uid),uid.toString(),true,"mfu.ac.th")));}
    @Test void loginAllowlistsLinksAndDoesNotEmailOnMe()throws Exception{
        assertThat(db.queryForObject("select google_sub from users where id=?",String.class,admin)).isEqualTo(admin.toString());assertThat(db.queryForObject("select email_verified_at is not null from users where id=?",Boolean.class,admin)).isTrue();
        long jobs=db.queryForObject("select count(*) from notification_outbox where recipient=?",Long.class,adminEmail);
        http.perform(get("/api/v1/auth/me").cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.system_roles[0]").value("ADMIN")).andExpect(jsonPath("$.google_sub").doesNotExist());
        assertThat(db.queryForObject("select count(*) from notification_outbox where recipient=?",Long.class,adminEmail)).isEqualTo(jobs);
        assertThatThrownBy(()->login.finalizeLogin(google(adminEmail,"different-sub",true,"mfu.ac.th"))).hasMessageContaining("differs");
        assertThatThrownBy(()->login.finalizeLogin(google("unknown@mfu.ac.th","new",true,"mfu.ac.th"))).hasMessageContaining("not enabled");
        for(OidcUser u:List.of(google("a@gmail.com","s",true,"mfu.ac.th"),google("a@mfu.ac.th.evil.com","s",true,"mfu.ac.th"),google(adminEmail,"s",false,"mfu.ac.th"),google(adminEmail,"s",true,"evil.com")))assertThatThrownBy(()->login.finalizeLogin(u)).hasMessageContaining("MFU");
    }
    @Test void loginSessionAuditOutboxRollbackAtEachPersistenceBoundary(){
        for(String boundary:List.of("session","audit","outbox")){
            UUID uid=UUID.randomUUID();String email="rollback-"+uid+"@mfu.ac.th";db.update("insert into users(id,email,display_name,is_admin) values (?,?,?,true)",uid,email,"Rollback");String constraint="fail_"+uid.toString().replace("-","");String table=boundary.equals("session")?"spring_session":boundary.equals("audit")?"auth_events":"notification_outbox";
            String condition=boundary.equals("session")?"principal_name <> '"+uid+"'":boundary.equals("audit")?"user_id <> '"+uid+"'::uuid":"recipient <> '"+email+"'";
            db.execute("alter table "+table+" add constraint "+constraint+" check ("+condition+")");
            try{assertThatThrownBy(()->login.finalizeLogin(google(email,uid.toString(),true,"mfu.ac.th"))).isInstanceOf(RuntimeException.class);}finally{db.execute("alter table "+table+" drop constraint "+constraint);}
            assertThat(db.queryForObject("select count(*) from auth_events where user_id=?",Long.class,uid)).isZero();assertThat(db.queryForObject("select count(*) from notification_outbox where recipient=?",Long.class,email)).isZero();assertThat(db.queryForObject("select count(*) from spring_session where principal_name=?",Long.class,uid.toString())).isZero();assertThat(db.queryForObject("select google_sub is null from users where id=?",Boolean.class,uid)).isTrue();
        }
    }
    @Test void concurrentFirstLinkCannotOverwriteIdentity()throws Exception{
        UUID uid=UUID.randomUUID();String email="race-"+uid+"@mfu.ac.th";db.update("insert into users(id,email,display_name,is_admin) values (?,?,?,true)",uid,email,"Race");
        try(var pool=Executors.newFixedThreadPool(2)){var gate=new CountDownLatch(1);var futures=List.of(pool.submit(()->{gate.await();try{login.finalizeLogin(google(email,"first-sub-"+uid,true,"mfu.ac.th"));return true;}catch(RuntimeException e){return false;}}),pool.submit(()->{gate.await();try{login.finalizeLogin(google(email,"second-sub-"+uid,true,"mfu.ac.th"));return true;}catch(RuntimeException e){return false;}}));gate.countDown();int success=0;for(var f:futures)if(f.get())success++;assertThat(success).isEqualTo(1);}
        assertThat(db.queryForObject("select count(*) from auth_events where user_id=? and event_type='LOGIN_SUCCEEDED'",Long.class,uid)).isEqualTo(1);
    }
    @Test void bootstrapIsIdempotentAndNeverPromotesExistingStaff(){
        new IdentityService(db,adminEmail).run(null);new IdentityService(db,adminEmail).run(null);
        assertThat(db.queryForObject("select count(*) from users where email=?",Long.class,adminEmail)).isEqualTo(1);
        UUID staff=member(unit(),"STAFF");String email=db.queryForObject("select email from users where id=?",String.class,staff);
        assertThatThrownBy(()->new IdentityService(db,email).run(null)).hasMessageContaining("non-admin");
        assertThat(db.queryForObject("select is_admin from users where id=?",Boolean.class,staff)).isFalse();
    }
    @Test void centralProvisioningAtomicUnitActivationAndIdempotency()throws Exception{
        String key=key();String unitInput=json.writeValueAsString(Map.of("code",key,"name","Waiting Unit"));String token=csrf(cookie);
        var result=http.perform(post("/api/v1/units").cookie(cookie).header("X-CSRF-TOKEN",token).header("Idempotency-Key",key).contentType("application/json").content(unitInput)).andExpect(status().isCreated()).andExpect(jsonPath("$.is_active").value(false)).andReturn();UUID unit=UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("id").asText());
        http.perform(post("/api/v1/units").cookie(cookie).header("X-CSRF-TOKEN",token).header("Idempotency-Key",key).contentType("application/json").content(unitInput)).andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(unit.toString()));
        String userInput=json.writeValueAsString(Map.of("email","provision-"+unit+"@mfu.ac.th","display_name","Unit admin","unit_id",unit,"unit_role","UNIT_ADMIN"));
        http.perform(post("/api/v1/users").cookie(cookie).header("X-CSRF-TOKEN",token).header("Idempotency-Key",key()).contentType("application/json").content(userInput)).andExpect(status().isCreated()).andExpect(jsonPath("$.identity_status").value("AWAITING_FIRST_GOOGLE_LOGIN"));assertThat(ids.unit(unit).get("is_active")).isEqualTo(true);
    }
    @Test void unitAdminCannotEscalateOrAffectOtherUnitsAndGuardsHold()throws Exception{
        UUID unit=unit(),other=unit(),unitAdmin=member(unit,"UNIT_ADMIN"),staff=member(unit,"STAFF");Cookie unitCookie=memberCookie(unitAdmin);String token=csrf(unitCookie);
        for(Map<String,Object> input:List.of(Map.<String,Object>of("email","illegal-"+key()+"@mfu.ac.th","display_name","No","unit_id",other,"unit_role","STAFF"),Map.<String,Object>of("email","illegal-"+key()+"@mfu.ac.th","display_name","No","unit_id",unit,"unit_role","UNIT_ADMIN"))){http.perform(post("/api/v1/users").cookie(unitCookie).header("X-CSRF-TOKEN",token).header("Idempotency-Key",key()).contentType("application/json").content(json.writeValueAsString(input))).andExpect(status().isForbidden());}
        http.perform(post("/api/v1/units").cookie(unitCookie).header("X-CSRF-TOKEN",token).header("Idempotency-Key",key()).contentType("application/json").content("{\"code\":\"X\",\"name\":\"X\"}")).andExpect(status().isForbidden());
        String adminToken=csrf(cookie);long v=((Number)ids.user(unitAdmin).get("version")).longValue();String deactivate=json.writeValueAsString(Map.of("expected_version",v,"is_active",false,"reason","Test guard"));
        http.perform(patch("/api/v1/users/"+unitAdmin).cookie(cookie).header("X-CSRF-TOKEN",adminToken).header("Idempotency-Key",key()).contentType("application/json").content(deactivate)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LAST_UNIT_ADMIN"));
        db.update("insert into outstanding_work(user_id,unit_id,kind,resource_id) values (?,?,?,?)",staff,unit,"RESERVATION",UUID.randomUUID());
        http.perform(patch("/api/v1/users/"+staff).cookie(cookie).header("X-CSRF-TOKEN",adminToken).header("Idempotency-Key",key()).contentType("application/json").content("{\"expected_version\":0,\"is_active\":false,\"reason\":\"Test\"}")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OUTSTANDING_WORK"));
    }
    @Test void sessionRevocationCsrfAndLogout()throws Exception{
        http.perform(post("/api/v1/units").cookie(cookie).header("Idempotency-Key",key()).contentType("application/json").content("{\"code\":\"X\",\"name\":\"X\"}")).andExpect(status().isForbidden());
        String token=csrf(cookie);http.perform(post("/api/v1/auth/logout").cookie(cookie).header("X-CSRF-TOKEN",token)).andExpect(status().isNoContent());http.perform(get("/api/v1/auth/me").cookie(cookie)).andExpect(status().isUnauthorized());
        Cookie newer=cookie(login.finalizeLogin(google(adminEmail,admin.toString(),true,"mfu.ac.th")));ids.revoke(admin);http.perform(get("/api/v1/auth/me").cookie(newer)).andExpect(status().isUnauthorized());
    }
    @Test void unitAdminCreatesAndEditsOwnStaffAndRejectsMassAssignment()throws Exception{
        UUID unit=unit(),unitAdmin=member(unit,"UNIT_ADMIN");Cookie ua=memberCookie(unitAdmin);String token=csrf(ua);
        String input=json.writeValueAsString(Map.of("email","staff-"+key()+"@mfu.ac.th","display_name","New Staff","unit_id",unit,"unit_role","STAFF"));
        var created=http.perform(post("/api/v1/users").cookie(ua).header("X-CSRF-TOKEN",token).header("Idempotency-Key",key()).contentType("application/json").content(input)).andExpect(status().isCreated()).andReturn();
        UUID staff=UUID.fromString(json.readTree(created.getResponse().getContentAsString()).get("id").asText());
        http.perform(get("/api/v1/users").param("unit_id",unit.toString()).cookie(ua)).andExpect(status().isOk());
        http.perform(patch("/api/v1/users/"+staff).cookie(ua).header("X-CSRF-TOKEN",token).header("Idempotency-Key",key()).contentType("application/json").content("{\"expected_version\":0,\"display_name\":\"Edited\",\"reason\":\"Profile correction\",\"unit_id\":\""+unit+"\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.display_name").value("Edited"));
        http.perform(post("/api/v1/users").cookie(ua).header("X-CSRF-TOKEN",token).header("Idempotency-Key",key()).contentType("application/json").content(input.substring(0,input.length()-1)+",\"is_admin\":true}")).andExpect(status().isBadRequest());
        assertThat(db.queryForObject("select is_admin from users where id=?",Boolean.class,staff)).isFalse();
    }
    @Test void unitClosureAndRoleChangeInvalidateExistingSessions()throws Exception{
        UUID unit=unit(),unitAdmin=member(unit,"UNIT_ADMIN"),staff=member(unit,"STAFF");Cookie staffSession=memberCookie(staff);String token=csrf(cookie);
        http.perform(patch("/api/v1/users/"+staff).cookie(cookie).header("X-CSRF-TOKEN",token).header("Idempotency-Key",key()).contentType("application/json").content(json.writeValueAsString(Map.of("expected_version",1,"unit_id",unit,"unit_role","UNIT_ADMIN","reason","Appoint backup administrator")))).andExpect(status().isOk());
        http.perform(get("/api/v1/auth/me").cookie(staffSession)).andExpect(status().isUnauthorized());
        Cookie active=memberCookie(staff);
        http.perform(patch("/api/v1/units/"+unit).cookie(cookie).header("X-CSRF-TOKEN",token).header("Idempotency-Key",key()).contentType("application/json").content(json.writeValueAsString(Map.of("expected_version",1,"is_active",false,"reason","Close test unit")))).andExpect(status().isOk());
        http.perform(get("/api/v1/auth/me").cookie(active)).andExpect(status().isUnauthorized());
        assertThatThrownBy(()->memberCookie(unitAdmin)).hasMessageContaining("No active access");
    }
    @Test void mailFailureRetriesOriginalJobAndKeepsSessionUsable()throws Exception{
        // Select this test's job deterministically, keeping other tests' pending jobs outside the due queue.
        db.update("update notification_outbox set next_retry_at=now()+interval '1 day' where status in ('PENDING','RETRY_WAIT') and recipient<>?",adminEmail);
        UUID job=db.queryForObject("select id from notification_outbox where recipient=?",UUID.class,adminEmail);
        doThrow(new MailSendException("sensitive-provider-secret")).when(mail).send(any(MimeMessage.class));assertThat(worker.deliverOne()).isTrue();assertThat(db.queryForObject("select status from notification_outbox where id=?",String.class,job)).isEqualTo("RETRY_WAIT");http.perform(get("/api/v1/auth/me").cookie(cookie)).andExpect(status().isOk());
        db.update("update notification_outbox set next_retry_at=now()-interval '1 second' where id=?",job);doNothing().when(mail).send(any(MimeMessage.class));assertThat(worker.deliverOne()).isTrue();
        var captured=ArgumentCaptor.forClass(MimeMessage.class);verify(mail,times(2)).send(captured.capture());assertThat(captured.getAllValues()).allSatisfy(message->{assertThat(message.getMessageID()).isEqualTo(NotificationWorker.messageId(job));assertThat(message.getContent().toString()).contains("Asia/Bangkok").doesNotContain("test-verified-provider-token",admin.toString());});
        assertThat(db.queryForObject("select status from notification_outbox where id=?",String.class,job)).isEqualTo("ACCEPTED_BY_PROVIDER");assertThat(db.queryForObject("select count(*) from notification_delivery_attempts where notification_id=?",Long.class,job)).isEqualTo(2);
        assertThat(NotificationWorker.body(Instant.parse("2026-11-06T00:00:00Z"),"IT")).contains("07:00:00");
    }
    @Test void centralRetriesSameNotificationWithAuditAndWorkerRecoversExpiredLease()throws Exception{
        UUID job=db.queryForObject("select id from notification_outbox where recipient=?",UUID.class,adminEmail);
        db.update("update notification_outbox set status='FAILED',last_error_code='SMTP_SEND_FAILED' where id=?",job);
        String token=csrf(cookie),key=key(),payload="{\"expected_version\":0,\"reason\":\"Relay restored\"}";
        for(int i=0;i<2;i++)http.perform(post("/api/v1/admin/login-notifications/"+job+"/retry").cookie(cookie).header("X-CSRF-TOKEN",token).header("Idempotency-Key",key).contentType("application/json").content(payload)).andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value(job.toString()));
        assertThat(db.queryForObject("select count(*) from audit_events where action='NOTIFICATION_RETRY' and resource_id=?",Long.class,job)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from notification_outbox where recipient=?",Long.class,adminEmail)).isEqualTo(1);
        db.update("update notification_outbox set next_retry_at=now()+interval '1 day' where status in ('PENDING','RETRY_WAIT') and id<>?",job);
        db.update("update notification_outbox set status='PROCESSING',attempt_count=1,lease_token=?,lease_until=now()-interval '1 second' where id=?",UUID.randomUUID(),job);
        db.update("insert into notification_delivery_attempts(notification_id,attempt,status) values (?,1,'PROCESSING')",job);
        assertThat(worker.deliverOne()).isTrue();assertThat(db.queryForObject("select status from notification_outbox where id=?",String.class,job)).isEqualTo("ACCEPTED_BY_PROVIDER");assertThat(db.queryForObject("select status from notification_delivery_attempts where notification_id=? and attempt=1",String.class,job)).isEqualTo("LEASE_EXPIRED");
        Cookie staff=memberCookie(member(unit(),"STAFF"));http.perform(get("/api/v1/admin/login-notifications").cookie(staff)).andExpect(status().isForbidden());
    }
}
