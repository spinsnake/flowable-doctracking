package th.ac.mfu.doctracking;

import java.nio.file.*;
import java.sql.*;
import java.util.Properties;

/** Native-JVM recovery probe. Run ONLY against the disposable schema from the completed integration suite. */
public class WorkflowRestartProbe {
    public static void main(String[] args) throws Exception {
        String url=System.getenv("RESTART_DB_URL");
        if(url==null || !url.matches("jdbc:postgresql:.*[?&]currentSchema=p1_test_[a-f0-9]{32}"))
            throw new IllegalArgumentException("An isolated p1_test schema is required");
        Path evidence=Path.of(args[1]);var state=new Properties();
        if(Files.exists(evidence))try(var in=Files.newInputStream(evidence)){state.load(in);}
        try(var db=DriverManager.getConnection(url,System.getenv("TEST_DB_USER"),System.getenv("TEST_DB_PASSWORD"))) {
            if(args[0].equals("prepare")) {
                // Existing registration was committed by the now-exited test JVM; no worker has consumed it.
                try(var q=db.createStatement();var rows=q.executeQuery("""
                    select d.id from documents d where d.business_state='WAIT_ORIGIN_READY' and d.process_sync_status='PENDING'
                    and not exists(select 1 from workflow_instances w where w.document_id=d.id)
                    and not exists(select 1 from act_ru_execution x where x.business_key_=d.id::text)
                    and (select count(*) from workflow_outbox o where o.document_id=d.id)=1
                    and exists(select 1 from workflow_outbox o where o.document_id=d.id and o.action='DOCUMENT_REGISTERED' and o.status='PENDING')
                    order by d.created_at desc limit 1
                    """)) {
                    if(!rows.next())throw new AssertionError("No pending registration fixture");
                    state.setProperty("document_id",rows.getString(1));
                }
                // Quiesce unrelated test fixtures; do not forge any ACK or alter the target registration.
                try(var q=db.prepareStatement("update workflow_outbox set available_at=now()+interval '10 years' where document_id<>?::uuid and status<>'APPLIED'")) {
                    q.setString(1,state.getProperty("document_id"));q.executeUpdate();
                }
            } else {
                try(var q=db.prepareStatement("""
                    select w.process_instance_id,t.id_ as task_id,t.task_def_key_,d.process_sync_status,
                      (select count(*) from workflow_inbox i where i.document_id=d.id) as consumed,
                      (select count(*) from act_ru_execution x where x.business_key_=d.id::text) as instances
                    from documents d join workflow_instances w on w.document_id=d.id
                    join act_ru_task t on t.proc_inst_id_=w.process_instance_id where d.id=?::uuid
                    """)) {
                    q.setString(1,state.getProperty("document_id"));
                    try(var rows=q.executeQuery()) {
                        if(!rows.next() || !rows.getString("process_sync_status").equals("APPLIED") || !rows.getString("task_def_key_").equals("ORIGIN_READY")
                                || rows.getInt("consumed")!=1 || rows.getInt("instances")!=1)throw new AssertionError("Recovery not applied exactly once");
                        String process=rows.getString("process_instance_id"),task=rows.getString("task_id");
                        if(state.containsKey("process_instance_id") && (!process.equals(state.getProperty("process_instance_id")) || !task.equals(state.getProperty("task_id"))))
                            throw new AssertionError("JVM restart created or advanced another process/task");
                        state.setProperty("process_instance_id",process);state.setProperty("task_id",task);
                    }
                }
            }
        }
        try(var out=Files.newOutputStream(evidence)){state.store(out,"Disposable PostgreSQL JVM recovery evidence; no credentials");}
        System.out.println(args[0]+" passed: "+state);
    }
}
