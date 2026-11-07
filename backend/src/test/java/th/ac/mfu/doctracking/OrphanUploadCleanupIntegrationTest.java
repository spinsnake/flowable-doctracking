package th.ac.mfu.doctracking;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import th.ac.mfu.doctracking.domain.*;
import th.ac.mfu.doctracking.identity.Actor;
import th.ac.mfu.doctracking.common.ApiException;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
class OrphanUploadCleanupIntegrationTest extends DatabaseTest {
    @Autowired TrackingStore store;
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager manager;
    Actor admin; UUID station; Path root; PrivatePhotoStorage storage;
    @BeforeEach void setup() throws Exception {
        UUID user=UUID.randomUUID(),unit=UUID.randomUUID();station=UUID.randomUUID();
        db.update("insert into users(id,email,display_name,is_admin) values(?,?,?,true)",user,"cleanup-"+user+"@mfu.ac.th","Cleanup");
        db.update("insert into units(id,code,name,is_active) values(?,?,?,true)",unit,"cleanup-"+unit,"Cleanup");
        db.update("insert into stations(id,unit_id,code,name) values(?,?,?,?)",station,unit,"cleanup-"+station,"Cleanup");
        admin=new Actor(user,"cleanup@mfu.ac.th","Cleanup",true,List.of(),Set.of(),Set.of(),0);
        root=Files.createTempDirectory("doctracking-cleanup-");storage=new PrivatePhotoStorage("local",root.toString(),"","ap-southeast-1","");
    }
    UUID upload(String status,boolean old) {
        UUID id=UUID.randomUUID();byte[] bytes={1,2,3};storage.put(id.toString(),bytes,"image/png");
        db.update("insert into evidence_uploads(id,owner_user_id,station_id,content_type,size_bytes,checksum_sha256,storage_key,status,created_at) values(?,?,?,'image/png',3,?,?,?,now()-(? * interval '1 day'))",id,admin.id(),station,EvidenceService.checksum(bytes),id.toString(),status,old?10:0);
        return id;
    }
    OrphanUploadCleanup cleanup(PrivatePhotoStorage photos,boolean enabled,long grace) { return new OrphanUploadCleanup(store,photos,manager,enabled,grace); }
    @Test void policyAndAdminAreRequiredAndPreviewDoesNotDelete() {
        UUID old=upload("STORED",true);
        assertThatThrownBy(()->cleanup(storage,false,0).run(admin,true,100)).isInstanceOf(ApiException.class).hasMessageContaining("grace");
        assertThatThrownBy(()->cleanup(storage,false,86400).run(admin,false,100)).isInstanceOf(ApiException.class).hasMessageContaining("disabled");
        Actor staff=new Actor(admin.id(),admin.email(),"Staff",false,List.of(),Set.of(),Set.of(),0);
        assertThatThrownBy(()->cleanup(storage,true,86400).run(staff,false,100)).isInstanceOf(ApiException.class);
        assertThat(cleanup(storage,false,86400).run(admin,true,100)).containsEntry("deleted",0);
        assertThat(Files.exists(root.resolve(old.toString()))).isTrue();
    }
    @Test void cleanupNeverDeletesFinalizedOrYoungUploadsAndRetryIsSafe() {
        UUID finalized=upload("FINALIZED",true),young=upload("INITIATED",false),old=upload("STORED",true);
        var result=cleanup(storage,true,86400).run(admin,false,100);
        assertThat(result).containsEntry("failed",0);
        assertThat(db.queryForObject("select count(*) from evidence_uploads where id=?",Integer.class,old)).isZero();
        for(UUID keep:List.of(finalized,young)) {assertThat(Files.exists(root.resolve(keep.toString()))).isTrue();assertThat(db.queryForObject("select count(*) from evidence_uploads where id=?",Integer.class,keep)).isEqualTo(1);}
        assertThat(cleanup(storage,true,86400).run(admin,false,100)).containsEntry("deleted",0);
    }
    @Test void uncertainStorageAckRetainsMetadataForIdempotentRetry() {
        UUID old=upload("STORED",true);PrivatePhotoStorage uncertain=mock(PrivatePhotoStorage.class);
        doAnswer(call->{storage.delete(call.getArgument(0));throw new IllegalStateException("Lost storage acknowledgement");}).when(uncertain).delete(old.toString());
        cleanup(uncertain,true,86400).run(admin,false,100);
        assertThat(db.queryForObject("select count(*) from evidence_uploads where id=?",Integer.class,old)).isEqualTo(1);
        cleanup(storage,true,86400).run(admin,false,100);
        assertThat(db.queryForObject("select count(*) from evidence_uploads where id=?",Integer.class,old)).isZero();
    }
    @Test void receiptOrAuditRollbackKeepsMetadataAndRetryTargetsTheSameUpload() {
        UUID old=upload("STORED",true);
        assertThatThrownBy(()->new TransactionTemplate(manager).execute(status->{
            cleanup(storage,true,86400).run(admin,false,100);
            throw new IllegalStateException("Audit persistence failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("select count(*) from evidence_uploads where id=?",Integer.class,old)).isEqualTo(1);
        assertThat(Files.exists(root.resolve(old.toString()))).isFalse();
        cleanup(storage,true,86400).run(admin,false,100);
        assertThat(db.queryForObject("select count(*) from evidence_uploads where id=?",Integer.class,old)).isZero();
    }
    @Test void uploadHeldByFinalizerIsSkippedThenFinalizedRowRemains() throws Exception {
        UUID old=upload("STORED",true);var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var finalizer=executor.submit(()->new TransactionTemplate(manager).execute(status->{db.queryForMap("select id from evidence_uploads where id=? for update",old);locked.countDown();try {if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}db.update("update evidence_uploads set status='FINALIZED',finalized_at=now() where id=?",old);return true;}));
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
            try {cleanup(storage,true,86400).run(admin,false,100);assertThat(Files.exists(root.resolve(old.toString()))).isTrue();}finally {release.countDown();}
            assertThat(finalizer.get(10,TimeUnit.SECONDS)).isTrue();
        }
        cleanup(storage,true,86400).run(admin,false,100);
        assertThat(db.queryForObject("select status from evidence_uploads where id=?",String.class,old)).isEqualTo("FINALIZED");
    }
    @AfterEach void removeOwnTemporaryFiles() throws Exception {
        storage.close();try(var files=Files.list(root)){for(Path path:files.toList()) Files.deleteIfExists(path);}Files.deleteIfExists(root);
    }
}
