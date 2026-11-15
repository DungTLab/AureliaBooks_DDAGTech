package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.dto.request.RegisterRequest;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.security.CustomerIdentity;
import com.ddagtech.aureliabooks.service.ContactChangeService;
import com.ddagtech.aureliabooks.service.ContactChangeWriter;
import com.ddagtech.aureliabooks.service.ContactOtpDelivery;
import jakarta.validation.Validator;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Scoped single-instance contact verification. Pending challenges/quotas expire in memory; no OTP is logged. */
@Service
public class ContactChangeServiceImpl implements ContactChangeService {

    private static final Duration TTL = ContactOtpEngine.TTL;
    private final UserRepository users;
    private final Validator validator;
    private final ContactOtpDelivery delivery;
    private final ContactChangeWriter writer;
    private final Clock clock;
    private final ContactOtpEngine engine;
    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();
    private final Object[] locks = new Object[64];

    static class Pending {

        String session,
            type,
            value,
            oldEmail,
            oldPhone,
            oldHash,
            channel,
            recipient,
            id,
            phase = "DESTINATION";
        byte[] mac;
        Instant expires, lastSent, authorizedUntil;
        int attempts;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ContactChangeServiceImpl(
        UserRepository users,
        Validator validator,
        ContactOtpDelivery delivery,
        ContactChangeWriter writer
    ) {
        this(users, validator, delivery, writer, Clock.systemUTC());
    }

    public ContactChangeServiceImpl(
        UserRepository users,
        Validator validator,
        ContactOtpDelivery delivery,
        ContactChangeWriter writer,
        Clock clock
    ) {
        this.users = users;
        this.validator = validator;
        this.delivery = delivery;
        this.writer = writer;
        this.clock = clock;
        this.engine = new ContactOtpEngine(clock);
        for (int i = 0; i < locks.length; i++) locks[i] = new Object();
    }

    private Object lock(Long id) {
        return locks[Math.floorMod(id.hashCode(), locks.length)];
    }

    @Override
    public List<String> reauthenticationMethods(Long owner) {
        var user = users.findById(owner).orElseThrow();
        CustomerIdentity.requireCustomer(user);
        var methods = new ArrayList<String>();
        if (user.getPasswordHash() != null && !user.getPasswordHash().isBlank()) methods.add("PASSWORD");
        if (user.isEmailVerified() && user.getEmail() != null && !user.getEmail().isBlank()) methods.add("EMAIL");
        if (user.isPhoneVerified() && user.getPhone() != null && !user.getPhone().isBlank()) methods.add("SMS");
        return List.copyOf(methods);
    }

    @Override
    public Result startReauthentication(Long owner, String session, String type, String channel, String ip) {
        synchronized (lock(owner)) {
            if (
                type == null ||
                channel == null ||
                !Set.of("EMAIL", "PHONE", "PASSWORD_SETUP").contains(type) ||
                !Set.of("EMAIL", "SMS").contains(channel) ||
                !reauthenticationMethods(owner).contains(channel)
            ) {
                return fail("Phương thức xác minh không khả dụng.");
            }
            var user = users.findById(owner).orElseThrow();
            var old = pending.get(owner);
            if (old != null && Duration.between(old.lastSent, clock.instant()).getSeconds() < 60) {
                return fail("Vui lòng chờ đủ 60 giây.");
            }
            pending.entrySet().removeIf(entry -> entry.getValue().expires.isBefore(clock.instant()));
            if (pending.size() >= 5000 && !pending.containsKey(owner)) {
                return fail("Hệ thống đang bận.");
            }
            var flow = new Pending();
            flow.session = session;
            flow.type = type;
            flow.phase = "CURRENT";
            flow.channel = channel;
            flow.recipient = "EMAIL".equals(channel) ? user.getEmail() : user.getPhone();
            flow.oldEmail = user.getEmail();
            flow.oldPhone = user.getPhone();
            flow.oldHash = user.getPasswordHash();
            flow.id = UUID.randomUUID().toString();
            flow.expires = clock.instant();
            flow.lastSent = Instant.EPOCH;
            flow.authorizedUntil = clock.instant().plus(TTL);
            pending.put(owner, flow);
            return issue(owner, flow, ip);
        }
    }

    /** Caller has reauthenticated the owner; verify the NEW destination before saving. */
    public Result startDestination(Long owner, String session, String type, String value, String ip) {
        synchronized (lock(owner)) {
            var u = users.findById(owner).orElseThrow();
            CustomerIdentity.requireCustomer(u);
            if (!Set.of("EMAIL", "PHONE").contains(type)) {
                return fail("Yêu cầu không hợp lệ.");
            }
            value = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
            if ("EMAIL".equals(type)) {
                if (
                    value.isBlank() || !validator.validateValue(RegisterRequest.class, "email", value).isEmpty()
                ) return fail("Email mới không hợp lệ.");
                if (value.equals(u.getEmail()) && u.isEmailVerified()) {
                    return fail("Email này đã được xác minh.");
                }
                if (!value.equals(u.getEmail()) && users.existsByEmail(value)) return fail(
                    "Email đã được tài khoản khác sử dụng."
                );
            } else {
                value = value.replace(" ", "").replace("-", "");
                if (value.startsWith("+84")) value = "0" + value.substring(3);
                if (!value.matches("0[0-9]{9}")) {
                    return fail("Số điện thoại mới phải gồm 10 chữ số, bắt đầu bằng 0.");
                }
                if (value.equals(u.getPhone()) && u.isPhoneVerified()) return fail(
                    "Số điện thoại này đã được xác minh."
                );
                if (!value.equals(u.getPhone()) && users.existsByPhone(value)) return fail(
                    "Số điện thoại đã được tài khoản khác sử dụng."
                );
            }
            var old = pending.get(owner);
            if (old != null && Duration.between(old.lastSent, clock.instant()).getSeconds() < 60) return fail(
                "Vui lòng chờ đủ 60 giây."
            );
            pending.entrySet().removeIf(e -> e.getValue().expires.isBefore(clock.instant()));
            if (pending.size() >= 5000 && !pending.containsKey(owner)) {
                return fail("Hệ thống đang bận.");
            }
            var p = new Pending();
            p.session = session;
            p.type = type;
            p.value = value;
            p.oldEmail = u.getEmail();
            p.oldPhone = u.getPhone();
            p.channel = "EMAIL".equals(type) ? "EMAIL" : "SMS";
            p.recipient = value;
            p.phase = "DESTINATION";
            p.id = UUID.randomUUID().toString();
            p.expires = clock.instant();
            p.lastSent = Instant.EPOCH;
            p.authorizedUntil = clock.instant().plus(TTL);
            pending.put(owner, p);
            return issue(owner, p, ip);
        }
    }

    public View view(Long owner, String session) {
        synchronized (lock(owner)) {
            Pending p = pending.get(owner);
            if (p == null || !p.session.equals(session)) {
                return null;
            }
            return new View(
                p.type,
                p.phase,
                mask(p.recipient),
                Math.max(0, Duration.between(clock.instant(), p.expires).getSeconds()),
                Math.max(0, 60 - Duration.between(p.lastSent, clock.instant()).getSeconds()),
                Math.max(0, 5 - p.attempts)
            );
        }
    }

    public Result resend(Long owner, String session, String ip) {
        synchronized (lock(owner)) {
            var p = bound(owner, session);
            if (p == null) {
                return fail("Yêu cầu không còn hiệu lực. Vui lòng bắt đầu lại.");
            }
            if (p.authorizedUntil != null && !clock.instant().isBefore(p.authorizedUntil)) {
                pending.remove(owner);
                return fail("Quyền xác thực đã hết hạn. Vui lòng bắt đầu lại.");
            }
            if (Duration.between(p.lastSent, clock.instant()).getSeconds() < 60) return fail(
                "Vui lòng chờ đủ 60 giây trước khi gửi lại."
            );
            return issue(owner, p, ip);
        }
    }

    public Result verify(Long owner, String session, String code, String ip) {
        synchronized (lock(owner)) {
            Pending p = bound(owner, session);
            if (p == null) {
                return fail("Yêu cầu không còn hiệu lực. Vui lòng bắt đầu lại.");
            }
            if (!clock.instant().isBefore(p.expires) || p.attempts >= 5 || p.mac == null) return fail(
                "Mã đã hết hạn hoặc hết lượt thử. Vui lòng gửi lại mã."
            );
            if (!quota(List.of("verify:" + owner), 10)) return fail(
                "Bạn đã thử quá nhiều lần. Vui lòng thử lại sau 15 phút."
            );
            if (code == null || !code.matches("[0-9]{6}") || !MessageDigest.isEqual(p.mac, protect(p, code))) {
                p.attempts++;
                return fail(
                    p.attempts >= 5 ? "Bạn đã nhập sai 5 lần. Vui lòng gửi lại mã." : "Mã xác thực không đúng."
                );
            }
            if ("CURRENT".equals(p.phase)) {
                var user = users.findById(owner).orElseThrow();
                CustomerIdentity.requireCustomer(user);
                if (
                    !Objects.equals(p.oldEmail, user.getEmail()) ||
                    !Objects.equals(p.oldPhone, user.getPhone()) ||
                    !Objects.equals(p.oldHash, user.getPasswordHash()) ||
                    !reauthenticationMethods(owner).contains(p.channel)
                ) {
                    pending.remove(owner);
                    return fail("Thông tin tài khoản đã thay đổi. Vui lòng xác thực lại.");
                }
                pending.remove(owner);
                return new Result(false, "Đã xác thực tài khoản.", false, false, true);
            }
            try {
                writer.apply(owner, p.oldEmail, p.oldPhone, p.type, p.value, ip, p.session);
            } catch (DataIntegrityViolationException ex) {
                pending.remove(owner);
                return fail("Email hoặc số điện thoại đã được tài khoản khác sử dụng. Vui lòng bắt đầu lại.");
            } catch (AppException ex) {
                pending.remove(owner);
                return fail(ex.getMessage());
            }
            pending.remove(owner);
            return new Result(true, "Đã cập nhật và xác minh thông tin liên hệ thành công.", false);
        }
    }

    private Pending bound(Long owner, String session) {
        Pending p = pending.get(owner);
        return p != null && p.session.equals(session) ? p : null;
    }

    private Result issue(Long owner, Pending p, String ip) {
        byte[] previous = p.mac;
        p.mac = null;
        if (
            !quota(List.of("send-user:" + owner, "send-target:" + p.recipient), 5) ||
            !quota(List.of("send-ip:" + ip), 20)
        ) return fail("Bạn đã gửi quá nhiều mã. Vui lòng thử lại sau 15 phút.");
        p.lastSent = clock.instant();
        p.expires = p.lastSent.plus(TTL);
        p.attempts = 0;
        if (p.authorizedUntil != null && p.expires.isAfter(p.authorizedUntil)) p.expires = p.authorizedUntil;
        ContactOtpEngine.OtpToken token = engine.issueCode(otpContext(p), previous);
        try {
            delivery.send(p.channel, p.recipient, token.code());
            p.mac = token.mac();
        } catch (RuntimeException ex) {
            return fail(
                "Không gửi được mã xác thực. Vui lòng kiểm tra cấu hình email/SMS hoặc thử gửi lại sau 60 giây."
            );
        }
        return new Result(
            false,
            "DESTINATION".equals(p.phase)
                ? "Đã gửi mã tới thông tin mới. Nhập mã để hoàn tất thay đổi."
                : "NEW_EMAIL".equals(p.phase)
                  ? "Đã gửi mã tới email mới. Nhập mã để hoàn tất thay đổi."
                  : "Đã gửi mã tới thông tin liên hệ hiện tại.",
            false,
            true
        );
    }

    private boolean quota(List<String> keys, int max) {
        return engine.consumeAllQuotas(keys, max);
    }

    private byte[] protect(Pending p, String code) {
        return engine.hmac(otpContext(p) + "|" + code);
    }

    private static String otpContext(Pending p) {
        return p.id + "|" + p.phase + "|" + p.recipient;
    }

    private static String mask(String value) {
        return ContactOtpEngine.maskContact(value);
    }

    private static Result fail(String message) {
        return new Result(false, message);
    }
}
