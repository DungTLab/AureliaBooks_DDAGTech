package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.dto.request.PasswordChangeRequest;
import com.ddagtech.aureliabooks.dto.request.RegisterRequest;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.security.CustomerIdentity;
import com.ddagtech.aureliabooks.security.LoginAttemptService;
import com.ddagtech.aureliabooks.security.PortalSecurity;
import com.ddagtech.aureliabooks.service.AccountOtpService;
import com.ddagtech.aureliabooks.service.AccountSecurityService;
import com.ddagtech.aureliabooks.service.ContactOtpDelivery;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Session-bound OTP challenges for registration, recovery and Google linking.
 * Pending challenges and rate limits expire in memory and are lost on restart.
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

    private static final int MAX_PUBLIC_REQUESTS_PER_IP = 40;
    private static final String INTERNAL_RESET = "INTERNAL_RESET";
    private static final String OTP_SENT = "Mã xác thực đã được gửi. Vui lòng kiểm tra email hoặc SMS của bạn.";

    static class Flow {

        String id, session, kind, channel, target, oldHash;
        Long owner;
        AccountSecurityService.Draft draft;
        byte[] mac;
        Instant expires, sent, authorized;
        int failures;
        boolean sending;
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

    public String registration(RegisterRequest r, String session, String ip) {
        if (!validator.validate(r).isEmpty()) {
            throw new IllegalArgumentException("Thông tin đăng ký không hợp lệ.");
        }
        admitPublicRequest(ip);
        if (users.existsByPhone(r.phone())) {
            throw new IllegalArgumentException("Số điện thoại đã được đăng ký.");
        }
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
        return installAndSend(f, session, ip);
    }

    public List<String> recoveryChannels(String identifier, String ip) {
        admitPublicRequest(ip);
        return verifiedRecoveryChannels(recoveryAccount(identifier));
    }

    private synchronized void admitPublicRequest(String ip) {
        // Count requests before lookup, including contacts that do not belong to any account.
        quota("public-otp-ip:" + ip, MAX_PUBLIC_REQUESTS_PER_IP);
    }

    private User recoveryAccount(String identifier) {
        String value = normalize(identifier);
        boolean email =
            value.contains("@") &&
            !value.isBlank() &&
            value.length() <= 100 &&
            validator.validateValue(RegisterRequest.class, "email", value).isEmpty();
        if (!email && !value.matches("0[0-9]{9}")) {
            throw new IllegalArgumentException(
                "Vui lòng nhập email hợp lệ hoặc số điện thoại 10 chữ số bắt đầu bằng 0."
            );
        }
        var u = users
            .findByIdentifierWithRoles(value)
            .filter(AccountOtpServiceImpl::hasCustomerRole)
            .orElseThrow(() ->
                new IllegalArgumentException("Không tìm thấy tài khoản với email hoặc số điện thoại này.")
            );
        if (!Boolean.TRUE.equals(u.getIsActive())) {
            throw new IllegalArgumentException(
                "Tài khoản đã bị vô hiệu hóa. Vui lòng liên hệ hỗ trợ."
            );
        }
        if (u.getPasswordHash() == null || u.getPasswordHash().isBlank()) {
            throw new IllegalArgumentException(
                "Tài khoản chỉ đăng nhập bằng Google. Vui lòng khôi phục quyền truy cập tại Google."
            );
        }
        return u;
    }

    private static boolean hasCustomerRole(User user) {
        return user.getRole() != null && "ROLE_CUSTOMER".equals(user.getRole().getRoleName());
    }

    private void requireCurrentRecoveryAccount(Flow flow) {
        User user = users.findById(flow.owner)
            .filter(account -> INTERNAL_RESET.equals(flow.kind)
                ? hasStaffRecoveryRole(account) : hasCustomerRole(account))
            .filter(account -> Boolean.TRUE.equals(account.getIsActive()))
            .filter(account -> Objects.equals(flow.oldHash, account.getPasswordHash()))
            .orElseThrow(() -> new IllegalArgumentException("Yêu cầu không còn hiệu lực. Vui lòng bắt đầu lại."));
        boolean contactMatches = "EMAIL".equals(flow.channel)
            ? user.isEmailVerified() && Objects.equals(flow.target, user.getEmail())
            : user.isPhoneVerified() && Objects.equals(flow.target, user.getPhone());
        if (!contactMatches || (INTERNAL_RESET.equals(flow.kind) && !users.hasCurrentStaffEmailProof(user))) {
            throw new IllegalArgumentException("Yêu cầu không còn hiệu lực. Vui lòng bắt đầu lại.");
        }
    }

    private List<String> verifiedRecoveryChannels(User u) {
        var channels = new ArrayList<String>();
        if (u.isEmailVerified() && u.getEmail() != null && !u.getEmail().isBlank()) channels.add("EMAIL");
        if (u.isPhoneVerified() && u.getPhone() != null && !u.getPhone().isBlank()) channels.add("SMS");
        if (channels.isEmpty()) {
            throw new IllegalArgumentException(
                "Tài khoản chưa có email hoặc số điện thoại đã xác minh để khôi phục mật khẩu. Vui lòng liên hệ hỗ trợ."
            );
        }
        return channels;
    }

    public String recovery(String identifier, String channel, String session, String ip) {
        admitPublicRequest(ip);
        var u = recoveryAccount(identifier);
        var channels = verifiedRecoveryChannels(u);
        if (!channels.contains(channel)) {
            throw new IllegalArgumentException(
                "Kênh khôi phục không hợp lệ. Vui lòng chọn email hoặc số điện thoại đã xác minh."
            );
        }
        return startReset(u, channel, session, ip, "RESET");
    }

    @Override
    public String internalRecovery(String email, String session, String ip) {
        String value = normalize(email);
        if (!value.contains("@") || value.length() > 100
                || !validator.validateValue(RegisterRequest.class, "email", value).isEmpty()) {
            throw new IllegalArgumentException("Vui lòng nhập email hợp lệ.");
        }
        admitPublicRequest(ip);
        User user = users.findByIdentifierWithRoles(value)
            .filter(AccountOtpServiceImpl::hasStaffRecoveryRole)
            .filter(account -> Boolean.TRUE.equals(account.getIsActive()))
            .filter(account -> account.getPasswordHash() != null && !account.getPasswordHash().isBlank())
            .filter(account -> value.equals(normalize(account.getEmail())) && users.hasCurrentStaffEmailProof(account))
            .orElseThrow(() -> new IllegalArgumentException("Không thể gửi mã khôi phục."));
        return startReset(user, "EMAIL", session, ip, INTERNAL_RESET);
    }

    private static boolean hasStaffRecoveryRole(User user) {
        return user.getRole() != null && PortalSecurity.staffRecoveryRole(user.getRole().getRoleName());
    }

    private String startReset(User u, String selected, String session, String ip, String kind) {
        var f = new Flow();
        f.kind = kind;
        f.channel = selected;
        f.owner = u.getId();
        f.oldHash = u.getPasswordHash();
        f.target = "EMAIL".equals(selected) ? u.getEmail() : u.getPhone();
        return installAndSend(f, session, ip);
    }

    private String installAndSend(Flow flow, String session, String ip) {
        String code;
        synchronized (this) {
            install(flow, session);
            try {
                code = reserveSend(flow, flow.channel, flow.target, ip);
            } catch (RuntimeException ex) {
                flows.remove(flow.id);
                throw ex;
            }
        }
        sendReservedCode(flow, code, true);
        return flow.id;
    }

    public String recoveryMessage() {
        return OTP_SENT;
    }

    public synchronized View view(String id, String session, boolean internal) {
        var f = bound(id, session);
        return f == null || !matchesPortal(f, internal)
            ? null
            : new View(
                  f.kind,
                  mask(f.target),
                  f.authorized != null && (!internal || Instant.now().isBefore(f.authorized)),
                  Math.max(0, 60 - Duration.between(f.sent, Instant.now()).getSeconds()),
                  Math.max(0, Duration.between(Instant.now(), f.expires).getSeconds())
              );
    }

    public void resend(String id, String session, String ip, boolean internal) {
        Flow flow;
        String code;
        synchronized (this) {
            flow = verificationFlow(id, session, internal);
            requireResendAvailable(flow);
            admitPublicRequest(ip);
            if (!"REGISTER".equals(flow.kind)) {
                requireCurrentRecoveryAccount(flow);
            }
            code = reserveSend(flow, flow.channel, flow.target, ip);
        }
        sendReservedCode(flow, code, false);
    }

    private void requireResendAvailable(Flow flow) {
        if (flow.authorized != null) {
            throw new IllegalArgumentException("Mã đã được xác minh.");
        }
        if (flow.sending || Duration.between(flow.sent, Instant.now()).getSeconds() < 60) {
            throw new IllegalArgumentException("Vui lòng chờ đủ 60 giây.");
        }
    }

    public synchronized Result verify(String id, String session, String code, String ip, boolean internal) {
        var f = verificationFlow(id, session, internal);
        verifyCode(f, code);
        if (!"REGISTER".equals(f.kind)) {
            requireCurrentRecoveryAccount(f);
        }
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
        if (f.authorized != null) {
            throw new IllegalArgumentException("Mã đã được sử dụng.");
        }
        quota("verify:" + (f.owner == null ? f.target : f.owner), 10);
        if (f.sending || (f.mac != null && hasOtherSendingFlow(f, f.target))) {
            throw new IllegalArgumentException("Mã đang được gửi. Vui lòng thử lại.");
        }
        if (f.mac == null || !Instant.now().isBefore(f.expires) || f.failures >= 5) {
            throw new IllegalArgumentException(
                "Mã đã hết hạn hoặc hết lượt thử."
            );
        }
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
    public void sendGoogleLink(String id, String session, String channel, String ip) {
        Flow flow;
        String code;
        synchronized (this) {
            flow = googleFlow(id, session);
            requireResendAvailable(flow);
            admitPublicRequest(ip);
            User user = googleAccount(flow);
            var channels = googleChannels(user);
            String selected = channels.size() == 1 ? channels.getFirst() : channel;
            if (selected == null || !channels.contains(selected)) {
                throw new IllegalArgumentException("Vui lòng chọn email hoặc số điện thoại đã xác minh.");
            }
            String target = "EMAIL".equals(selected) ? user.getEmail() : user.getPhone();
            code = reserveSend(flow, selected, target, ip);
        }
        sendReservedCode(flow, code, false);
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
    public synchronized User reset(
        String id, String session, String password, String confirmation, String ip, boolean internal
    ) {
        var f = verificationFlow(id, session, internal);
        if (
            "REGISTER".equals(f.kind) || f.authorized == null || !Instant.now().isBefore(f.authorized)
        ) throw new IllegalArgumentException("Vui lòng xác minh mã trước khi đặt mật khẩu.");
        var r = new PasswordChangeRequest("verified", password, confirmation);
        if (!validator.validate(r).isEmpty()) throw new IllegalArgumentException(
            "Mật khẩu từ 8 ký tự, có chữ hoa, số, ký tự đặc biệt; xác nhận phải khớp và tối đa 72 byte UTF-8."
        );
        quota("public-otp-reset-ip:" + ip, MAX_PUBLIC_REQUESTS_PER_IP);
        String newHash = passwords.encode(password);
        User user = internal
            ? writer.resetStaff(f.owner, f.oldHash, f.target, newHash, ip)
            : writer.reset(f.owner, f.oldHash, f.channel, f.target, newHash, ip);
        flows.remove(id);
        return user;
    }

    private Flow verificationFlow(String id, String session, boolean internal) {
        Flow flow = required(id, session);
        if (!matchesPortal(flow, internal)) {
            throw new IllegalArgumentException("Yêu cầu xác minh không hợp lệ.");
        }
        return flow;
    }

    private static boolean matchesPortal(Flow flow, boolean internal) {
        return internal ? INTERNAL_RESET.equals(flow.kind) : "RESET".equals(flow.kind) || "REGISTER".equals(flow.kind);
    }

    private void install(Flow f, String session) {
        flows
            .entrySet()
            .removeIf(
                e -> !e.getValue().sending && (
                    (e.getValue().expires.isBefore(Instant.now()) && e.getValue().authorized == null) ||
                    (e.getValue().authorized != null && !Instant.now().isBefore(e.getValue().authorized))
                )
            );
        if (flows.size() >= 5000) {
            throw new IllegalArgumentException("Hệ thống đang bận.");
        }
        f.id = UUID.randomUUID().toString();
        f.session = session;
        f.expires = Instant.now().plusSeconds(300);
        f.sent = Instant.EPOCH;
        flows.put(f.id, f);
    }

    private String reserveSend(Flow flow, String channel, String target, String ip) {
        String targetKey = "target:" + target;
        // Share the cooldown across sessions so restarting the form cannot bypass it.
        var previousSend = limits.get(targetKey);
        if (hasOtherSendingFlow(flow, target) || (
            previousSend != null &&
            previousSend.lastSent != null &&
            Duration.between(previousSend.lastSent, Instant.now()).getSeconds() < 60
        )) {
            throw new IllegalArgumentException("Vui lòng chờ đủ 60 giây trước khi nhận mã khác.");
        }
        quota(targetKey, 5);
        quota("ip:" + ip, 20);
        if (flow.owner != null) quota("owner:" + flow.owner, 5);
        limits.get(targetKey).lastSent = Instant.now();

        flow.channel = channel;
        flow.target = target;
        String code;
        byte[] previous = flow.mac;
        do {
            code = String.format(Locale.ROOT, "%06d", random.nextInt(1000000));
        } while (previous != null && MessageDigest.isEqual(previous, protect(flow, code)));
        flow.mac = null;
        flow.sent = Instant.now();
        flow.expires = flow.sent.plusSeconds(300);
        if (flow.googleProofExpires != null && flow.expires.isAfter(flow.googleProofExpires)) {
            flow.expires = flow.googleProofExpires;
        }
        flow.failures = 0;
        flow.sending = true;
        return code;
    }

    private void sendReservedCode(Flow flow, String code, boolean removeOnFailure) {
        try {
            // A stalled provider must not hold the monitor used by other OTP flows.
            if ("REGISTER".equals(flow.kind)) {
                delivery.sendRegistrationSms(flow.target, code);
            } else {
                delivery.send(flow.channel, flow.target, code);
            }
        } catch (RuntimeException ex) {
            synchronized (this) {
                flow.sending = false;
                if (removeOnFailure) {
                    flows.remove(flow.id);
                }
            }
            throw new IllegalArgumentException("Không gửi được mã. Vui lòng kiểm tra cấu hình email/SMS.");
        }

        synchronized (this) {
            flow.mac = protect(flow, code);
            flow.sending = false;
            flows.values().removeIf(other -> replaces(flow, flow.target, other));
        }
    }

    private boolean hasOtherSendingFlow(Flow flow, String target) {
        return flows.values().stream().anyMatch(other -> other.sending && replaces(flow, target, other));
    }

    private static boolean replaces(Flow flow, String target, Flow other) {
        return other != flow && flow.kind.equals(other.kind)
            && (Objects.equals(target, other.target) || (flow.owner != null && flow.owner.equals(other.owner)));
    }

    private void quota(String id, int max) {
        var now = Instant.now();
        limits.entrySet().removeIf(e -> !now.isBefore(e.getValue().until));
        if (limits.size() >= 10000 && !limits.containsKey(id)) {
            throw new IllegalArgumentException("Hệ thống đang bận.");
        }
        var c = limits.computeIfAbsent(id, k -> new Counter(now.plusSeconds(900)));
        if (c.count >= max) {
            throw new IllegalArgumentException("Quá nhiều yêu cầu. Vui lòng thử lại sau 15 phút.");
        }
        c.count++;
    }

    private Flow bound(String id, String session) {
        var f = flows.get(id);
        return f != null && f.session.equals(session) ? f : null;
    }

    private Flow required(String id, String session) {
        var f = bound(id, session);
        if (f == null) {
            throw new IllegalArgumentException("Yêu cầu không còn hiệu lực.");
        }
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
