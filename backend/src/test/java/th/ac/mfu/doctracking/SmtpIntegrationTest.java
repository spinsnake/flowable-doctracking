package th.ac.mfu.doctracking;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.transaction.PlatformTransactionManager;
import th.ac.mfu.doctracking.notifications.NotificationWorker;
import static org.assertj.core.api.Assertions.*;

/** Loopback-only SMTP sink: no internet relay or real mailbox is contacted. */
@SpringBootTest
class SmtpIntegrationTest extends DatabaseTest {
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager manager;

    @Test void realSmtpTransportDeliversStableMessageIdAndRecordsProviderAcceptance() throws Exception {
        UUID user=UUID.randomUUID(),event=UUID.randomUUID(),job=UUID.randomUUID();String email="smtp-"+user+"@mfu.ac.th";
        db.update("insert into users(id,email,display_name,is_admin) values (?,?,'SMTP fixture',true)",user,email);
        db.update("insert into auth_events(id,user_id,event_type) values (?,?,'LOGIN_SUCCEEDED')",event,user);
        // Prioritize this isolated fixture without touching other tests' jobs.
        db.update("insert into notification_outbox(id,login_event_id,recipient,login_at) values (?,?,?,'1970-01-01T00:00:00Z')",job,event,email);
        try(var socket=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));var executor=Executors.newSingleThreadExecutor()) {
            socket.setSoTimeout(10000);
            Future<byte[]> received=executor.submit(()->receive(socket));
            var sender=new JavaMailSenderImpl();sender.setHost("127.0.0.1");sender.setPort(socket.getLocalPort());
            sender.getJavaMailProperties().put("mail.smtp.connectiontimeout","5000");
            sender.getJavaMailProperties().put("mail.smtp.timeout","5000");
            sender.getJavaMailProperties().put("mail.smtp.writetimeout","5000");
            var worker=new NotificationWorker(db,manager,sender,false,true,"no-reply@example.invalid","Local fixture IT");
            assertThat(worker.deliverOne()).isTrue();
            var message=new MimeMessage(Session.getInstance(new Properties()),new ByteArrayInputStream(received.get(10,TimeUnit.SECONDS)));
            assertThat(message.getMessageID()).isEqualTo(NotificationWorker.messageId(job));
            assertThat(message.getAllRecipients()).extracting(Object::toString).containsExactly(email);
            assertThat(message.getContent().toString()).contains("Asia/Bangkok","Local fixture IT").doesNotContain(user.toString(),event.toString());
            assertThat(db.queryForObject("select status from notification_outbox where id=?",String.class,job)).isEqualTo("ACCEPTED_BY_PROVIDER");
            assertThat(db.queryForObject("select status from notification_delivery_attempts where notification_id=?",String.class,job)).isEqualTo("ACCEPTED_BY_PROVIDER");
        }
    }

    private byte[] receive(ServerSocket server) throws Exception {
        try(var connection=server.accept()) {
            connection.setSoTimeout(10000);
            var input=new BufferedReader(new InputStreamReader(connection.getInputStream(),StandardCharsets.US_ASCII));
            var output=new PrintWriter(new OutputStreamWriter(connection.getOutputStream(),StandardCharsets.US_ASCII),true);
            output.print("220 localhost SMTP fixture\r\n");output.flush();
            var message=new StringBuilder();boolean data=false;
            for(String line;(line=input.readLine())!=null;) {
                if(data) {
                    if(line.equals(".")){data=false;output.print("250 accepted locally\r\n");output.flush();}
                    else message.append(line.startsWith("..")?line.substring(1):line).append("\r\n");
                } else if(line.startsWith("EHLO") || line.startsWith("HELO")) output.print("250 localhost\r\n");
                else if(line.equals("DATA")){data=true;output.print("354 end with dot\r\n");}
                else if(line.equals("QUIT")){output.print("221 bye\r\n");output.flush();break;}
                else output.print("250 OK\r\n");
                output.flush();
            }
            return message.toString().getBytes(StandardCharsets.US_ASCII);
        }
    }
}
