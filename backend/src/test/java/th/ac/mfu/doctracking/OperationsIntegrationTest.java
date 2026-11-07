package th.ac.mfu.doctracking;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.Cookie;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import th.ac.mfu.doctracking.common.CommandService;
import th.ac.mfu.doctracking.domain.RegistryService;
import th.ac.mfu.doctracking.identity.*;
import th.ac.mfu.doctracking.workflow.WorkflowOutboxWorker;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ExtendWith(OutputCaptureExtension.class)
class OperationsIntegrationTest extends DatabaseTest {
    @Autowired JdbcTemplate db;@Autowired MockMvc http;@Autowired ObjectMapper json;@Autowired LoginService login;
    @Autowired RegistryService registry;@Autowired CommandService commands;@Autowired WorkflowOutboxWorker worker;@Autowired MeterRegistry metrics;
    Cookie admin,staff;Actor origin;UUID document;
    String key() {return UUID.randomUUID().toString();}
    @BeforeEach void fixture() {
        UUID unit=UUID.randomUUID(),a=UUID.randomUUID(),b=UUID.randomUUID(),uid=UUID.randomUUID(),aid=UUID.randomUUID();
        db.update("insert into units(id,code,name,is_active) values(?,?,'Operations Unit',true)",unit,key());
        for(UUID station:List.of(a,b))db.update("insert into stations(id,unit_id,code,name) values(?,?,?,'Operations station')",station,unit,key());
        db.update("insert into users(id,email,display_name,is_admin) values(?,?,?,true)",aid,aid+"@mfu.ac.th","Secret Administrator");
        db.update("insert into users(id,email,display_name) values(?,?,?)",uid,uid+"@mfu.ac.th","Secret Staff");
        db.update("insert into unit_memberships values(?,?,'STAFF')",uid,unit);
        origin=new Actor(uid,uid+"@mfu.ac.th","Secret Staff",false,List.of(new Actor.Membership(unit,"STAFF")),Set.of(),Set.of(),0);
        admin=IdentityIntegrationTest.cookie(login.finalizeLogin(IdentityIntegrationTest.google(aid+"@mfu.ac.th",aid.toString(),true,"mfu.ac.th")));
        staff=IdentityIntegrationTest.cookie(login.finalizeLogin(IdentityIntegrationTest.google(uid+"@mfu.ac.th",uid.toString(),true,"mfu.ac.th")));
        var template=new RegistryService.TemplateInput("Private route name",unit,List.of(a,b,a),null);
        var result=commands.execute(origin,"ops:template",key(),template,()->registry.createTemplate(origin,template));UUID tid=UUID.fromString(((Map<?,?>)result.body()).get("id").toString());
        var registration=new RegistryService.Registration("secret-document-title","secret-document-number",a,tid,1);
        var registered=commands.execute(origin,"ops:register",key(),registration,()->registry.register(origin,registration));
        document=UUID.fromString(((List<?>)((Map<?,?>)registered.body()).get("document_ids")).getFirst().toString());worker.processDocument(document);
    }
    String csrf(Cookie cookie)throws Exception{return json.readTree(http.perform(get("/api/v1/auth/csrf").cookie(cookie)).andReturn().getResponse().getContentAsString()).get("token").asText();}
    @Test void dashboardAndMetricsRequireCentralAdminAndPublicProbesHideDetails()throws Exception {
        for(String path:List.of("summary","workflow-deliveries","login-notifications","exceptions","audit-events","reminders","tasks","documents/"+document+"/engine")) {
            http.perform(get("/api/v1/admin/operations/"+path)).andExpect(status().isUnauthorized());
            http.perform(get("/api/v1/admin/operations/"+path).cookie(staff)).andExpect(status().isForbidden());
            http.perform(get("/api/v1/admin/operations/"+path).cookie(admin)).andExpect(status().isOk());
        }
        http.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        http.perform(get("/actuator/metrics").cookie(staff)).andExpect(status().isForbidden());
        http.perform(get("/actuator/metrics").cookie(admin)).andExpect(status().isOk());
        http.perform(get("/actuator/health/readiness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP")).andExpect(jsonPath("$.components").doesNotExist());
        http.perform(get("/actuator/health/liveness")).andExpect(status().isOk()).andExpect(jsonPath("$.components").doesNotExist());
    }
    @Test void redactedReadModelsAndCursorPagingNeverExposeReasonsOrMailRecipients()throws Exception {
        db.update("insert into audit_events(actor_id,action,resource_id,reason) values(?,'TECHNICAL_TEST',?,'private-audit-json-email@example.invalid')",origin.id(),document);
        db.update("insert into audit_events(actor_id,action,resource_id,reason) values(?,'TECHNICAL_TEST',?,'private-second-audit-reason')",origin.id(),document);
        db.update("update notification_outbox set status='FAILED',last_error_code='secret-provider-token' where recipient=?",origin.email());
        var summary=json.readTree(http.perform(get("/api/v1/admin/operations/summary").cookie(admin)).andReturn().getResponse().getContentAsString());
        assertThat(summary.get("alerts").toString()).contains("MAIL_DELIVERY_FAILED","count").doesNotContain("secret-provider-token",origin.email());
        for(String path:List.of("summary","workflow-deliveries","login-notifications","exceptions","audit-events","reminders","tasks","documents/"+document+"/engine")) {
            String body=http.perform(get("/api/v1/admin/operations/"+path).cookie(admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("@mfu.ac.th","secret-document-title","secret-document-number","private-audit-json","secret-provider-token","Secret Staff","recipient","reason\"");
        }
        var first=json.readTree(http.perform(get("/api/v1/admin/operations/audit-events").cookie(admin).param("limit","1")).andReturn().getResponse().getContentAsString());
        assertThat(first.get("items").size()).isEqualTo(1);assertThat(first.get("next_cursor").isNull()).isFalse();
        var second=json.readTree(http.perform(get("/api/v1/admin/operations/audit-events").cookie(admin).param("limit","1").param("cursor",first.get("next_cursor").asText())).andReturn().getResponse().getContentAsString());
        assertThat(second.get("items").get(0).get("id").asText()).isNotEqualTo(first.get("items").get(0).get("id").asText());
        http.perform(get("/api/v1/admin/operations/tasks").cookie(admin).param("limit","101")).andExpect(status().isBadRequest());
        http.perform(get("/api/v1/admin/operations/workflow-deliveries").cookie(admin).param("status","BAD")).andExpect(status().isUnprocessableEntity());
    }
    @Test void exactEngineBindingAndMetricsRemainReadOnly()throws Exception {
        long events=db.queryForObject("select count(*) from document_events where document_id=?",Long.class,document);
        String body=http.perform(get("/api/v1/admin/operations/documents/"+document+"/engine").cookie(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.mapping.process_definition_version").isNumber()).andExpect(jsonPath("$.tasks[0].binding.documentId").value(document.toString())).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).get("tasks").size()).isEqualTo(1);
        assertThat(metrics.get("doctracking.workflow.tasks.waiting").gauge().value()).isGreaterThan(0);
        assertThat(metrics.get("doctracking.workflow.outbox.dead.letter").gauge().value()).isGreaterThanOrEqualTo(0);
        assertThat(db.queryForObject("select count(*) from document_events where document_id=?",Long.class,document)).isEqualTo(events);
    }
    @Test void correlationIsValidatedEchoedAndSafeLogsOmitQueryAndUnmatchedPath(CapturedOutput output)throws Exception {
        String id=key();http.perform(get("/api/v1/admin/operations/tasks").cookie(admin).param("limit","0").header("X-Correlation-ID",id)).andExpect(status().isBadRequest()).andExpect(header().string("X-Correlation-ID",id)).andExpect(jsonPath("$.correlation_id").value(id));
        var response=http.perform(get("/api/v1/admin/operations/summary").header("X-Correlation-ID","person@example.invalid")).andExpect(status().isUnauthorized()).andReturn().getResponse();
        assertThat(UUID.fromString(response.getHeader("X-Correlation-ID"))).isNotNull();
        assertThat(json.readTree(response.getContentAsString()).get("correlation_id").asText()).isEqualTo(response.getHeader("X-Correlation-ID"));
        http.perform(get("/secret-path-token").cookie(admin).queryParam("code","secret-query-token")).andExpect(status().isNotFound());
        assertThat(output.getOut()).contains("http_request").doesNotContain("person@example.invalid","secret-path-token","secret-query-token");
    }
    @Test void orphanCleanupRequiresCsrfAdminAndApprovedPolicy()throws Exception {
        String path="/api/v1/admin/operations/orphan-uploads/cleanup",body="{\"dry_run\":true,\"limit\":10,\"reason\":\"Preview abandoned upload storage\"}";
        http.perform(post(path).cookie(admin).header("Idempotency-Key",key()).contentType("application/json").content(body)).andExpect(status().isForbidden());
        http.perform(post(path).cookie(staff).header("X-CSRF-TOKEN",csrf(staff)).header("Idempotency-Key",key()).contentType("application/json").content(body)).andExpect(status().isForbidden());
        http.perform(post(path).cookie(admin).header("X-CSRF-TOKEN",csrf(admin)).header("Idempotency-Key",key()).contentType("application/json").content(body)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORPHAN_POLICY_REQUIRED"));
    }
}
