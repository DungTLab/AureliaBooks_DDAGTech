package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.security.CustomerIdentity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Objects;

/** The OTP service calls this separate proxy, so a returned update is committed before consuming its grant. */
@Service @RequiredArgsConstructor
public class ContactChangeWriter {
    private final UserRepository users;
    private final AuditLogService audit;
    private final org.springframework.security.core.session.SessionRegistry sessions;
    @Transactional public void apply(Long owner,String oldEmail,String oldPhone,String type,String value,String ip) {
        var u=users.findOwnerForUpdate(owner).orElseThrow(()->new AppException(ErrorCode.USER_NOT_EXISTED));
        CustomerIdentity.requireCustomer(u);
        if(!Objects.equals(oldEmail,u.getEmail())||!Objects.equals(oldPhone,u.getPhone()))
            throw new AppException(ErrorCode.INVALID_INPUT_DATA,"Thông tin tài khoản đã thay đổi. Vui lòng xác thực lại.");
        if("EMAIL".equals(type)) {
            if(!Objects.equals(u.getEmail(),value)&&users.existsByEmail(value))throw new AppException(ErrorCode.USER_EXISTED,"Email đã được tài khoản khác sử dụng.");
            u.setEmail(value);
            u.setEmailVerified(true);
        } else if("PHONE".equals(type)) {
            if(!Objects.equals(u.getPhone(),value)&&users.existsByPhone(value))throw new AppException(ErrorCode.USER_EXISTED,"Số điện thoại đã được tài khoản khác sử dụng.");
            u.setPhone(value);
            u.setPhoneVerified(true);
        } else throw new AppException(ErrorCode.INVALID_INPUT_DATA);
        users.saveAndFlush(u); // Existing UNIQUE indexes arbitrate races between different owners.
        audit.record(owner,"PROFILE_"+type+"_CHANGED","users",owner,
                "{\"field\":\""+type+"\",\"verified\":true}",ip);
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization(){
                @Override public void afterCommit(){
                    for(Object principal:sessions.getAllPrincipals()) {
                        boolean matches=principal instanceof com.ddagtech.aureliabooks.security.CustomUserDetails local&&owner.equals(local.getId())
                            ||u.getProviderId()!=null&&principal instanceof org.springframework.security.oauth2.core.user.OAuth2User google&&u.getProviderId().equals(google.getAttribute("sub"));
                        if(matches)sessions.getAllSessions(principal,false).forEach(s->s.expireNow());
                    }
                }
            });
    }
}
