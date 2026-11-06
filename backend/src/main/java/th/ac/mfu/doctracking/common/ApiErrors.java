package th.ac.mfu.doctracking.common;

import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> domain(ApiException error) {
        return response(error.status(), error.code(), error.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            IllegalArgumentException.class})
    ResponseEntity<?> invalid(Exception error) {
        return response(400, "INVALID_INPUT", "ข้อมูลไม่ถูกต้อง กรุณาตรวจสอบรายการ");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> conflict(DataIntegrityViolationException error) {
        return response(409, "DATA_CONFLICT", "ข้อมูลซ้ำหรือสถานะเปลี่ยนแล้ว กรุณาโหลดข้อมูลใหม่");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception error) {
        if (error instanceof org.springframework.web.ErrorResponse httpError) {
            int status=httpError.getStatusCode().value();
            if(status>=400 && status<500) return response(status,"HTTP_ERROR","คำขอหรือเส้นทางไม่ถูกต้อง");
        }
        String correlation = UUID.randomUUID().toString();
        // Do not log raw request, token, SQL parameters or provider errors.
        LOG.error("request_failed correlation_id={} exception={}", correlation, error.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(Map.of("code", "INTERNAL_ERROR",
                "message", "ระบบไม่สามารถทำรายการได้", "correlation_id", correlation));
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<?> concurrentChange(PessimisticLockingFailureException error) {
        return response(409, "CONCURRENT_CHANGE", "มีการเปลี่ยนแปลงพร้อมกัน กรุณาลองอีกครั้งด้วยคำขอเดิม");
    }

    private ResponseEntity<?> response(int status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message,
                "correlation_id", UUID.randomUUID().toString()));
    }
}
