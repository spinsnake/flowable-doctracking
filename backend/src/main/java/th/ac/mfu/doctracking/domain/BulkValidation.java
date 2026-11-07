package th.ac.mfu.doctracking.domain;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import th.ac.mfu.doctracking.common.ApiException;

import java.sql.SQLException;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

/** A submitted batch commits valid items; a rejected item rolls back to its own JDBC savepoint. */
@Component
public class BulkValidation {
    private final TrackingStore s;
    public BulkValidation(TrackingStore s) { this.s=s; }
    public <T> List<T> items(List<T> items,Function<T,UUID> document) {
        if(items==null || items.isEmpty() || items.size()>100) throw new ApiException(422,"INVALID_BATCH","Submit between 1 and 100 documents");
        Set<UUID> ids=new HashSet<>();
        for(T item:items) if(item==null || document.apply(item)==null || !ids.add(document.apply(item)))
            throw new ApiException(422,"INVALID_BATCH","Unique document IDs are required");
        return items.stream().sorted(Comparator.comparing(item->document.apply(item).toString())).toList();
    }
    public void lockDocuments(Collection<UUID> ids) {
        ids.stream().distinct().sorted(Comparator.comparing(UUID::toString)).forEach(id->s.db.queryForList("select id from documents where id=? for update",id));
    }
    public <T> T attempt(UUID document,boolean singleton,List<Map<String,Object>> rejected,Supplier<T> work) {
        var connection=DataSourceUtils.getConnection(Objects.requireNonNull(s.db.getDataSource()));
        java.sql.Savepoint savepoint;
        try { savepoint=connection.setSavepoint(); }
        catch(SQLException e) {
            DataSourceUtils.releaseConnection(connection,s.db.getDataSource());
            throw new IllegalStateException("Cannot create batch savepoint",e);
        }
        try {
            T result=work.get();connection.releaseSavepoint(savepoint);return result;
        } catch(ApiException | DataIntegrityViolationException failure) {
            try { connection.rollback(savepoint);connection.releaseSavepoint(savepoint); }
            catch(SQLException e) { throw new IllegalStateException("Cannot roll back batch item",e); }
            if(singleton) throw failure;
            String code=failure instanceof ApiException api?(api.status()==403?"FORBIDDEN":api.code()):"DATA_CONFLICT";
            String message=failure instanceof ApiException api && api.status()!=403?api.getMessage():"Document is unavailable for this action";
            rejected.add(Map.of("document_id",document,"code",code,"message",message));
            return null;
        } catch(SQLException e) { throw new IllegalStateException("Cannot release batch savepoint",e); }
        finally { DataSourceUtils.releaseConnection(connection,s.db.getDataSource()); }
    }
}
