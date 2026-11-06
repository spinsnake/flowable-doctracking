package th.ac.mfu.doctracking.identity;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.client.web.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.endpoint.*;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.*;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.csrf.*;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfiguration {
    @Bean ClientRegistrationRepository clientRegistrations(@Value("${app.google-client-id:}") String id,@Value("${app.google-client-secret:}") String secret,@Value("${app.base-url:http://localhost:8080}") String base){
        return new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("google").clientId(id.isBlank()?"unconfigured":id).clientSecret(secret.isBlank()?"unconfigured":secret).clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC).authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).redirectUri(base+"/api/v1/auth/google/callback").scope("openid","email").authorizationUri("https://accounts.google.com/o/oauth2/v2/auth").tokenUri("https://oauth2.googleapis.com/token").jwkSetUri("https://www.googleapis.com/oauth2/v3/certs").issuerUri("https://accounts.google.com").userInfoUri("https://openidconnect.googleapis.com/v1/userinfo").userNameAttributeName("sub").clientName("Google").build());
    }
    @Bean SecurityFilterChain security(HttpSecurity http,ClientRegistrationRepository clients,OAuthCorrelationRepository correlation,ActorService actors,LoginService login,JdbcTemplate db,DefaultCookieSerializer cookies,JdbcIndexedSessionRepository sessions,@Value("${app.google-client-id:}") String clientId,@Value("${app.google-client-secret:}") String secret,@Value("${app.google-hosted-domain:mfu.ac.th}") String hd)throws Exception{
        var resolver=new DefaultOAuth2AuthorizationRequestResolver(clients,"/unused-oauth-entry");resolver.setAuthorizationRequestCustomizer(builder->{OAuth2AuthorizationRequestCustomizers.withPkce().accept(builder);builder.additionalParameters(p->p.put("hd",hd));});
        OAuth2AuthorizationRequestResolver custom=new OAuth2AuthorizationRequestResolver(){public OAuth2AuthorizationRequest resolve(HttpServletRequest request){return request.getRequestURI().equals("/api/v1/auth/google/start")?resolver.resolve(request,"google"):null;}public OAuth2AuthorizationRequest resolve(HttpServletRequest request,String registration){return resolve(request);}};
        var csrf=new HttpSessionCsrfTokenRepository();csrf.setHeaderName("X-CSRF-TOKEN");
        http.securityContext(c->c.securityContextRepository(new NullSecurityContextRepository())).sessionManagement(c->c.disable()).requestCache(c->c.disable()).csrf(c->c.csrfTokenRepository(csrf).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())).logout(c->c.disable());
        http.authorizeHttpRequests(c->c.requestMatchers("/actuator/health","/api/v1/health","/api/v1/auth/google/start","/api/v1/auth/google/callback").permitAll().anyRequest().authenticated());
        http.exceptionHandling(c->c.authenticationEntryPoint((req,res,e)->json(res,401,"UNAUTHENTICATED")).accessDeniedHandler((req,res,e)->json(res,403,"FORBIDDEN")));
        http.oauth2Login(o->o.authorizationEndpoint(e->e.authorizationRequestResolver(custom).authorizationRequestRepository(correlation)).redirectionEndpoint(e->e.baseUri("/api/v1/auth/google/callback")).authorizedClientRepository(new OAuth2AuthorizedClientRepository(){public <T extends org.springframework.security.oauth2.client.OAuth2AuthorizedClient>T loadAuthorizedClient(String id,org.springframework.security.core.Authentication a,HttpServletRequest r){return null;}public void saveAuthorizedClient(org.springframework.security.oauth2.client.OAuth2AuthorizedClient c,org.springframework.security.core.Authentication a,HttpServletRequest r,HttpServletResponse s){}public void removeAuthorizedClient(String id,org.springframework.security.core.Authentication a,HttpServletRequest r,HttpServletResponse s){}}).successHandler((req,res,authentication)->{
            try{var session=login.finalizeLogin((OidcUser)authentication.getPrincipal(),cookies.readCookieValues(req));cookies.writeCookieValue(new CookieSerializer.CookieValue(req,res,session.getId()));res.sendRedirect("/");}catch(th.ac.mfu.doctracking.common.ApiException e){SecurityContextHolder.clearContext();json(res,e.status(),e.code());}catch(Exception e){SecurityContextHolder.clearContext();json(res,503,"LOGIN_NOT_COMPLETED");}
        }).failureHandler((req,res,e)->json(res,403,"LOGIN_DENIED")));
        http.addFilterBefore(new OncePerRequestFilter(){@Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{
            res.setHeader("Cache-Control","no-store");String path=req.getRequestURI();
            if(path.equals("/api/v1/auth/google/start")||path.equals("/api/v1/auth/google/callback")){
                if(clientId.isBlank()||secret.isBlank()){json(res,503,"GOOGLE_NOT_CONFIGURED");return;}
                String bucket;
                try{bucket=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest((req.getRemoteAddr()+path).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new ServletException(e);}
                int count=db.queryForObject("insert into auth_rate_limits(bucket,started_at,attempts) values (?,now(),1) on conflict(bucket) do update set attempts=case when auth_rate_limits.started_at<now()-interval '1 minute' then 1 else auth_rate_limits.attempts+1 end,started_at=case when auth_rate_limits.started_at<now()-interval '1 minute' then now() else auth_rate_limits.started_at end returning attempts",Integer.class,bucket);
                if(count>20){res.setHeader("Retry-After","60");json(res,429,"RATE_LIMITED");return;}
            }
            chain.doFilter(req,res);
        }},OAuth2AuthorizationRequestRedirectFilter.class);
        http.addFilterBefore(new OncePerRequestFilter(){@Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{
            if(req.getSession(false)!=null && req.getSession(false).getAttribute("uid")!=null){try{Actor a=actors.current();SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(a,null,List.of()));}catch(th.ac.mfu.doctracking.common.ApiException e){json(res,401,"SESSION_REVOKED");return;}}
            chain.doFilter(req,res);
        }},AnonymousAuthenticationFilter.class);
        return http.build();
    }
    static void json(HttpServletResponse res,int status,String code)throws IOException{res.setStatus(status);res.setContentType("application/json");res.getWriter().write("{\"code\":\""+code+"\",\"message\":\"Request could not be completed\",\"correlation_id\":\""+UUID.randomUUID()+"\"}");}
}
