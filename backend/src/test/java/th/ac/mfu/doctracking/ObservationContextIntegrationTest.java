package th.ac.mfu.doctracking;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import th.ac.mfu.doctracking.common.*;
import th.ac.mfu.doctracking.domain.*;
import th.ac.mfu.doctracking.identity.Actor;
import th.ac.mfu.doctracking.workflow.WorkflowOutboxWorker;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class ObservationContextIntegrationTest extends DatabaseTest {
    @Autowired JdbcTemplate db;
    @Autowired RegistryService registry;
    @Autowired WorkflowQueryService queries;
    @Autowired ReservationService reservations;
    @Autowired CommandService commands;
    @Autowired WorkflowOutboxWorker worker;
    record Fixture(Actor courier,UUID document,UUID a,UUID b,UUID offroute,UUID unit) {}
    String key(){return UUID.randomUUID().toString();}
    @AfterEach void restoreDefault(){db.update("update workflow_policy set default_tracking_mode='MODEL_B' where id=true");}
    Fixture fixture(String mode) {
        UUID unit=UUID.randomUUID(),a=UUID.randomUUID(),b=UUID.randomUUID(),offroute=UUID.randomUUID(),origin=UUID.randomUUID(),courier=UUID.randomUUID();
        db.update("insert into units(id,code,name,is_active) values(?,?,?,true)",unit,key(),"Context unit");
        for(UUID station:List.of(a,b,offroute))db.update("insert into stations(id,unit_id,code,name) values(?,?,?,?)",station,unit,key(),"Context station");
        for(UUID user:List.of(origin,courier))db.update("insert into users(id,email,display_name) values(?,?,?)",user,key()+"@mfu.ac.th","Context user");
        db.update("insert into unit_memberships(user_id,unit_id,role) values(?,?,'STAFF')",origin,unit);
        db.update("insert into user_capabilities(user_id,capability) values(?,'COURIER')",courier);
        for(UUID station:List.of(a,b,offroute))db.update("insert into user_station_permissions values(?,?)",courier,station);
        Actor owner=new Actor(origin,"origin@mfu.ac.th","Origin",false,List.of(new Actor.Membership(unit,"STAFF")),Set.of(),Set.of(),0);
        Actor actor=new Actor(courier,"courier@mfu.ac.th","Courier",false,List.of(),Set.of(a,b,offroute),Set.of("COURIER"),0);
        var template=new RegistryService.TemplateInput("A B A",unit,List.of(a,b,a),null);
        var created=commands.execute(owner,"context:template",key(),template,()->registry.createTemplate(owner,template));
        UUID templateId=UUID.fromString(((Map<?,?>)created.body()).get("id").toString());
        db.update("update workflow_policy set default_tracking_mode=? where id=true",mode);
        var input=new RegistryService.Registration("Private title","Private number",a,templateId,1);
        var registered=commands.execute(owner,"context:register",key(),input,()->registry.register(owner,input));
        UUID document=UUID.fromString(((List<?>)((Map<?,?>)registered.body()).get("document_ids")).getFirst().toString());
        return new Fixture(actor,document,a,b,offroute,unit);
    }
    @Test void knownPhysicalFindingHasOnlyObservedStationOccurrencesAndNoRegistryFields() {
        Fixture f=fixture("MODEL_A");
        var pending=queries.observationContext(f.courier,f.document,f.a);
        assertThat(pending).containsEntry("can_observe",false).containsEntry("process_sync_status","PENDING").containsEntry("latest_operation_id",null);
        worker.processDocument(f.document);
        var context=queries.observationContext(f.courier,f.document,f.a);
        assertThat(context).containsEntry("document_id",f.document).containsEntry("document_version",0L).containsEntry("can_observe",true);
        assertThat(context).doesNotContainKeys("title","document_number","origin_unit_id","route_snapshot","actor_id");
        @SuppressWarnings("unchecked") var steps=(List<Map<String,Object>>)context.get("route_steps");
        assertThat(steps).hasSize(2).allSatisfy(r->assertThat(r).containsEntry("station_id",f.a));
        assertThat(steps.getFirst().get("step_id")).isNotEqualTo(steps.getLast().get("step_id"));
        var outside=queries.observationContext(f.courier,f.document,f.offroute);
        assertThat((List<?>)outside.get("route_steps")).isEmpty();assertThat(outside).containsEntry("can_observe",true);
        UUID first=(UUID)context.get("current_route_step_id");
        var reserve=new ReservationService.Reserve(f.a,List.of(new ReservationService.Item(f.document,first,0L)));
        commands.execute(f.courier,"context:reserve",key(),reserve,()->reservations.reserve(f.courier,reserve));
        assertThat(queries.observationContext(f.courier,f.document,f.a)).containsEntry("can_observe",false).containsEntry("document_version",1L).containsEntry("latest_operation_id",db.queryForObject("select latest_operation_id from documents where id=?",UUID.class,f.document));
    }
    @Test void currentCapabilityStationUnitAndSessionAreRequiredAndModelBStaysHidden() {
        Fixture f=fixture("MODEL_A");
        db.update("delete from user_station_permissions where user_id=? and station_id=?",f.courier.id(),f.a);
        assertDenied(f,f.a);
        db.update("update users set session_version=1 where id=?",f.courier.id());assertDenied(f,f.b);
        db.update("update users set session_version=0 where id=?",f.courier.id());
        db.update("update units set is_active=false where id=?",f.unit);assertDenied(f,f.b);
        db.update("update units set is_active=true where id=?",f.unit);
        db.update("delete from user_capabilities where user_id=?",f.courier.id());assertDenied(f,f.b);
        Fixture b=fixture("MODEL_B");
        assertThatThrownBy(()->queries.observationContext(b.courier,b.document,b.a)).isInstanceOf(ApiException.class).satisfies(e->assertThat(((ApiException)e).status()).isEqualTo(404));
    }
    void assertDenied(Fixture f,UUID station){assertThatThrownBy(()->queries.observationContext(f.courier,f.document,station)).isInstanceOf(ApiException.class).satisfies(e->assertThat(((ApiException)e).status()).isEqualTo(403));}
}
