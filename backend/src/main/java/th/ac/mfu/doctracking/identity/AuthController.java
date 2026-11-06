package th.ac.mfu.doctracking.identity;

import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/auth")
public class AuthController {
    private final ActorService actors;private final IdentityService ids;
    public AuthController(ActorService actors,IdentityService ids){this.actors=actors;this.ids=ids;}
    @GetMapping("/me") Object me(){Actor a=actors.current();var user=ids.user(a.id());user.put("station_ids",a.stationIds());user.put("capabilities",a.capabilities());return user;}
    @GetMapping("/csrf") Object csrf(HttpServletRequest req){actors.current();var token=(CsrfToken)req.getAttribute(CsrfToken.class.getName());return Map.of("header_name",token.getHeaderName(),"token",token.getToken());}
    @PostMapping("/logout") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT) void logout(HttpServletRequest req,HttpServletResponse res){actors.current();req.getSession(false).invalidate();res.addHeader("Set-Cookie","SESSION=; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=0");}
}
