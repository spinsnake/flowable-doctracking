package th.ac.mfu.doctracking.domain;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import th.ac.mfu.doctracking.common.ApiException;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.UUID;

@Component
public class PrivatePhotoStorage {
    private final Path root;
    private final S3Client s3;
    private final String bucket;
    public PrivatePhotoStorage(@Value("${app.evidence.backend:local}") String backend,
                              @Value("${app.evidence.root:./var/private-evidence}") String root,
                              @Value("${app.evidence.bucket:}") String bucket,
                              @Value("${app.evidence.region:ap-southeast-1}") String region,
                              @Value("${app.evidence.endpoint:}") String endpoint) {
        this.root=Path.of(root).toAbsolutePath().normalize();this.bucket=bucket;
        if("s3".equals(backend)) {
            if(bucket.isBlank()) throw new IllegalArgumentException("Private evidence bucket required");
            var builder=S3Client.builder().region(Region.of(region));
            if(!endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint)).forcePathStyle(true);
            s3=builder.build();
        } else if("local".equals(backend)) s3=null;
        else throw new IllegalArgumentException("Unsupported evidence backend");
    }
    private Path path(String key) {
        // Storage keys are generated UUIDs, never user filenames or URLs.
        if(!UUID.fromString(key).toString().equals(key)) throw new IllegalArgumentException("Invalid storage key");
        Path value=root.resolve(key).normalize();
        if(!value.startsWith(root)) throw new IllegalArgumentException("Invalid storage key");return value;
    }
    public void put(String key,byte[] bytes,String contentType) {
        try {
            path(key);
            if(s3!=null) s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),RequestBody.fromBytes(bytes));
            else {
                Path destination=path(key);Files.createDirectories(root);
                Path temp=Files.createTempFile(root,"upload-",".tmp");
                try { Files.write(temp,bytes);Files.move(temp,destination,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
                finally { Files.deleteIfExists(temp); }
            }
        } catch(IOException | RuntimeException e) { throw new ApiException(503,"EVIDENCE_STORAGE_UNAVAILABLE","Photo storage unavailable"); }
    }
    public byte[] get(String key) {
        try {
            path(key);
            if(s3!=null) return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
            return Files.readAllBytes(path(key));
        } catch(IOException | RuntimeException e) { throw new ApiException(503,"EVIDENCE_STORAGE_UNAVAILABLE","Photo storage unavailable"); }
    }
    /** Idempotent, exact-key deletion; callers must hold the upload row lock. */
    public void delete(String key) {
        try {
            path(key); // Validate generated UUID keys for both backends, including S3.
            if(s3!=null) s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
            else Files.deleteIfExists(path(key));
        } catch(IOException | RuntimeException e) { throw new ApiException(503,"EVIDENCE_STORAGE_UNAVAILABLE","Photo storage unavailable"); }
    }
    @PreDestroy public void close() { if(s3!=null) s3.close(); }
}
