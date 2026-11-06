package th.ac.mfu.doctracking.domain;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.ac.mfu.doctracking.common.ApiException;
import th.ac.mfu.doctracking.common.CommandResult;
import th.ac.mfu.doctracking.identity.Actor;

import javax.imageio.ImageIO;
import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@Service
public class EvidenceService {
    private final TrackingStore s;
    private final PrivatePhotoStorage storage;
    private final int maxBytes;
    public EvidenceService(TrackingStore s,PrivatePhotoStorage storage,@Value("${app.evidence.max-bytes:10485760}") int maxBytes) {
        if(maxBytes<1 || maxBytes>20971520) throw new IllegalArgumentException("Evidence size limit unsupported");
        this.s=s;this.storage=storage;this.maxBytes=maxBytes;
    }
    public record Upload(UUID document_id,UUID station_id,String content_type,Long size_bytes,String checksum_sha256) {}
    public record Download(byte[] bytes,String contentType) {}
    public void authorizeContext(Actor actor,Upload input) {
        if(input.document_id()==null && input.station_id()==null) throw new ApiException(422,"EVIDENCE_CONTEXT_REQUIRED","Document or station required");
        if(input.document_id()!=null) {
            var doc=s.document(input.document_id(),false);
            if(!actor.hasUnit(TrackingStore.id(doc,"origin_unit_id"))) {
                actor.requireCapability("COURIER");
                UUID station=(UUID)s.one("select station_id from route_steps where id=?",doc.get("current_route_step_id")).get("station_id");
                boolean activeOwner=false;
                if("IN_TRANSIT".equals(doc.get("business_state")) && doc.get("active_leg_id")!=null) {
                    var leg=s.one("select l.courier_user_id,r.station_id from courier_legs l join route_steps r on r.id=l.to_step_id where l.id=? and l.document_id=? and l.ended_at is null",doc.get("active_leg_id"),input.document_id());
                    activeOwner=actor.id().equals(leg.get("courier_user_id")) && actor.hasStation(TrackingStore.id(leg,"station_id"));
                }
                if(!activeOwner && (!actor.stationIds().contains(station) || !Set.of("READY_FOR_PICKUP","AWAIT_PHYSICAL_PICKUP","DROPPED_OFF_UNCONFIRMED","PICKUP_RESERVED").contains(doc.get("business_state"))))
                    throw new ApiException(404,"NOT_FOUND","Resource not found");
            }
        }
        if(input.station_id()!=null && !actor.stationIds().contains(input.station_id())) throw new ApiException(403,"STATION_SCOPE_REQUIRED","Upload station permission required");
    }
    public CommandResult create(Actor actor,Upload input) {
        authorizeContext(actor,input);
        if(input.content_type()==null || !Set.of("image/jpeg","image/png").contains(input.content_type()) || input.size_bytes()==null || input.size_bytes()<1 || input.size_bytes()>maxBytes || input.checksum_sha256()==null || !input.checksum_sha256().matches("[0-9a-fA-F]{64}"))
            throw new ApiException(422,"INVALID_PHOTO_METADATA","JPEG/PNG, allowed size and SHA-256 checksum are required");
        UUID id=UUID.randomUUID();
        s.db.update("insert into evidence_uploads(id,owner_user_id,document_id,station_id,content_type,size_bytes,checksum_sha256,storage_key) values(?,?,?,?,?,?,?,?)",id,actor.id(),input.document_id(),input.station_id(),input.content_type(),input.size_bytes(),input.checksum_sha256().toLowerCase(Locale.ROOT),id.toString());
        return new CommandResult(201,metadata(id));
    }
    public void authorizeOwner(Actor actor,UUID id) {
        var row=s.one("select owner_user_id,document_id,station_id from evidence_uploads where id=?",id);
        if(!actor.id().equals(row.get("owner_user_id"))) throw new ApiException(404,"NOT_FOUND","Resource not found");
        authorizeContext(actor,new Upload(TrackingStore.id(row,"document_id"),TrackingStore.id(row,"station_id"),null,null,null));
    }
    @Transactional
    public Map<String,Object> content(Actor actor,UUID id,String contentType,InputStream input) {
        authorizeOwner(actor,id);
        var row=s.one("select * from evidence_uploads where id=? for update",id);
        if("FINALIZED".equals(row.get("status"))) throw new ApiException(409,"UPLOAD_FINALIZED","Finalized photo cannot be overwritten");
        if(!Objects.equals(contentType,row.get("content_type"))) throw new ApiException(422,"PHOTO_TYPE_MISMATCH","Content-Type must match upload metadata");
        byte[] bytes;
        try { bytes=input.readNBytes(maxBytes+1); }
        catch(IOException e) { throw new ApiException(422,"UPLOAD_INCOMPLETE","Cannot read photo content"); }
        validateBytes(row,bytes);
        storage.put((String)row.get("storage_key"),bytes,(String)row.get("content_type"));
        s.db.update("update evidence_uploads set status='STORED' where id=?",id);return metadata(id);
    }
    public CommandResult finalizeUpload(Actor actor,UUID id) {
        authorizeOwner(actor,id);
        var row=s.one("select * from evidence_uploads where id=? for update",id);
        if("FINALIZED".equals(row.get("status"))) return new CommandResult(200,metadata(id));
        if(!"STORED".equals(row.get("status"))) throw new ApiException(409,"UPLOAD_NOT_STORED","Upload content before finalizing");
        byte[] bytes=storage.get((String)row.get("storage_key"));
        validateBytes(row,bytes);validateImage(bytes,(String)row.get("content_type"));
        s.db.update("update evidence_uploads set status='FINALIZED',finalized_at=now() where id=?",id);
        return new CommandResult(200,metadata(id));
    }
    private void validateBytes(Map<String,Object> row,byte[] bytes) {
        if(bytes.length>maxBytes || bytes.length!=((Number)row.get("size_bytes")).longValue() || !checksum(bytes).equals(row.get("checksum_sha256")))
            throw new ApiException(422,"PHOTO_INTEGRITY_MISMATCH","Photo size or checksum does not match");
    }
    private void validateImage(byte[] bytes,String contentType) {
        try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers=ImageIO.getImageReaders(input);
            if(!readers.hasNext()) throw new IOException("Unsupported image");
            var reader=readers.next();
            try {
                reader.setInput(input,true,true);String format=reader.getFormatName().toLowerCase(Locale.ROOT);
                if(!(("png".equals(format) && "image/png".equals(contentType)) || (("jpeg".equals(format)||"jpg".equals(format)) && "image/jpeg".equals(contentType)))) throw new IOException("MIME mismatch");
                int w=reader.getWidth(0),h=reader.getHeight(0);
                if(w<1 || h<1 || w>10000 || h>10000 || (long)w*h>25000000) throw new IOException("Image dimensions exceed limit");
                if(reader.read(0)==null) throw new IOException("Cannot decode image");
            } finally { reader.dispose(); }
        } catch(IOException | RuntimeException e) { throw new ApiException(422,"INVALID_PHOTO","Photo must be a decodable JPEG/PNG within dimension limits"); }
    }
    public static String checksum(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    Map<String,Object> metadata(UUID id) {
        return s.one("select id,status,content_type,size_bytes,checksum_sha256,document_id,station_id,finalized_at from evidence_uploads where id=?",id);
    }
    public Download download(Actor actor,UUID id) {
        var row=s.one("select * from evidence_uploads where id=? and status='FINALIZED'",id);
        boolean owner=actor.id().equals(row.get("owner_user_id"));
        if(owner) authorizeOwner(actor,id);
        else {
            // Origin can download only evidence actually attached to an authorized event, not arbitrary unlinked uploads.
            var linked=s.db.queryForList("select d.origin_unit_id from event_evidence x join document_events e on e.id=x.event_id join documents d on d.id=e.document_id where x.evidence_id=?",id);
            if(linked.stream().noneMatch(r->actor.hasUnit(TrackingStore.id(r,"origin_unit_id")))) throw new ApiException(404,"NOT_FOUND","Resource not found");
        }
        byte[] bytes=storage.get((String)row.get("storage_key"));validateBytes(row,bytes);
        return new Download(bytes,(String)row.get("content_type"));
    }
    public void requireFinalized(Actor actor,List<UUID> ids,UUID document,UUID station) {
        if(ids==null || ids.isEmpty() || new HashSet<>(ids).size()!=ids.size()) throw new ApiException(422,"EVIDENCE_REQUIRED","Unique finalized photo IDs required");
        for(UUID id:ids) {
            authorizeOwner(actor,id);
            var row=s.one("select * from evidence_uploads where id=? for share",id);
            if(!"FINALIZED".equals(row.get("status")) || (row.get("document_id")!=null && !document.equals(row.get("document_id"))) || (row.get("station_id")!=null && !station.equals(row.get("station_id"))))
                throw new ApiException(422,"EVIDENCE_NOT_USABLE","Photo is not finalized for this command context");
        }
    }
}
