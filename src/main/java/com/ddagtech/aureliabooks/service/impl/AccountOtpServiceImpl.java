package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.dto.request.*;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.security.CustomerIdentity;
import com.ddagtech.aureliabooks.security.LoginAttemptService;
import com.ddagtech.aureliabooks.service.*;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Single JVM OTP store, shared registration/recovery UI. No raw credentials retained.
 */
@Service
@RequiredArgsConstructor
public class AccountOtpServiceImpl implements AccountOtpService {

    private final UserRepository users;
    private final Validator validator;
    private final PasswordEncoder passwords;
    private final ContactOtpDelivery delivery;
    private final AccountSecurityService writer;
    private final LoginAttemptService attempts;
    private final SecureRandom random = new SecureRandom();
    private final byte[] key = new byte[32];
    private final Map<String, Flow> flows = new HashMap<>();
    private final Map<String, Counter> limits = new HashMap<>();

    {
        random.nextBytes(key);
    }

    private static final String OTP_SENT = "Mã xác thực đã được gửi. Vui lòng kiểm tra email hoặc SMS của bạn.";

    static class Flow {

        String id, session, kind, channel, target, oldHash;
        Long owner;
        AccountSecurityService.Draft draft;
        byte[] mac;
        Instant expires, sent, authorized;
        int failures;
        String googleSubject, googleEmail;
        Instant googleProofExpires;
    }

    static class Counter {

        int count;
        Instant until, lastSent;

        Counter(Instant until) {
            this.until = until;
        }
    }

    public synchronized String registration(RegisterRequest r, String session, String ip) {
        if (!validator.validate(r).isEmpty()) throw new IllegalArgumentException("Thông tin đăng ký không hợp lệ.");
        if (users.existsByPhone(r.phone())) throw new IllegalArgumentException("Số điện thoại đã được đăng ký.");
        var f = new Flow();
        f.kind = "REGISTER";
        f.channel = "SMS";
        f.target = r.phone();
        f.draft = new AccountSecurityService.Draft(
            r.phone(),
            r.fullName(),
            r.dob(),
            r.gender(),
            passwords.encode(r.password())
        );
        install(f, session);
        issue(f, ip);
        return f.id;
    }

    public List<String> recoveryChannels(String identifier) {
        return verifiedRecoveryChannels(recoveryAccount(identifier));
    }

    private User recoveryAccount(String identifier) {
        String value = normalize(identifier);
        boolean email =
            value.contains("@") &&
            !value.isBlank() &&
            value.length() <= 100 &&
            validator.validateValue(RegisterRequest.class, "email", value).isEmpty();
        if (!email && !value.matches("0[0-9]{9}")) throw new IllegalArgumentException(
            "Vui lòng nhập email hợp lệ hoặc số điện thoại 10 chữ số bắt đầu bằng 0."
        );
        var u = users
            .findByIdentifierWithRoles(value)
            .orElseThrow(() ->
                new IllegalArgumentException("Không tìm thấy tài khoản với email hoặc số điện thoại này.")
            );
        if (!Boolean.TRUE.equals(u.getIsActive())) throw new IllegalArgumentException(
            "Tài khoản đã bị vô hiệu hóa. Vui lòng liên hệ hỗ trợ."
        );
        if (u.getPasswordHash() == null || u.getPasswordHash().isBlank()) throw new IllegalArgumentException(
            "Tài khoản chỉ đăng nhập bằng Google. Vui lòng khôi phục quyền truy cập tại Google."
        );
        return u;
    }

    private List<String> verifiedRecoveryChannels(User u) {
        var channels = new ArrayList<String>();
        if (u.isEmailVerified() && u.getEmail() != null && !u.getEmail().isBlank()) channels.add("EMAIL");
        if (u.isPhoneVerified() && u.getPhone() != null && !u.getPhone().isBlank()) channels.add("SMS");
        if (channels.isEmpty()) throw new IllegalArgumentException(
            "Tài khoản chưa có email hoặc số điện thoại đã xác minh để khôi phục mật khẩu. Vui lòng liên hệ hỗ trợ."
        );
        return channels;
    }

    public synchronized String recovery(String identifier, String channel, String session, String ip) {
        var u = recoveryAccount(identifier);
        var channels = verifiedRecoveryChannels(u);
        String selected = channel;
        if (!channels.contains(selected)) throw new IllegalArgumentException(
            "Kênh khôi phục không hợp lệ. Vui lòng chọn email hoặc số điện thoại đã xác minh."
        );
        var f = new Flow();
        f.kind = "RESET";
        f.channel = selected;
        f.owner = u.getId();
        f.oldHash = u.getPasswordHash();
        f.target = "EMAIL".equals(selected) ? u.getEmail() : u.getPhone();
        install(f, session);
        try {
            issue(f, ip);
        } catch (RuntimeException ex) {
            flows.remove(f.id);
            throw ex;
        }
        return f.id;
    }

    public String recoveryMessage() {
        return OTP_SENT;
    }

    public synchronized View view(String id, String session) {
        var f = bound(id, session);
        return f == null
            ? null
            : new View(
                  f.kind,
                  mask(f.target),
                  f.authorized != null,
                  Math.max(0, 60 - Duration.between(f.sent, Instant.now()).getSeconds()),
                  Math.max(0, Duration.between(Instant.now(), f.expires).getSeconds())
              );
    }

    public synchronized void resend(String id, String session, String ip) {
        var f = required(id, session);
        if (f.authorized != null) throw new IllegalArgumentException("Mã đã được xác minh.");
        if (Duration.between(f.sent, Instant.now()).getSeconds() < 60) throw new IllegalArgumentException(
            "Vui lòng chờ đủ 60 giây."
        );
        issue(f, ip);
    }

    public synchronized Result verify(String id, String session, String code, String ip) {
        var f = required(id, session);
        if (!"REGISTER".equals(f.kind) && !"RESET".equals(f.kind)) {
            throw new IllegalArgumentException("Yêu cầu xác minh không hợp lệ.");
        }
        verifyCode(f, code);
        if ("REGISTER".equals(f.kind)) {
            writer.register(f.draft, ip);
            flows.remove(id);
            return new Result(true, "Đăng ký thành công. Vui lòng đăng nhập.");
        }
        f.mac = null;
        f.authorized = Instant.now().plusSeconds(300);
        return new Result(false, "Mã hợp lệ. Hãy đặt mật khẩu mới.");
    }

    private void verifyCode(Flow f, String code) {
        if (f.authorized != null) throw new IllegalArgumentException("Mã đã được sử dụng.");
        quota("verify:" + (f.owner == null ? f.target : f.owner), 10);
        if (f.mac == null || !Instant.now().isBefore(f.expires) || f.failures >= 5) throw new IllegalArgumentException(
            "Mã đã hết hạn hoặc hết lượt thử."
        );
        if (code == null || !code.matches("[0-9]{6}") || !MessageDigest.isEqual(f.mac, protect(f, code))) {
            f.failures++;
            throw new IllegalArgumentException("Mã xác thực không đúng.");
        }
    }

    @Override
    public synchronized String prepareGoogleLink(Long owner, String subject, String email, String session) {
        if (
            subject == null ||
            subject.isBlank() ||
            subject.length() > 100 ||
            email == null ||
            session == null ||
            session.isBlank()
        ) {
            throw new IllegalArgumentException("Yêu cầu Google không hợp lệ.");
        }
        var f = new Flow();
        f.kind = "GOOGLE_LINK";
        f.owner = owner;
        f.googleSubject = subject;
        f.googleEmail = email;
        if (googleChannels(googleAccount(f)).isEmpty()) {
            throw new IllegalArgumentException(
                "Tài khoản chưa có kênh đã xác minh. Vui lòng đăng nhập bằng mật khẩu, xác minh thông tin liên hệ trong hồ sơ rồi thử đăng nhập Google lại."
            );
        }
        install(f, session);
        f.googleProofExpires = Instant.now().plusSeconds(600);
        f.expires = f.googleProofExpires;
        return f.id;
    }

    @Override
    public synchronized GoogleLinkView googleLinkView(String id, String session) {
        Flow f = bound(id, session);
        if (f == null || !"GOOGLE_LINK".equals(f.kind) || !Instant.now().isBefore(f.googleProofExpires)) {
            return null;
        }
        var channels = googleChannels(googleAccount(f));
        if (channels.isEmpty()) {
            throw new IllegalArgumentException(
                "Tài khoản không còn kênh đã xác minh. Vui lòng đăng nhập bằng mật khẩu."
            );
        }
        return new GoogleLinkView(
            channels,
            f.target == null ? "" : mask(f.target),
            f.mac != null,
            Math.max(0, 60 - Duration.between(f.sent, Instant.now()).getSeconds()),
            Math.max(0, Duration.between(Instant.now(), f.expires).getSeconds())
        );
    }

    @Override
    public synchronized void sendGoogleLink(String id, String session, String channel, String ip) {
        Flow f = googleFlow(id, session);
        User user = googleAccount(f);
        var channels = googleChannels(user);
        String selected = channels.size() == 1 ? channels.getFirst() : channel;
        if (selected == null || !channels.contains(selected)) {
            throw new IllegalArgumentException("Vui lòng chọn email hoặc số điện thoại đã xác minh.");
        }
        if (Duration.between(f.sent, Instant.now()).getSeconds() < 60) {
            throw new IllegalArgumentException("Vui lòng chờ đủ 60 giây trước khi gửi lại mã.");
        }
        f.channel = selected;
        f.target = "EMAIL".equals(selected) ? user.getEmail() : user.getPhone();
        issue(f, ip);
        if (f.expires.isAfter(f.googleProofExpires)) {
            f.expires = f.googleProofExpires;
        }
    }

    @Override
    public synchronized User verifyGoogleLink(String id, String session, String code, String ip) {
        Flow f = googleFlow(id, session);
        return attempts.authenticate(f.owner, () -> {
            verifyCode(f, code);
            try {
                return writer.completeGoogleLink(f.owner, f.googleSubject, f.googleEmail, f.channel, f.target, ip);
            } finally {
                flows.remove(id);
            }
        });
    }

    private Flow googleFlow(String id, String session) {
        Flow f = required(id, session);
        if (!"GOOGLE_LINK".equals(f.kind) || !Instant.now().isBefore(f.googleProofExpires)) {
            throw new IllegalArgumentException("Yêu cầu Google đã hết hạn. Vui lòng bắt đầu lại.");
        }
        return f;
    }

    private User googleAccount(Flow f) {
        User user = users
            .findByIdentifierWithRoles(f.googleEmail)
            .orElseThrow(() -> new IllegalArgumentException("Thông tin tài khoản đã thay đổi. Vui lòng bắt đầu lại."));
        CustomerIdentity.requireCustomer(user);
        if (
            !user.getId().equals(f.owner) ||
            !f.googleEmail.equalsIgnoreCase(user.getEmail()) ||
            (user.getProviderId() != null && !user.getProviderId().equals(f.googleSubject))
        ) {
            throw new IllegalArgumentException("Thông tin liên kết đã thay đổi. Vui lòng bắt đầu lại.");
        }
        return user;
    }

    private List<String> googleChannels(User user) {
        var channels = new ArrayList<String>();
        if (user.isEmailVerified() && user.getEmail() != null && !user.getEmail().isBlank()) {
            channels.add("EMAIL");
        }
        if (user.isPhoneVerified() && user.getPhone() != null && !user.getPhone().isBlank()) {
            channels.add("SMS");
        }
        return List.copyOf(channels);
    }

        @Override
    public synchronized User reset(String id, String session, String password, String confirmation, String ip) {
        var f = required(id, session);
        if (
            !"RESET".equals(f.kind) || f.authorized == null || !Instant.now().isBefore(f.authorized)
        ) throw new IllegalArgumentException("Vui lòng xác minh mã trước khi đặt mật khẩu.");
        var r = new PasswordChangeRequest("verified", password, confirmation);
        if (!validator.validate(r).isEmpty()) throw new IllegalArgumentException(
            "Mật khẩu từ 8 ký tự, có chữ hoa, số, ký tự đặc biệt; xác nhận phải khớp và tối đa 72 byte UTF-8."
        );
        User user = writer.reset(f.owner, f.oldHash, f.channel, f.target, passwords.encode(password), ip);
        flows.remove(id);
        return user;
    }

    private void install(Flow f, String session) {
        flows
            .entrySet()
            .removeIf(
                e ->
                    (e.getValue().expires.isBefore(Instant.now()) && e.getValue().authorized == null) ||
                    (e.getValue().authorized != null && !Instant.now().isBefore(e.getValue().authorized))
            );
        if (flows.size() >= 5000) throw new IllegalArgumentException("Hệ thống đang bận.");
        f.id = UUID.randomUUID().toString();
        f.session = session;
        f.expires = Instant.now().plusSeconds(300);
        f.sent = Instant.EPOCH;
        flows.put(f.id, f);
    }

    private void issue(Flow f, String ip) {
        String targetKey = "target:" + f.target;
        var previousSend = limits.get(targetKey);
        if (
            previousSend != null &&
            previousSend.lastSent != null &&
            Duration.between(previousSend.lastSent, Instant.now()).getSeconds() < 60
        ) throw new IllegalArgumentException("Vui lòng chờ đủ 60 giây trước khi nhận mã khác.");
        quota(targetKey, 5);
        quota("ip:" + ip, 20);
        if (f.owner != null) quota("owner:" + f.owner, 5);
        limits.get(targetKey).lastSent = Instant.now();
        String code;
        byte[] previous = f.mac;
        do {
            code = String.format(Locale.ROOT, "%06d", random.nextInt(1000000));
        } while (previous != null && MessageDigest.isEqual(previous, protect(f, code)));
        f.mac = null;
        f.sent = Instant.now();
        f.expires = f.sent.plusSeconds(300);
        f.failures = 0;
        try {
            delivery.send(f.channel, f.target, code);
            f.mac = protect(f, code);
            flows
                .entrySet()
                .removeIf(
                    e ->
                        !e.getKey().equals(f.id) &&
                        f.kind.equals(e.getValue().kind) &&
                        (Objects.equals(f.target, e.getValue().target) ||
                            (f.owner != null && f.owner.equals(e.getValue().owner)))
                );
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Không gửi được mã. Vui lòng kiểm tra cấu hình email/SMS.");
        }
    }

    private void quota(String id, int max) {
        var now = Instant.now();
        limits.entrySet().removeIf(e -> !now.isBefore(e.getValue().until));
        if (limits.size() >= 10000 && !limits.containsKey(id)) throw new IllegalArgumentException("Hệ thống đang bận.");
        var c = limits.computeIfAbsent(id, k -> new Counter(now.plusSeconds(900)));
        if (c.count >= max) throw new IllegalArgumentException("Quá nhiều yêu cầu. Vui lòng thử lại sau 15 phút.");
        c.count++;
    }

    private Flow bound(String id, String session) {
        var f = flows.get(id);
        return f != null && f.session.equals(session) ? f : null;
    }

    private Flow required(String id, String session) {
        var f = bound(id, session);
        if (f == null) throw new IllegalArgumentException("Yêu cầu không còn hiệu lực.");
        return f;
    }

    private byte[] protect(Flow f, String code) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal((f.id + "|" + f.kind + "|" + f.target + "|" + code).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    private static String mask(String value) {
        int at = value.indexOf('@');
        return at > 0
            ? value.charAt(0) + "***" + value.substring(at)
            : "******" + value.substring(Math.max(0, value.length() - 4));
    }
}
