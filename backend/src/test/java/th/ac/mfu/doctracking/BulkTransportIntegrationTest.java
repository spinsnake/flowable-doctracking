package th.ac.mfu.doctracking;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import th.ac.mfu.doctracking.common.*;
import th.ac.mfu.doctracking.domain.*;
import th.ac.mfu.doctracking.identity.Actor;
import th.ac.mfu.doctracking.workflow.WorkflowOutboxWorker;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class BulkTransportIntegrationTest extends DatabaseTest {
    @Autowired JdbcTemplate db;
    @Autowired RegistryService registry;
    @Autowired ReservationService reservations;
    @Autowired EvidenceService evidence;
    @Autowired WorkflowCommandService workflow;
    @Autowired WorkflowOutboxWorker worker;
    @Autowired CommandService commands;
    @Autowired ObjectMapper json;
    record Fixture(Actor origin,Actor staff,Actor courier,Actor competitor,List<UUID> stations,List<UUID> documents) {}
    com.fasterxml.jackson.databind.JsonNode normalized(Object value) throws IOException {return json.readTree(json.writeValueAsBytes(value));}
    String key() { return UUID.randomUUID().toString(); }
    UUID id(CommandResult result,String field) {return UUID.fromString(((Map<?,?>)result.body()).get(field).toString());}
    @SuppressWarnings("unchecked") Map<String,Object> body(CommandResult result) {return (Map<String,Object>)result.body();}
    Actor user(UUID unit,Set<UUID> stations,boolean courier) {
        UUID id=UUID.randomUUID();String email=id+"@mfu.ac.th";
        db.update("insert into users(id,email,display_name) values(?,?,?)",id,email,"Bulk test");
        if(unit!=null) db.update("insert into unit_memberships(user_id,unit_id,role) values(?,?,'STAFF')",id,unit);
        for(UUID station:stations) db.update("insert into user_station_permissions(user_id,station_id) values(?,?)",id,station);
        if(courier) db.update("insert into user_capabilities(user_id,capability) values(?,'COURIER')",id);
        return new Actor(id,email,"Bulk test",false,unit==null?List.of():List.of(new Actor.Membership(unit,"STAFF")),stations,courier?Set.of("COURIER"):Set.of(),0);
    }
    Fixture fixture(int count) {
        List<UUID> units=new ArrayList<>(),stations=new ArrayList<>(),documents=new ArrayList<>();
        for(int i=0;i<3;i++) {
            UUID unit=UUID.randomUUID(),station=UUID.randomUUID();units.add(unit);stations.add(station);
            db.update("insert into units(id,code,name,is_active) values(?,?,?,true)",unit,key(),"Bulk unit");
            db.update("insert into stations(id,unit_id,code,name) values(?,?,?,?)",station,unit,key(),"Bulk station "+i);
        }
        Actor origin=user(units.getFirst(),Set.of(),false),staff=user(units.get(1),Set.of(stations.get(1)),false);
        Actor courier=user(null,Set.copyOf(stations),true),competitor=user(null,Set.copyOf(stations),true);
        var template=new RegistryService.TemplateInput("Bulk A B C A",units.getFirst(),List.of(stations.get(0),stations.get(1),stations.get(2),stations.get(0)),null);
        UUID templateId=id(commands.execute(origin,"template:create",key(),template,()->registry.createTemplate(origin,template)),"id");
        for(int i=0;i<count;i++) {
            var input=new RegistryService.Registration("Bulk "+i,null,stations.getFirst(),templateId,1);
            var result=commands.execute(origin,"document:register",key(),input,()->registry.register(origin,input));
            UUID document=UUID.fromString(((List<?>)body(result).get("document_ids")).getFirst().toString());documents.add(document);worker.processDocument(document);
            var ready=new WorkflowCommandService.Step(step(document,0),version(document));
            commands.execute(origin,"document:ready:"+document,key(),ready,()->workflow.ready(origin,document,ready));worker.processDocument(document);
        }
        return new Fixture(origin,staff,courier,competitor,stations,documents);
    }
    long version(UUID document) {return db.queryForObject("select version from documents where id=?",Long.class,document);}
    UUID step(UUID document,int sequence) {return db.queryForObject("select id from route_steps where document_id=? and sequence=?",UUID.class,document,sequence);}
    List<ReservationService.Item> reserveItems(List<UUID> documents) {return documents.stream().map(d->new ReservationService.Item(d,step(d,0),version(d))).toList();}
    CommandResult reserve(Actor actor,UUID station,List<ReservationService.Item> items,String key) {
        var input=new ReservationService.Reserve(station,items);return commands.execute(actor,"reservation:create",key,input,()->reservations.reserve(actor,input));
    }
    UUID photo(Actor actor,UUID station) throws IOException {
        var output=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",output);byte[] bytes=output.toByteArray();
        var input=new EvidenceService.Upload(null,station,"image/png",(long)bytes.length,EvidenceService.checksum(bytes));
        UUID photo=id(commands.execute(actor,"evidence:create",key(),input,()->evidence.create(actor,input)),"id");
        evidence.content(actor,photo,"image/png",new ByteArrayInputStream(bytes));
        commands.execute(actor,"evidence:finalize:"+photo,key(),Map.of("id",photo),()->evidence.finalizeUpload(actor,photo));return photo;
    }
    List<WorkflowCommandService.Item> items(List<UUID> documents,int sequence,boolean leg) {
        return documents.stream().map(d->new WorkflowCommandService.Item(d,step(d,sequence),version(d),leg?db.queryForObject("select active_leg_id from documents where id=?",UUID.class,d):null)).toList();
    }
    CommandResult pickup(Fixture f,UUID reservation,List<WorkflowCommandService.Item> items,UUID photo,String key) {
        var input=new WorkflowCommandService.Pickup(f.stations.getFirst(),items,List.of(photo),reservation,true);
        return commands.execute(f.courier,"courier:pickup",key,input,()->workflow.pickup(f.courier,input));
    }
    @Test void tenSubmittedWithTwoConflictsCommitEightAndReplayDoesNotReserveTwice() throws Exception {
        Fixture f=fixture(10);var input=reserveItems(f.documents);
        reserve(f.competitor,f.stations.getFirst(),input.subList(0,2),key());
        String key=key();var result=reserve(f.courier,f.stations.getFirst(),input,key);
        assertThat((List<?>)body(result).get("accepted")).hasSize(8);assertThat((List<?>)body(result).get("rejected")).hasSize(2);
        assertThat(((List<?>)body(result).get("rejected")).stream().map(r->((Map<?,?>)r).get("code").toString())).containsOnly("RESERVATION_CONFLICT");
        assertThat(normalized(body(result))).isEqualTo(normalized(body(reserve(f.courier,f.stations.getFirst(),input,key))));
        UUID reservation=id(result,"id");
        assertThat(db.queryForObject("select count(*) from reservation_items where reservation_id=? and is_active",Integer.class,reservation)).isEqualTo(8);
        assertThat(db.queryForObject("select count(*) from document_events where operation_id=?",Integer.class,id(result,"operation_id"))).isEqualTo(8);
        assertThatThrownBy(()->registry.operation(f.competitor,id(result,"operation_id"))).isInstanceOf(ApiException.class);
    }
    @Test void sharedPhotoPartialPickupDropoffAndReceiptKeepPerDocumentHistoryAndAggregateSync() throws Exception {
        Fixture f=fixture(3);UUID reservation=id(reserve(f.courier,f.stations.getFirst(),reserveItems(f.documents),key()),"id");
        var pickupItems=new ArrayList<>(items(f.documents,0,false));var bad=pickupItems.get(2);
        pickupItems.set(2,new WorkflowCommandService.Item(bad.document_id(),bad.route_step_id(),bad.expected_version()-1,null));
        UUID shared=photo(f.courier,f.stations.getFirst());String retry=key();var picked=pickup(f,reservation,pickupItems,shared,retry);
        assertThat((List<?>)body(picked).get("accepted")).hasSize(2);assertThat((List<?>)body(picked).get("rejected")).hasSize(1);
        UUID operation=id(picked,"operation_id");
        assertThat(db.queryForObject("select count(*) from event_evidence x join document_events e on e.id=x.event_id where e.operation_id=? and x.evidence_id=?",Integer.class,operation,shared)).isEqualTo(2);
        worker.processDocument(f.documents.getFirst());
        assertThat(registry.operation(f.courier,operation)).containsEntry("process_sync_status","PENDING");
        worker.processDocument(f.documents.get(1));
        assertThat(registry.operation(f.courier,operation)).containsEntry("process_sync_status","APPLIED");
        assertThat(normalized(body(picked))).isEqualTo(normalized(body(pickup(f,reservation,pickupItems,shared,retry))));
        assertThat(db.queryForObject("select status from reservations where id=?",String.class,reservation)).isEqualTo("ACTIVE");
        assertThat(db.queryForObject("select count(*) from reservation_items where reservation_id=? and is_active",Integer.class,reservation)).isEqualTo(1);
        var dropItems=new ArrayList<>(items(f.documents.subList(0,2),1,true));var wrong=dropItems.get(1);
        dropItems.set(1,new WorkflowCommandService.Item(wrong.document_id(),step(wrong.document_id(),2),wrong.expected_version(),wrong.leg_id()));
        var drop=new WorkflowCommandService.Dropoff(f.stations.get(1),dropItems,List.of(photo(f.courier,f.stations.get(1))));
        var dropped=commands.execute(f.courier,"courier:dropoff",key(),drop,()->workflow.dropoff(f.courier,drop));
        assertThat((List<?>)body(dropped).get("accepted")).hasSize(1);assertThat((List<?>)body(dropped).get("rejected")).hasSize(1);
        worker.processDocument(f.documents.getFirst());
        var receipt=new WorkflowCommandService.Batch(f.stations.get(1),items(f.documents.subList(0,2),1,false));
        var received=commands.execute(f.staff,"station:receipt:"+f.stations.get(1),key(),receipt,()->workflow.confirmReceipt(f.staff,f.stations.get(1),receipt));
        assertThat((List<?>)body(received).get("accepted")).hasSize(1);assertThat((List<?>)body(received).get("rejected")).hasSize(1);
        worker.processDocument(f.documents.getFirst());
        assertThat(db.queryForObject("select business_state from documents where id=?",String.class,f.documents.getFirst())).isEqualTo("UNIT_PROCESSING");
        assertThat(db.queryForObject("select ended_at is null from courier_legs where id=?",Boolean.class,wrong.leg_id())).isTrue();
    }
    @Test void expiryAndCancelReleaseEveryRemainingItemWithoutTouchingConsumedCustody() throws Exception {
        Fixture f=fixture(3);UUID reservation=id(reserve(f.courier,f.stations.getFirst(),reserveItems(f.documents),key()),"id");
        pickup(f,reservation,items(f.documents.subList(0,1),0,false),photo(f.courier,f.stations.getFirst()),key());
        db.update("update reservations set expires_at=now()-interval '1 minute' where id=?",reservation);reservations.expire();
        assertThat(db.queryForObject("select business_state from documents where id=?",String.class,f.documents.getFirst())).isEqualTo("IN_TRANSIT");
        for(UUID document:f.documents.subList(1,3)) assertThat(db.queryForObject("select business_state from documents where id=?",String.class,document)).isEqualTo("READY_FOR_PICKUP");
        UUID cancel=id(reserve(f.courier,f.stations.getFirst(),reserveItems(f.documents.subList(1,3)),key()),"id");
        commands.execute(f.courier,"reservation:cancel:"+cancel,key(),Map.of(),()->reservations.cancel(f.courier,cancel,0,"Not collected"));
        assertThat(db.queryForObject("select count(*) from document_events where event_type='PICKUP_RESERVATION_CANCELLED' and payload->>'reservation_id'=?",Integer.class,cancel.toString())).isEqualTo(2);
        assertThat(db.queryForObject("select count(*) from reservation_items where reservation_id=? and is_active",Integer.class,cancel)).isZero();
    }
    @Test void overlappingReverseOrderBatchesHaveOneReservationPerDocumentAndNoDeadlock() throws Exception {
        Fixture f=fixture(10);var forward=reserveItems(f.documents);var reverse=new ArrayList<>(forward);Collections.reverse(reverse);
        var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->{start.await();return reserve(f.courier,f.stations.getFirst(),forward,key());});
            var second=executor.submit(()->{start.await();return reserve(f.competitor,f.stations.getFirst(),reverse,key());});
            start.countDown();var results=List.of(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS));
            assertThat(results.stream().mapToInt(r->((List<?>)body(r).get("accepted")).size()).sum()).isEqualTo(10);
            assertThat(results.stream().mapToInt(r->((List<?>)body(r).get("rejected")).size()).sum()).isEqualTo(10);
        }
        for(UUID document:f.documents) assertThat(db.queryForObject("select count(*) from reservation_items where document_id=? and is_active",Integer.class,document)).isEqualTo(1);
    }
    @Test void duplicateItemsAreRejectedBeforeMutationAndAllRejectedResultHasStableActorOnlyOperation() throws Exception {
        Fixture f=fixture(2);var input=reserveItems(f.documents);
        assertThatThrownBy(()->reserve(f.courier,f.stations.getFirst(),List.of(input.getFirst(),input.getFirst()),key())).isInstanceOf(ApiException.class);
        reserve(f.competitor,f.stations.getFirst(),input,key());String retry=key();var rejected=reserve(f.courier,f.stations.getFirst(),input,retry);
        assertThat((List<?>)body(rejected).get("accepted")).isEmpty();assertThat((List<?>)body(rejected).get("rejected")).hasSize(2);
        assertThat(registry.operation(f.courier,id(rejected,"operation_id"))).containsEntry("process_sync_status","APPLIED");
        assertThatThrownBy(()->registry.operation(f.origin,id(rejected,"operation_id"))).isInstanceOf(ApiException.class);
        assertThat(normalized(body(rejected))).isEqualTo(normalized(body(reserve(f.courier,f.stations.getFirst(),input,retry))));
    }
    @Test void simultaneousPickupRetryCreatesOnlyOneLegAndEventForEveryAcceptedDocument() throws Exception {
        Fixture f=fixture(3);UUID reservation=id(reserve(f.courier,f.stations.getFirst(),reserveItems(f.documents),key()),"id");
        var items=items(f.documents,0,false);UUID photo=photo(f.courier,f.stations.getFirst());String retry=key();var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->{start.await();return pickup(f,reservation,items,photo,retry);});
            var second=executor.submit(()->{start.await();return pickup(f,reservation,items,photo,retry);});
            start.countDown();var a=first.get(30,TimeUnit.SECONDS);var b=second.get(30,TimeUnit.SECONDS);
            assertThat(normalized(body(a))).isEqualTo(normalized(body(b)));
        }
        for(UUID document:f.documents) {
            assertThat(db.queryForObject("select count(*) from courier_legs where document_id=?",Integer.class,document)).isEqualTo(1);
            assertThat(db.queryForObject("select count(*) from document_events where document_id=? and event_type='COURIER_PICKED_UP'",Integer.class,document)).isEqualTo(1);
        }
    }
    @Test void expiryRacingBulkPickupCannotTurnExpiredReservationIntoCustody() throws Exception {
        Fixture f=fixture(3);UUID reservation=id(reserve(f.courier,f.stations.getFirst(),reserveItems(f.documents),key()),"id");
        var items=items(f.documents,0,false);UUID photo=photo(f.courier,f.stations.getFirst());
        db.update("update reservations set expires_at=now()-interval '1 second' where id=?",reservation);var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var expiry=executor.submit(()->{start.await();return reservations.expire();});
            var pickup=executor.submit(()->{start.await();return pickup(f,reservation,items,photo,key());});
            start.countDown();expiry.get(30,TimeUnit.SECONDS);var result=pickup.get(30,TimeUnit.SECONDS);
            assertThat((List<?>)body(result).get("accepted")).isEmpty();assertThat((List<?>)body(result).get("rejected")).hasSize(3);
        }
        for(UUID document:f.documents) {
            assertThat(db.queryForObject("select count(*) from courier_legs where document_id=?",Integer.class,document)).isZero();
            assertThat(db.queryForObject("select business_state from documents where id=?",String.class,document)).isEqualTo("READY_FOR_PICKUP");
        }
    }
    @Test void databaseFailureRollsBackOnlyItsItemIncludingCustodyEvidenceAndEvent() throws Exception {
        Fixture f=fixture(2);UUID reservation=id(reserve(f.courier,f.stations.getFirst(),reserveItems(f.documents),key()),"id");
        UUID failing=f.documents.getLast(),photo=photo(f.courier,f.stations.getFirst());
        String guard="bulk_failure_"+UUID.randomUUID().toString().replace("-","");
        db.execute("create function "+guard+"() returns trigger language plpgsql as $$ begin if new.document_id='"+failing+"'::uuid and new.event_type='COURIER_PICKED_UP' then raise exception 'Injected item failure' using errcode='23514'; end if; return new; end $$");
        db.execute("create trigger "+guard+" before insert on document_events for each row execute function "+guard+"()");
        try {
            var result=pickup(f,reservation,items(f.documents,0,false),photo,key());
            assertThat((List<?>)body(result).get("accepted")).hasSize(1);assertThat((List<?>)body(result).get("rejected")).hasSize(1);
            assertThat(((Map<?,?>)((List<?>)body(result).get("rejected")).getFirst()).get("code")).isEqualTo("DATA_CONFLICT");
            assertThat(db.queryForObject("select business_state from documents where id=?",String.class,failing)).isEqualTo("PICKUP_RESERVED");
            assertThat(db.queryForObject("select count(*) from courier_legs where document_id=?",Integer.class,failing)).isZero();
            assertThat(db.queryForObject("select count(*) from document_events where document_id=? and event_type='COURIER_PICKED_UP'",Integer.class,failing)).isZero();
            assertThat(db.queryForObject("select is_active from reservation_items where reservation_id=? and document_id=?",Boolean.class,reservation,failing)).isTrue();
            assertThat(db.queryForObject("select count(*) from event_evidence x join document_events e on e.id=x.event_id where e.operation_id=?",Integer.class,id(result,"operation_id"))).isEqualTo(1);
        } finally {
            db.execute("drop trigger "+guard+" on document_events");db.execute("drop function "+guard+"()");
        }
    }
}
