package th.ac.mfu.doctracking.common;

import java.net.URI;
import java.util.List;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration
public class RuntimeConfig {
    @Bean
    ObjectMapper persistenceJson() {
        return JsonMapper.builder().addModule(new JavaTimeModule())
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    }

    @Bean
    ApplicationRunner productionRequirements(Environment env) {
        return args -> {
            if (!env.acceptsProfiles(Profiles.of("prod"))) return;
            if (!env.getProperty("app.policy-confirmed", Boolean.class, false)) {
                throw new IllegalStateException("Production requires explicit confirmation of P0 policy dependencies");
            }
            for (String key : List.of("app.base-url", "app.google-client-id", "app.google-client-secret",
                    "app.bootstrap-admin-email", "app.mail-from", "spring.mail.host", "app.it-contact")) {
                if (env.getProperty(key, "").isBlank()) throw new IllegalStateException("Required production configuration missing: " + key);
            }
            URI origin = URI.create(env.getProperty("app.base-url"));
            if (!"https".equals(origin.getScheme()) || origin.getHost() == null || origin.getRawUserInfo() != null
                    || origin.getQuery() != null || origin.getFragment() != null
                    || !(origin.getPath().isEmpty() || origin.getPath().equals("/"))) {
                throw new IllegalStateException("Production APP_URL must be an HTTPS origin");
            }
            if (!env.getProperty("app.bootstrap-admin-email", "").trim().toLowerCase().matches("[^@\\s]+@mfu\\.ac\\.th")
                    || !env.getProperty("app.session-secure", Boolean.class, true)
                    || !env.getProperty("app.mail-enabled", Boolean.class, false)
                    || !"s3".equals(env.getProperty("app.evidence.backend", "local"))) {
                throw new IllegalStateException("Production requires MFU admin, secure sessions, mail and private S3 evidence");
            }
            if (!(env.getProperty("spring.mail.properties.mail.smtp.starttls.required", Boolean.class, false)
                    || env.getProperty("spring.mail.properties.mail.smtp.ssl.enable", Boolean.class, false))) {
                throw new IllegalStateException("Production SMTP requires TLS");
            }
        };
    }
}
