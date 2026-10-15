package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.request.*;
import com.ddagtech.aureliabooks.repository.UserRepository;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import java.security.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Single JVM OTP store, shared registration/recovery UI. No raw credentials retained. */
@Service @RequiredArgsConstructor
public class AccountOtpService {
    private final UserRepository users;
    private final Validator validator;
    private final PasswordEncoder passwords;
    private final ContactOtpDelivery delivery;
    private final AccountSecurityService writer;
    private final SecureRandom random=new SecureRandom();
    private final byte[] key=new byte[32];
    private final Map<String,Flow> flows=new HashMap<>();
    private final Map<String,Counter> limits=new HashMap<>();
    {random.nextBytes(key);}
    private static final String GENERIC="Nếu thông tin phù hợp và kênh đã xác minh, mã đã được gửi. Vui lòng kiểm tra email/SMS.";
    static class Flow {String id,session,kind,channel,target,oldHash;Long owner;AccountSecurityService.Draft draft;byte[] mac;Instant expires,sent,authorized;int failures;}
    static class Counter {int count;Instant until,lastSent;Counter(Instant until){this.until=until;}}
    public record View(String kind,String recipient,boolean authorized,long resendIn){}
    public record Result(boolean complete,String message){}
    public synchronized String registration(RegisterRequest r,String session,String ip){
        if(!validator.validate(r).isEmpty())throw new IllegalArgumentException("Thông tin đăng ký không hợp lệ.");
        if(users.existsByPhone(r.phone()))throw new IllegalArgumentException("Số điện thoại đã được đăng ký.");
        var f=new Flow();f.kind="REGISTER";f.channel="SMS";f.target=r.phone();f.draft=new AccountSecurityService.Draft(r.phone(),r.fullName(),r.dob(),r.gender(),passwords.encode(r.password()));
        install(f,session);issue(f,ip);return f.id;
    }
    public List<String> recoveryChannels(String identifier){
        var account=users.findByIdentifierWithRoles(normalize(identifier));
        if(account.isEmpty()||!Boolean.TRUE.equals(account.get().getIsActive())||account.get().getPasswordHash()==null)return List.of();
        var u=account.get();var channels=new ArrayList<String>();if(u.isEmailVerified()&&u.getEmail()!=null)channels.add("EMAIL");if(u.isPhoneVerified()&&u.getPhone()!=null)channels.add("SMS");return channels;
    }
    public synchronized String recovery(String identifier,String channel,String session,String ip){
        var f=new Flow();f.kind="RESET";f.channel="EMAIL".equals(channel)?"EMAIL":"SMS";f.target=normalize(identifier);
        var account=users.findByIdentifierWithRoles(f.target);
        if(account.isPresent()&&Boolean.TRUE.equals(account.get().getIsActive())&&account.get().getPasswordHash()!=null){
            var u=account.get();var channels=recoveryChannels(identifier);
            if(channels.size()==1)f.channel=channels.getFirst();
            if(channels.contains(f.channel)){f.owner=u.getId();f.oldHash=u.getPasswordHash();f.target="EMAIL".equals(f.channel)?u.getEmail():u.getPhone();}
        }
        install(f,session);
        if(f.owner!=null){try{issue(f,ip);}catch(RuntimeException ignored){f.mac=null;}}else{quota("ip:"+ip,20);f.sent=Instant.now();}
        return f.id;
    }
    public String recoveryMessage(){return GENERIC;}
    public synchronized View view(String id,String session){var f=bound(id,session);return f==null?null:new View(f.kind,"RESET".equals(f.kind)?"Email/SĐT đã đăng ký":mask(f.target),f.authorized!=null,Math.max(0,60-Duration.between(f.sent,Instant.now()).getSeconds()));}
    public synchronized void resend(String id,String session,String ip){var f=required(id,session);if(f.authorized!=null)throw new IllegalArgumentException("Mã đã được xác minh.");if(Duration.between(f.sent,Instant.now()).getSeconds()<60)throw new IllegalArgumentException("Vui lòng chờ đủ 60 giây.");if("RESET".equals(f.kind)&&f.owner==null){f.sent=Instant.now();quota("ip:"+ip,20);return;}issue(f,ip);}
    public synchronized Result verify(String id,String session,String code,String ip){
        var f=required(id,session);
        if(f.authorized!=null)throw new IllegalArgumentException("Mã đã được sử dụng.");
        quota("verify:"+(f.owner==null?f.target:f.owner),10);
        if(f.mac==null||!Instant.now().isBefore(f.expires)||f.failures>=5)throw new IllegalArgumentException("Mã đã hết hạn hoặc hết lượt thử.");
        if(code==null||!code.matches("[0-9]{6}")||!MessageDigest.isEqual(f.mac,protect(f,code))){f.failures++;throw new IllegalArgumentException("Mã xác thực không đúng.");}
        if("REGISTER".equals(f.kind)){writer.register(f.draft,ip);flows.remove(id);return new Result(true,"Đăng ký thành công. Vui lòng đăng nhập.");}
        f.mac=null;f.authorized=Instant.now().plusSeconds(300);return new Result(false,"Mã hợp lệ. Hãy đặt mật khẩu mới.");
    }
    public synchronized void reset(String id,String session,String password,String confirmation,String ip){
        var f=required(id,session);
        if(!"RESET".equals(f.kind)||f.authorized==null||!Instant.now().isBefore(f.authorized))throw new IllegalArgumentException("Vui lòng xác minh mã trước khi đặt mật khẩu.");
        var r=new PasswordChangeRequest("verified",password,confirmation);if(!validator.validate(r).isEmpty())throw new IllegalArgumentException("Mật khẩu từ 8 ký tự, có chữ hoa, số, ký tự đặc biệt; xác nhận phải khớp và tối đa 72 byte UTF-8.");
        writer.reset(f.owner,f.oldHash,f.channel,f.target,passwords.encode(password),ip);flows.remove(id);
    }
    private void install(Flow f,String session){flows.entrySet().removeIf(e->e.getValue().expires.isBefore(Instant.now())&&e.getValue().authorized==null||e.getValue().authorized!=null&&!Instant.now().isBefore(e.getValue().authorized));if(flows.size()>=5000)throw new IllegalArgumentException("Hệ thống đang bận.");f.id=UUID.randomUUID().toString();f.session=session;f.expires=Instant.now().plusSeconds(300);f.sent=Instant.EPOCH;flows.put(f.id,f);}
    private void issue(Flow f,String ip){
        String targetKey="target:"+f.target;var previousSend=limits.get(targetKey);
        if(previousSend!=null&&previousSend.lastSent!=null&&Duration.between(previousSend.lastSent,Instant.now()).getSeconds()<60)throw new IllegalArgumentException("Vui lòng chờ đủ 60 giây trước khi nhận mã khác.");
        quota(targetKey,5);quota("ip:"+ip,20);if(f.owner!=null)quota("owner:"+f.owner,5);limits.get(targetKey).lastSent=Instant.now();
        String code;byte[] previous=f.mac;do{code=String.format(Locale.ROOT,"%06d",random.nextInt(1000000));}while(previous!=null&&MessageDigest.isEqual(previous,protect(f,code)));f.mac=null;f.sent=Instant.now();f.expires=f.sent.plusSeconds(300);f.failures=0;
        try{delivery.send(f.channel,f.target,code);f.mac=protect(f,code);
            flows.entrySet().removeIf(e->!e.getKey().equals(f.id)&&f.kind.equals(e.getValue().kind)&&
                (Objects.equals(f.target,e.getValue().target)||f.owner!=null&&f.owner.equals(e.getValue().owner)));
        }catch(RuntimeException e){throw new IllegalArgumentException("Không gửi được mã. Vui lòng kiểm tra cấu hình email/SMS.");}
    }
    private void quota(String id,int max){var now=Instant.now();limits.entrySet().removeIf(e->!now.isBefore(e.getValue().until));if(limits.size()>=10000&&!limits.containsKey(id))throw new IllegalArgumentException("Hệ thống đang bận.");var c=limits.computeIfAbsent(id,k->new Counter(now.plusSeconds(900)));if(c.count>=max)throw new IllegalArgumentException("Quá nhiều yêu cầu. Vui lòng thử lại sau 15 phút.");c.count++;}
    private Flow bound(String id,String session){var f=flows.get(id);return f!=null&&f.session.equals(session)?f:null;}
    private Flow required(String id,String session){var f=bound(id,session);if(f==null)throw new IllegalArgumentException("Yêu cầu không còn hiệu lực.");return f;}
    private byte[] protect(Flow f,String code){try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal((f.id+"|"+f.kind+"|"+f.target+"|"+code).getBytes(StandardCharsets.UTF_8));}catch(GeneralSecurityException e){throw new IllegalStateException(e);}}
    private static String normalize(String value){return value==null?"":value.strip().toLowerCase(Locale.ROOT);}
    private static String mask(String value){int at=value.indexOf('@');return at>0?value.charAt(0)+"***"+value.substring(at):"******"+value.substring(Math.max(0,value.length()-4));}
}
