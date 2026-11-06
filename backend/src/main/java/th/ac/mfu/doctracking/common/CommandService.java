package th.ac.mfu.doctracking.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import th.ac.mfu.doctracking.identity.Actor;

@Service
public class CommandService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;

    public CommandService(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.json = json;
        this.transaction = new TransactionTemplate(manager);
    }

    /** Callers must authorize the current actor/resource before entering, including replays. */
    public CommandResult execute(Actor actor, String scope, String key, Object request,
                                 Supplier<CommandResult> work) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{8,200}")) {
            throw new ApiException(400, "IDEMPOTENCY_KEY_REQUIRED", "กรุณาระบุ Idempotency-Key ที่ถูกต้อง");
        }
        String hash = hash(request);
        return transaction.execute(status -> {
            // Unit administration takes unit locks before revoking member sessions.
            jdbc.queryForList("""
                    SELECT id FROM units WHERE id IN (
                        SELECT unit_id FROM unit_memberships WHERE user_id=?
                        UNION SELECT s.unit_id FROM user_station_permissions p
                        JOIN stations s ON s.id=p.station_id WHERE p.user_id=?
                    ) ORDER BY id FOR SHARE
                    """, actor.id(), actor.id());
            if (jdbc.queryForList("""
                    SELECT id FROM users WHERE id=? AND is_active AND session_version=? FOR SHARE
                    """, actor.id(), actor.sessionVersion()).isEmpty()) {
                throw new ApiException(401, "SESSION_REVOKED", "Session revoked");
            }
            int inserted = jdbc.update("""
                    INSERT INTO command_receipts(actor_id, scope, key, request_hash)
                    VALUES (?, ?, ?, ?) ON CONFLICT (actor_id, scope, key) DO NOTHING
                    """, actor.id(), scope, key, hash);
            if (inserted == 0) {
                var stored = jdbc.queryForMap("""
                        SELECT request_hash, status, response::text AS response
                        FROM command_receipts WHERE actor_id=? AND scope=? AND key=? FOR UPDATE
                        """, actor.id(), scope, key);
                if (!hash.equals(stored.get("request_hash"))) {
                    throw new ApiException(409, "IDEMPOTENCY_MISMATCH", "คำขอซ้ำมีข้อมูลต่างจากครั้งก่อน");
                }
                if (stored.get("status") == null || stored.get("response") == null) {
                    throw new ApiException(409, "OPERATION_PENDING", "คำขอกำลังประมวลผล");
                }
                return new CommandResult(((Number) stored.get("status")).intValue(), decode((String) stored.get("response")));
            }
            CommandResult result = work.get();
            jdbc.update("""
                    UPDATE command_receipts SET status=?, response=?::jsonb
                    WHERE actor_id=? AND scope=? AND key=?
                    """, result.status(), encode(result.body()), actor.id(), scope, key);
            return result;
        });
    }

    private String hash(Object request) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(encode(request).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalArgumentException("Unsupported command JSON", error); }
    }

    private Object decode(String value) {
        try { return json.readValue(value, Object.class); }
        catch (Exception error) { throw new IllegalStateException("Invalid stored command result", error); }
    }
}
