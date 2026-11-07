package th.ac.mfu.doctracking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.core.*;
import th.ac.mfu.doctracking.common.CommandService;
import th.ac.mfu.doctracking.domain.RegistryService;
import th.ac.mfu.doctracking.identity.Actor;
import th.ac.mfu.doctracking.workflow.WorkflowOutboxWorker;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real browser/HTTP/PostgreSQL/Flowable test. Provider and fixture identities never enter the application JAR. */
@EnabledIfEnvironmentVariable(named="P3_BROWSER_ENABLED", matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "app.google-client-id=p3-browser-client", "app.google-client-secret=test-only-secret",
        "app.base-url=http://127.0.0.1:62916"})
@Import(P3BrowserIntegrationTest.ProviderConfiguration.class)
class P3BrowserIntegrationTest extends DatabaseTest {
    static final String GATEWAY="http://127.0.0.1:62916";
    static final RSAKey KEY=key();
    static final Map<String,Grant> GRANTS=new ConcurrentHashMap<>();
    static final List<String> EMAILS=new CopyOnWriteArrayList<>();
    static final AtomicInteger PKCE_VERIFIED=new AtomicInteger();
    static final HttpServer PROVIDER=provider();
    record Grant(String email,String nonce,String challenge) {}
    @Autowired JdbcTemplate db;
    @Autowired RegistryService registry;
    @Autowired CommandService commands;
    @Autowired WorkflowOutboxWorker worker;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;

    @TestConfiguration static class ProviderConfiguration {
        @Bean @Primary ClientRegistrationRepository browserProvider() {
            String base="http://127.0.0.1:"+PROVIDER.getAddress().getPort();
            return new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("google")
                    .clientId("p3-browser-client").clientSecret("test-only-secret")
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .redirectUri(GATEWAY+"/api/v1/auth/google/callback").scope("openid","email")
                    .authorizationUri(base+"/authorize").tokenUri(base+"/token").jwkSetUri(base+"/jwks")
                    .issuerUri("https://accounts.google.com").userInfoUri(base+"/userinfo")
                    .userNameAttributeName("sub").clientName("Test Google").build());
        }
    }
    @AfterAll static void stopProvider(){PROVIDER.stop(0);}

    @Test void modelBTraversesThreeStationsThroughRealBrowserAndOriginAloneCompletes() throws Exception {
        var stations=new ArrayList<UUID>();var units=new ArrayList<UUID>();var users=new ArrayList<UUID>();
        String run=UUID.randomUUID().toString().substring(0,8);
        for(int i=0;i<3;i++) {
            UUID unit=UUID.randomUUID(),station=UUID.randomUUID();units.add(unit);stations.add(station);
            db.update("insert into units(id,code,name,is_active) values(?,?,?,true)",unit,"P3-"+run+"-"+i,"P3 Unit "+(char)('A'+i));
            db.update("insert into stations(id,unit_id,code,name) values(?,?,?,?)",station,unit,"P3-S-"+run+"-"+i,"P3 Station "+(char)('A'+i));
        }
        String[] roles={"origin","staff-b","staff-c","courier-1","courier-2","courier-3","outsider"};
        var emails=new LinkedHashMap<String,String>();
        for(int i=0;i<roles.length;i++) {
            UUID user=UUID.randomUUID();users.add(user);String email=roles[i]+"-"+run+"@mfu.ac.th";emails.put(roles[i],email);EMAILS.add(email);
            db.update("insert into users(id,email,display_name) values(?,?,?)",user,email,"P3 "+roles[i]);
            if(i<3) db.update("insert into unit_memberships(user_id,unit_id,role) values(?,?,'STAFF')",user,units.get(i));
            if(i==6) {
                UUID unrelated=UUID.randomUUID();
                db.update("insert into units(id,code,name,is_active) values(?,?,?,true)",unrelated,"P3-OUT-"+run,"P3 unrelated unit");
                db.update("insert into unit_memberships(user_id,unit_id,role) values(?,?,'STAFF')",user,unrelated);
            }
            if(i==1||i==2) db.update("insert into user_station_permissions(user_id,station_id) values(?,?)",user,stations.get(i));
            if(i>=3&&i<=5) {
                db.update("insert into user_capabilities(user_id,capability) values(?,'COURIER')",user);
                for(UUID station:stations)db.update("insert into user_station_permissions(user_id,station_id) values(?,?)",user,station);
            }
        }
        Actor origin=new Actor(users.getFirst(),emails.get("origin"),"P3 origin",false,
                List.of(new Actor.Membership(units.getFirst(),"STAFF")),Set.of(),Set.of(),0);
        var template=new RegistryService.TemplateInput("P3 A → B → C → A",units.getFirst(),List.of(stations.get(0),stations.get(1),stations.get(2),stations.get(0)),null);
        var created=commands.execute(origin,"template:create",UUID.randomUUID().toString(),template,()->registry.createTemplate(origin,template));
        String templateId=((Map<?,?>)created.body()).get("id").toString();
        db.update("update workflow_policy set default_tracking_mode='MODEL_B' where id=true");
        Path root=Path.of("").toAbsolutePath().normalize();
        if(!Files.isDirectory(root.resolve("frontend")))root=root.getParent();
        assertTrue(Files.isRegularFile(root.resolve("frontend/.output/public/index.html")),
                "Build frontend first; the static SPA entrypoint must exist");
        Path output=root.resolve("frontend/test-results/p3");Files.createDirectories(output);
        Path fixtures=output.resolve("fixtures.json");
        json.writeValue(fixtures.toFile(),Map.of("emails",emails,"stations",stations,"template",templateId,"backendPort",port,"run",run));
        var scheduler=Executors.newSingleThreadScheduledExecutor();
        // The same production outbox worker applies committed events asynchronously; no APPLIED or ready state is seeded.
        scheduler.scheduleWithFixedDelay(()->{try{for(int i=0;i<25&&worker.processOne();i++){} }catch(Exception e){throw new IllegalStateException(e);}},0,100,TimeUnit.MILLISECONDS);
        Process process=null;
        try {
            var builder=new ProcessBuilder("node",root.resolve("frontend/tests/p3-e2e.mjs").toString(),fixtures.toString()).directory(root.toFile());
            builder.redirectErrorStream(true).redirectOutput(output.resolve("browser.log").toFile());
            process=builder.start();
            assertTrue(process.waitFor(240,TimeUnit.SECONDS),"Browser timed out; see frontend/test-results/p3/browser.log");
            assertEquals(0,process.exitValue(),()->{try{return Files.readString(output.resolve("browser.log"));}catch(Exception e){return e.toString();}});
            Map<?,?> result=json.readValue(output.resolve("report.json").toFile(),Map.class);
            UUID document=UUID.fromString(result.get("document_id").toString());
            assertEquals("COMPLETED",db.queryForObject("select business_state from documents where id=?",String.class,document));
            assertEquals("APPLIED",db.queryForObject("select process_sync_status from documents where id=?",String.class,document));
            assertEquals(1,db.queryForObject("select count(*) from workflow_instances where document_id=?",Integer.class,document));
            assertEquals(3,db.queryForObject("select count(*) from courier_legs where document_id=?",Integer.class,document));
            assertEquals(3,db.queryForObject("select count(distinct courier_user_id) from courier_legs where document_id=?",Integer.class,document));
            assertEquals(6,db.queryForObject("select count(*) from event_evidence x join document_events e on e.id=x.event_id where e.document_id=?",Integer.class,document));
            assertEquals(3,db.queryForObject("select count(*) from document_events where document_id=? and event_type='UNIT_READY'",Integer.class,document));
            assertEquals(2,db.queryForObject("select count(*) from document_events where document_id=? and event_type='UNIT_RECEIPT_CONFIRMED'",Integer.class,document));
            assertEquals(users.getFirst(),db.queryForObject("select actor_id from document_events where document_id=? and event_type='ORIGIN_RETURN_CONFIRMED'",UUID.class,document));
            assertTrue(PKCE_VERIFIED.get()>=7,"Each real browser actor must use the PKCE token exchange");
            assertEquals(0,db.queryForObject("select count(*) from workflow_outbox where document_id=? and status<>'APPLIED'",Integer.class,document));
            for(UUID user:users) {
                assertEquals(1,db.queryForObject("select count(*) from auth_events where user_id=? and event_type='LOGIN_SUCCEEDED'",Integer.class,user));
                assertEquals(1,db.queryForObject("select count(*) from notification_outbox n join auth_events e on e.id=n.login_event_id where e.user_id=?",Integer.class,user));
            }
        } finally {scheduler.shutdownNow();if(process!=null&&process.isAlive())process.destroyForcibly();}
    }

    static RSAKey key(){try{return new RSAKeyGenerator(2048).keyID("p3-test-key").generate();}catch(Exception e){throw new IllegalStateException(e);}}
    static Map<String,String> params(String encoded){var result=new LinkedHashMap<String,String>();if(encoded!=null)for(String item:encoded.split("&")){String[]pair=item.split("=",2);result.put(URLDecoder.decode(pair[0],StandardCharsets.UTF_8),pair.length==2?URLDecoder.decode(pair[1],StandardCharsets.UTF_8):"");}return result;}
    static String encode(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8);}
    static void respond(HttpExchange exchange,int status,String type,String body)throws java.io.IOException{byte[]bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type",type+"; charset=utf-8");exchange.sendResponseHeaders(status,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}}
    static HttpServer provider(){try{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/jwks",e->respond(e,200,"application/json",new JWKSet(KEY.toPublicJWK()).toString()));
        server.createContext("/authorize",e->{
            String query=e.getRequestURI().getRawQuery();StringBuilder html=new StringBuilder("<!doctype html><html><meta charset=utf-8><title>Test-only Google provider</title><h1>Test-only Google provider</h1>");
            for(String email:EMAILS)html.append("<p><a role=button href='/approve?").append(query.replace("&","&amp;")).append("&amp;actor=").append(encode(email)).append("'>").append(email).append("</a></p>");
            respond(e,200,"text/html",html.append("</html>").toString());
        });
        server.createContext("/approve",e->{
            var p=params(e.getRequestURI().getRawQuery());if(!EMAILS.contains(p.get("actor"))){respond(e,403,"application/json","{}");return;}
            String code=UUID.randomUUID().toString();GRANTS.put(code,new Grant(p.get("actor"),p.get("nonce"),p.get("code_challenge")));
            e.getResponseHeaders().set("Location",p.get("redirect_uri")+"?code="+code+"&state="+encode(p.get("state")));e.sendResponseHeaders(302,-1);e.close();
        });
        server.createContext("/token",e->{try{
            var p=params(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));var grant=GRANTS.get(p.get("code"));
            String challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(p.getOrDefault("code_verifier","").getBytes(StandardCharsets.US_ASCII)));
            if(grant==null||!challenge.equals(grant.challenge())){respond(e,400,"application/json","{\"error\":\"invalid_grant\"}");return;}
            PKCE_VERIFIED.incrementAndGet();Instant now=Instant.now();
            var claims=new JWTClaimsSet.Builder().issuer("https://accounts.google.com").audience("p3-browser-client").subject("p3-"+grant.email()).issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300))).claim("email",grant.email()).claim("email_verified",true).claim("hd","mfu.ac.th").claim("nonce",grant.nonce()).build();
            var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(),claims);jwt.sign(new RSASSASigner(KEY));
            respond(e,200,"application/json","{\"access_token\":\""+p.get("code")+"\",\"token_type\":\"Bearer\",\"expires_in\":300,\"scope\":\"openid email\",\"id_token\":\""+jwt.serialize()+"\"}");
        }catch(Exception error){respond(e,500,"application/json","{\"error\":\"test_provider_error\"}");}});
        server.createContext("/userinfo",e->{String token=e.getRequestHeaders().getFirst("Authorization");var grant=token==null?null:GRANTS.get(token.replace("Bearer ",""));respond(e,grant==null?401:200,"application/json",grant==null?"{}":"{\"sub\":\"p3-"+grant.email()+"\",\"email\":\""+grant.email()+"\",\"email_verified\":true}");});
        server.start();return server;
    }catch(Exception e){throw new IllegalStateException(e);}}
}
