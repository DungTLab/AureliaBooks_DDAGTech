package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.service.*;

import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.dto.response.ContactSettingsView;
import com.ddagtech.aureliabooks.mapper.UserMapper;
import com.ddagtech.aureliabooks.repository.*;
import com.ddagtech.aureliabooks.security.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import java.time.LocalDate;
import java.util.Objects;

/** Transaction boundaries for OTP registration, recovery and explicit Google linking. */
@Service @RequiredArgsConstructor
public class AccountSecurityServiceImpl implements AccountSecurityService {
    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwords;
    private final LoginAttemptService attempts;
    private final SessionRegistry sessions;
    private final AuditLogService audit;
    @Override
    @Transactional(readOnly = true)
    public ContactSettingsView contactSettings(Long id) {
        return UserMapper.toContactSettings(customer(id));
    }

    public User customer(Long id){var u=users.findById(id).orElseThrow();CustomerIdentity.requireCustomer(u);return u;}
    public void reauthenticate(Long id,String password,boolean googleProof){
        var u=customer(id);
        if(u.getPasswordHash()==null){if(!googleProof)throw new IllegalArgumentException("Vui lòng xác thực lại bằng Google.");return;}
        attempts.authenticate(id,()->{if(password==null||!passwords.matches(password,u.getPasswordHash()))throw new BadCredentialsException("Mật khẩu hiện tại không đúng.");return true;});
    }
    @Transactional public void register(Draft draft,String ip){
        if(users.existsByPhone(draft.phone()))throw new IllegalArgumentException("Số điện thoại đã được đăng ký.");
        var role=roles.findByRoleName("ROLE_CUSTOMER").orElseThrow();
        var u=users.saveAndFlush(User.builder().phone(draft.phone()).phoneVerified(true).fullName(draft.fullName()).dob(draft.dob()).gender(draft.gender()).passwordHash(draft.passwordHash()).role(role).build());
        audit.record(u.getId(),"ACCOUNT_REGISTERED","users",u.getId(),"{\"phone_verified\":true}",ip);
    }
    @Transactional public void reset(Long id,String oldHash,String channel,String recipient,String newHash,String ip){
        var u=users.findOwnerForUpdate(id).orElseThrow();
        if(!Boolean.TRUE.equals(u.getIsActive())||u.getPasswordHash()==null||!Objects.equals(oldHash,u.getPasswordHash())||
            !("EMAIL".equals(channel)?u.isEmailVerified()&&Objects.equals(recipient,u.getEmail()):u.isPhoneVerified()&&Objects.equals(recipient,u.getPhone())))
            throw new IllegalArgumentException("Yêu cầu không còn hiệu lực. Vui lòng bắt đầu lại.");
        u.setPasswordHash(newHash);users.saveAndFlush(u);
        audit.record(id,"PASSWORD_RESET","users",id,"{}",ip);expireAfterCommit(u);
    }
    @Transactional public User linkGoogle(Long id,String sub,String mail,boolean verified,String ip){
        var u=users.findOwnerForUpdate(id).orElseThrow();CustomerIdentity.requireCustomer(u);
        if(!verified||sub==null||sub.isBlank()||sub.length()>100||mail==null||mail.isBlank())throw new IllegalArgumentException("Google chưa xác thực thông tin.");
        var linked=users.findByGoogleIdentity(User.AuthProvider.GOOGLE,sub);
        if(linked.isPresent()&&!linked.get().getId().equals(id))throw new IllegalArgumentException("Google này đã liên kết với tài khoản khác.");
        if(u.getProviderId()!=null&&!u.getProviderId().equals(sub))throw new IllegalArgumentException("Tài khoản đã liên kết với Google khác.");
        var emailOwner=users.findByEmail(mail);
        if(emailOwner.isPresent()&&!emailOwner.get().getId().equals(id))throw new IllegalArgumentException("Email Google đã thuộc tài khoản khác; không thể tự gộp tài khoản.");
        u.setProviderId(sub); // LOCAL plus provider_id supports both methods without changing the account/role.
        if(u.getEmail()==null){u.setEmail(mail);u.setEmailVerified(true);}
        users.saveAndFlush(u);audit.record(id,"GOOGLE_LINKED","users",id,"{}",ip);return u;
    }
    private void expireAfterCommit(User u){TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
        @Override public void afterCommit(){for(Object p:sessions.getAllPrincipals()){
            boolean same=p instanceof CustomUserDetails local&&u.getId().equals(local.getId())||u.getProviderId()!=null&&p instanceof OAuth2User google&&u.getProviderId().equals(google.getAttribute("sub"));
            if(same)sessions.getAllSessions(p,false).forEach(s->s.expireNow());
        }}
    });}
}
