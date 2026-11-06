package th.ac.mfu.doctracking;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Local signed provider exists only in test source; exercises the real OAuth/OIDC filters. */
@SpringBootTest(properties = {"app.google-client-id=p1-test-client", "app.google-client-secret=test-only-client-secret"})
@AutoConfigureMockMvc
@Import(OAuthHttpIntegrationTest.ProviderConfiguration.class)
class OAuthHttpIntegrationTest extends DatabaseTest {
    private static final RSAKey KEY = key();
    private static final RSAKey WRONG_KEY = key();
    private static final Map<String, Grant> GRANTS = new ConcurrentHashMap<>();
    private static final AtomicBoolean PKCE_VERIFIED = new AtomicBoolean();
    private static final HttpServer PROVIDER = provider();
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;

    record Grant(String email, String sub, String nonce, String challenge, String fault) { }
    record Attempt(String state, Cookie correlation, String code) { }

    @TestConfiguration
    static class ProviderConfiguration {
        @Bean @Primary
        ClientRegistrationRepository testProvider() {
            String base = "http://127.0.0.1:" + PROVIDER.getAddress().getPort();
            return new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("google")
                    .clientId("p1-test-client").clientSecret("test-only-client-secret")
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .redirectUri("http://localhost/api/v1/auth/google/callback").scope("openid", "email")
                    .authorizationUri(base + "/authorize").tokenUri(base + "/token")
                    .jwkSetUri(base + "/jwks").issuerUri("https://accounts.google.com")
                    .userInfoUri(base + "/userinfo").userNameAttributeName("sub").clientName("Test Google").build());
        }
    }

    @AfterAll static void stopProvider() { PROVIDER.stop(0); }

    @Test
    void signedCodeFlowPersistsUsableSessionAndRejectsReplay() throws Exception {
        String email = allowlisted();
        Attempt attempt = start(email, "sub-" + UUID.randomUUID(), "");
        MvcResult logged = callback(attempt).andExpect(status().is3xxRedirection()).andReturn();
        Cookie session = session(logged);
        mvc.perform(get("/api/v1/auth/me").cookie(session)).andExpect(status().isOk());
        assertTrue(PKCE_VERIFIED.get(), "Token endpoint must validate the original PKCE challenge");
        int before = notifications(email);
        callback(attempt).andExpect(status().isForbidden());
        assertEquals(before, notifications(email));
        mvc.perform(get("/api/v1/auth/me").cookie(session)).andExpect(status().isOk());
        assertEquals(before, notifications(email), "Page refresh must not enqueue another email");
    }

    @Test
    void loggingInAgainReplacesExistingSessionWithoutCookieLoss() throws Exception {
        String email = allowlisted(); String sub = "sub-" + UUID.randomUUID();
        Cookie previous = session(callback(start(email, sub, "")).andExpect(status().is3xxRedirection()).andReturn());
        Attempt next = start(email, sub, "");
        MvcResult result = mvc.perform(get("/api/v1/auth/google/callback").param("state", next.state())
                .param("code", next.code()).cookie(next.correlation(), previous))
                .andExpect(status().is3xxRedirection()).andReturn();
        Cookie fresh = session(result);
        assertNotEquals(previous.getValue(), fresh.getValue());
        mvc.perform(get("/api/v1/auth/me").cookie(fresh)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").cookie(previous)).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsSignedInvalidClaimsAndUnassignedAccountWithoutIssuingSessionOrEmail() throws Exception {
        for (String fault : List.of("signature", "issuer", "audience", "expired", "nonce", "hd", "unverified", "pkce")) {
            String email = allowlisted(); Attempt attempt = start(email, "sub-" + UUID.randomUUID(), fault);
            callback(attempt).andExpect(status().isForbidden());
            assertEquals(0, notifications(email), fault);
        }
        String unknown = "unassigned-" + UUID.randomUUID() + "@mfu.ac.th";
        callback(start(unknown, "sub-unknown", "")).andExpect(status().isForbidden());
        assertEquals(0, notifications(unknown));
        assertEquals(0, db.queryForObject("select count(*) from users where email=?", Integer.class, unknown));
        String expiredEmail=allowlisted();Attempt expired=start(expiredEmail,"expired-state-sub","");
        db.update("update oauth_login_requests set expires_at=now()-interval '1 second' where state=?",expired.state());
        callback(expired).andExpect(status().isForbidden());
        assertEquals(0,notifications(expiredEmail));
    }

    @Test
    void rejectsWrongStateAndChangedGoogleIdentity() throws Exception {
        String email = allowlisted(); Attempt attempt = start(email, "sub-" + UUID.randomUUID(), "");
        mvc.perform(get("/api/v1/auth/google/callback").param("state", "tampered")
                .param("code", attempt.code()).cookie(attempt.correlation())).andExpect(status().isForbidden());
        assertEquals(0, notifications(email));
        callback(attempt).andExpect(status().is3xxRedirection());
        int before = notifications(email);
        callback(start(email, "different-sub-" + UUID.randomUUID(), "")).andExpect(status().isForbidden());
        assertEquals(before, notifications(email));
    }

    private Attempt start(String email, String sub, String fault) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/auth/google/start")).andExpect(status().is3xxRedirection()).andReturn();
        Map<String, String> query = params(URI.create(result.getResponse().getRedirectedUrl()).getRawQuery());
        assertEquals("S256", query.get("code_challenge_method"));
        assertNotNull(query.get("nonce"));
        String code = UUID.randomUUID().toString();
        GRANTS.put(code, new Grant(email, sub, query.get("nonce"), fault.equals("pkce")?"different-challenge":query.get("code_challenge"), fault));
        Cookie cookie = Arrays.stream(result.getResponse().getCookies()).filter(c -> c.getName().equals("OAUTH_STATE")).findFirst().orElseThrow();
        return new Attempt(query.get("state"), cookie, code);
    }

    private org.springframework.test.web.servlet.ResultActions callback(Attempt attempt) throws Exception {
        return mvc.perform(get("/api/v1/auth/google/callback").param("state", attempt.state())
                .param("code", attempt.code()).cookie(attempt.correlation()));
    }

    private Cookie session(MvcResult result) {
        return Arrays.stream(result.getResponse().getCookies()).filter(c -> c.getName().equals("SESSION") && !c.getValue().isBlank() && c.getMaxAge() != 0)
                .reduce((first, last) -> last).orElseThrow(() -> new AssertionError("No usable session cookie"));
    }

    private String allowlisted() {
        String email = "oauth-" + UUID.randomUUID() + "@mfu.ac.th";
        db.update("insert into users(id,email,display_name,is_admin) values (?,?,'OAuth test',true)", UUID.randomUUID(), email);
        return email;
    }

    private int notifications(String email) { return db.queryForObject("select count(*) from notification_outbox where recipient=?", Integer.class, email); }

    private static RSAKey key() {
        try { return new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate(); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    private static HttpServer provider() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/jwks", exchange -> respond(exchange, 200, new JWKSet(KEY.toPublicJWK()).toString()));
            server.createContext("/token", exchange -> {
                try {
                    var form = params(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    String code = form.get("code"); Grant grant = GRANTS.get(code);
                    String verifier = form.get("code_verifier");
                    String challenge = verifier == null ? "" : Base64.getUrlEncoder().withoutPadding().encodeToString(
                            MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
                    if (grant == null || !challenge.equals(grant.challenge())) { respond(exchange, 400, "{\"error\":\"invalid_grant\"}"); return; }
                    PKCE_VERIFIED.set(true);
                    Instant now = Instant.now();
                    var claims = new JWTClaimsSet.Builder().issuer(grant.fault().equals("issuer") ? "https://wrong.invalid" : "https://accounts.google.com")
                            .audience(grant.fault().equals("audience") ? "wrong-client" : "p1-test-client").subject(grant.sub())
                            .issueTime(Date.from(now.minusSeconds(120))).expirationTime(Date.from(now.plusSeconds(grant.fault().equals("expired") ? -90 : 300)))
                            .claim("email", grant.email()).claim("email_verified", !grant.fault().equals("unverified"))
                            .claim("hd", grant.fault().equals("hd") ? "other.invalid" : "mfu.ac.th")
                            .claim("nonce", grant.fault().equals("nonce") ? "wrong" : grant.nonce()).build();
                    var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(KEY.getKeyID()).build(), claims);
                    jwt.sign(new RSASSASigner(grant.fault().equals("signature") ? WRONG_KEY : KEY));
                    respond(exchange, 200, "{\"access_token\":\"" + code + "\",\"token_type\":\"Bearer\",\"expires_in\":300,\"scope\":\"openid email\",\"id_token\":\"" + jwt.serialize() + "\"}");
                } catch (Exception error) { respond(exchange, 500, "{\"error\":\"test_provider_error\"}"); }
            });
            server.createContext("/userinfo", exchange -> {
                String token = exchange.getRequestHeaders().getFirst("Authorization");
                Grant grant = token == null ? null : GRANTS.get(token.replace("Bearer ", ""));
                if (grant == null) respond(exchange, 401, "{}");
                else respond(exchange, 200, "{\"sub\":\"" + grant.sub() + "\",\"email\":\"" + grant.email() + "\",\"email_verified\":true}");
            });
            server.start(); return server;
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    private static Map<String, String> params(String encoded) {
        Map<String, String> result = new HashMap<>();
        if (encoded != null) for (String item : encoded.split("&")) {
            String[] pair = item.split("=", 2);
            result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
        }
        return result;
    }

    private static void respond(HttpExchange exchange, int code, String json) throws java.io.IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
