package com.ddagtech.aureliabooks.security;

import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.service.AccountSecurityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.*;
import org.springframework.security.oauth2.core.user.OAuth2User;
import java.time.Instant;
import jakarta.servlet.http.HttpSession;

/** Explicit session-bound intent, consumed only after server-validated Google claims. */
@Component @RequiredArgsConstructor
public class GoogleAccountAction {
    private final AccountSecurityService security;
    public record Intent(Long owner,String purpose,Instant expires){}
    public static void begin(HttpSession session,Long owner,String purpose){session.removeAttribute("GOOGLE_REAUTH_OWNER");session.removeAttribute("GOOGLE_REAUTH_UNTIL");session.setAttribute("GOOGLE_ACCOUNT_ACTION",new Intent(owner,purpose,Instant.now().plusSeconds(300)));}
    public User resolve(OAuth2User claims){
        if(!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs))return null;
        var request=attrs.getRequest();var session=request.getSession(false);if(session==null)return null;
        Object action=session.getAttribute("GOOGLE_ACCOUNT_ACTION");if(!(action instanceof Intent intent))return null;
        session.removeAttribute("GOOGLE_ACCOUNT_ACTION");
        if(!Instant.now().isBefore(intent.expires()))throw new IllegalArgumentException("Yêu cầu Google đã hết hạn.");
        if(!"REAUTH".equals(intent.purpose()))throw new IllegalArgumentException("Thao tác Google không còn được hỗ trợ.");
        if(!Boolean.TRUE.equals(claims.getAttribute("email_verified")))throw new IllegalArgumentException("Email Google chưa xác minh.");
        String sub=claims.getAttribute("sub");var owner=security.customer(intent.owner());
        if(sub==null||!sub.equals(owner.getProviderId()))throw new IllegalArgumentException("Hãy chọn đúng Google đã liên kết với tài khoản này.");
        session.setAttribute("GOOGLE_REAUTH_OWNER",owner.getId());session.setAttribute("GOOGLE_REAUTH_UNTIL",Instant.now().plusSeconds(300));
        session.setAttribute("GOOGLE_PROFILE_ACTION_DONE","REAUTH");
        return owner;
    }
}
