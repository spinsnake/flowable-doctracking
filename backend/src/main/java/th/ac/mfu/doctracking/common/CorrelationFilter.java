package th.ac.mfu.doctracking.common;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/** Only route templates and validated random IDs reach request logs; never URLs, queries or bodies. */
@Component @Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationFilter extends OncePerRequestFilter {
    private static final Logger LOG=LoggerFactory.getLogger(CorrelationFilter.class);
    public static String current() { String value=MDC.get("correlation_id");return value==null?UUID.randomUUID().toString():value; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
        String supplied=request.getHeader("X-Correlation-ID");
        String id=supplied!=null && supplied.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")?supplied.toLowerCase():UUID.randomUUID().toString();
        String previous=MDC.get("correlation_id");MDC.put("correlation_id",id);response.setHeader("X-Correlation-ID",id);
        long started=System.nanoTime();
        try { chain.doFilter(request,response); }
        finally {
            Object route=request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            LOG.info("http_request correlation_id={} method={} route={} status={} duration_ms={}",id,
                    request.getMethod().matches("GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS")?request.getMethod():"OTHER",
                    route==null?"unmapped":route,response.getStatus(),(System.nanoTime()-started)/1_000_000);
            if(previous==null)MDC.remove("correlation_id");else MDC.put("correlation_id",previous);
        }
    }
}
