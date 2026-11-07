package th.ac.mfu.doctracking.operations;

import io.micrometer.core.instrument.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Gauges report observed counts/ages; deployment alert rules supply approved operational thresholds. */
@Component
public class OperationsMetrics {
    public OperationsMetrics(MeterRegistry registry,JdbcTemplate db) {
        gauge(registry,db,"doctracking.workflow.outbox.pending","select count(*) from workflow_outbox where status='PENDING'");
        gauge(registry,db,"doctracking.workflow.outbox.retry","select count(*) from workflow_outbox where status='RETRY_WAIT'");
        gauge(registry,db,"doctracking.workflow.outbox.dead.letter","select count(*) from workflow_outbox where status='DEAD_LETTER'");
        gauge(registry,db,"doctracking.workflow.outbox.lag.seconds","select greatest(coalesce(extract(epoch from now()-min(created_at)),0),0) from workflow_outbox where status<>'APPLIED'");
        gauge(registry,db,"doctracking.mail.retry","select count(*) from notification_outbox where status='RETRY_WAIT'");
        gauge(registry,db,"doctracking.mail.failed","select count(*) from notification_outbox where status='FAILED'");
        gauge(registry,db,"doctracking.workflow.tasks.waiting","select count(*) from act_ru_task");
        gauge(registry,db,"doctracking.workflow.tasks.oldest.seconds","select greatest(coalesce(extract(epoch from now()-min(create_time_)),0),0) from act_ru_task");
    }
    private void gauge(MeterRegistry registry,JdbcTemplate db,String name,String sql) {
        Gauge.builder(name,db,jdbc->{try{return jdbc.queryForObject(sql,Double.class);}catch(RuntimeException unavailable){return Double.NaN;}}).register(registry);
    }
}
