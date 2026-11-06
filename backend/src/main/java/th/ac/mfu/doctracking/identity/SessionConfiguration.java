package th.ac.mfu.doctracking.identity;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.config.annotation.web.http.EnableSpringHttpSession;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration @EnableSpringHttpSession
public class SessionConfiguration {
    @Bean JdbcIndexedSessionRepository sessionRepository(JdbcTemplate db,PlatformTransactionManager manager,@Value("${app.session-ttl-minutes:30}") long minutes) {
        TransactionTemplate required=new TransactionTemplate(manager);required.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        if(minutes<1 || minutes>1440)throw new IllegalArgumentException("Session TTL must be between 1 and 1440 minutes");
        JdbcIndexedSessionRepository repository=new JdbcIndexedSessionRepository(db,required);repository.setDefaultMaxInactiveInterval(Duration.ofMinutes(minutes));repository.setCleanupCron("0 * * * * *");return repository;
    }
    @Bean DefaultCookieSerializer cookieSerializer(@Value("${app.session-secure:true}") boolean secure){var cookie=new DefaultCookieSerializer();cookie.setCookieName("SESSION");cookie.setCookiePath("/");cookie.setUseHttpOnlyCookie(true);cookie.setUseSecureCookie(secure);cookie.setSameSite("Lax");return cookie;}
}
