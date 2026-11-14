package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.request.StaffContactUpdateRequest;
import com.ddagtech.aureliabooks.dto.request.UserCreateRequest;
import com.ddagtech.aureliabooks.entity.Role;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.mapper.AdminUserMapper;
import com.ddagtech.aureliabooks.repository.RoleRepository;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.security.LoginAttemptService;
import com.ddagtech.aureliabooks.service.impl.AccountOtpServiceImpl;
import com.ddagtech.aureliabooks.service.impl.AccountSecurityServiceImpl;
import com.ddagtech.aureliabooks.service.impl.AdminUserServiceImpl;
import com.ddagtech.aureliabooks.service.impl.StaffContactServiceImpl;
import com.ddagtech.aureliabooks.service.impl.StaffContactWriterImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end integration test wiring real UC28 provision, contact update, re-authentication, and
 * self-OTP email verification services together against an in-memory user store.
 */
class StaffEmailRecoveryEndToEndTest {

    private record RecordedAudit(Long actorId, String action, Long targetId, String json) {
    }

    private ValidatorFactory validatorFactory;
    private Map<Long, User> userStore;
    private List<RecordedAudit> auditRecords;
    private AtomicReference<String> lastOtpCode;
    private AtomicReference<String> lastInitialMailBody;

    private AdminUserServiceImpl adminUserService;
    private StaffContactServiceImpl staffContactService;
    private AccountOtpServiceImpl accountOtpService;
    private AccountSecurityServiceImpl accountSecurityService;
    private PasswordEncoder passwordEncoder;

    private Role adminRole;
    private Role saleStaffRole;
    private User adminUser;

    @BeforeEach
    void setUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        userStore = new ConcurrentHashMap<>();
        auditRecords = new ArrayList<>();
        lastOtpCode = new AtomicReference<>();
        lastInitialMailBody = new AtomicReference<>();
        AtomicLong idGenerator = new AtomicLong(100L);

        adminRole = Role.builder().id(1L).roleName("ROLE_ADMIN").build();
        Role managerRole = Role.builder().id(2L).roleName("ROLE_MANAGER").build();
        saleStaffRole = Role.builder().id(3L).roleName("ROLE_SALE_STAFF").build();

        passwordEncoder = new BCryptPasswordEncoder(4);
        adminUser = User.builder()
                .id(1L)
                .email("admin@aureliabook.vn")
                .phone("0900000001")
                .fullName("Root Admin")
                .passwordHash(passwordEncoder.encode("AdminPass@123"))
                .role(adminRole)
                .isActive(true)
                .emailVerified(false)
                .phoneVerified(false)
                .build();
        userStore.put(adminUser.getId(), adminUser);

        UserRepository userRepo = mock(UserRepository.class);
        when(userRepo.findById(any())).thenAnswer(inv -> Optional.ofNullable(userStore.get(inv.<Long>getArgument(0))));
        when(userRepo.findOwnerForUpdate(any())).thenAnswer(inv -> Optional.ofNullable(userStore.get(inv.<Long>getArgument(0))));
        when(userRepo.findByIdentifierWithRoles(anyString())).thenAnswer(inv -> {
            String id = inv.<String>getArgument(0).trim().toLowerCase();
            return userStore.values().stream()
                    .filter(u -> u.getEmail() != null && u.getEmail().equalsIgnoreCase(id))
                    .findFirst();
        });
        when(userRepo.existsByEmail(anyString())).thenAnswer(inv -> {
            String mail = inv.<String>getArgument(0).trim().toLowerCase();
            return userStore.values().stream().anyMatch(u -> mail.equalsIgnoreCase(u.getEmail()));
        });
        when(userRepo.existsByPhone(anyString())).thenAnswer(inv -> {
            String phone = inv.getArgument(0);
            return userStore.values().stream().anyMatch(u -> phone.equals(u.getPhone()));
        });
        when(userRepo.save(any(User.class))).thenAnswer(inv -> saveToStore(inv.getArgument(0), idGenerator));
        when(userRepo.saveAndFlush(any(User.class))).thenAnswer(inv -> saveToStore(inv.getArgument(0), idGenerator));

        RoleRepository roleRepo = mock(RoleRepository.class);
        when(roleRepo.findById(1L)).thenReturn(Optional.of(adminRole));
        when(roleRepo.findById(2L)).thenReturn(Optional.of(managerRole));
        when(roleRepo.findById(3L)).thenReturn(Optional.of(saleStaffRole));

        AuditLogService auditLogService = mock(AuditLogService.class);
        doAnswer(inv -> {
            auditRecords.add(new RecordedAudit(
                    inv.getArgument(0),
                    inv.getArgument(1),
                    inv.getArgument(3),
                    inv.getArgument(4)
            ));
            return null;
        }).when(auditLogService).recordRequired(any(), anyString(), anyString(), any(), anyString(), any());

        SessionRegistry sessionRegistry = mock(SessionRegistry.class);
        when(sessionRegistry.getAllPrincipals()).thenReturn(List.of());

        JavaMailSender mailSender = mock(JavaMailSender.class);
        doAnswer(inv -> {
            SimpleMailMessage msg = inv.getArgument(0);
            lastInitialMailBody.set(msg.getText());
            return null;
        }).when(mailSender).send(any(SimpleMailMessage.class));
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> mailProvider = mock(ObjectProvider.class);
        when(mailProvider.getIfAvailable()).thenReturn(mailSender);

        ContactOtpDelivery delivery = mock(ContactOtpDelivery.class);
        doAnswer(inv -> {
            lastOtpCode.set(inv.getArgument(2));
            return null;
        }).when(delivery).send(anyString(), anyString(), anyString());

        LoginAttemptService attempts = new LoginAttemptService();
        ObjectMapper objectMapper = new ObjectMapper();

        adminUserService = new AdminUserServiceImpl(
                userRepo,
                roleRepo,
                passwordEncoder,
                sessionRegistry,
                auditLogService,
                objectMapper,
                new AdminUserMapper(),
                attempts,
                mailProvider,
                "no-reply@aureliabook.vn"
        );
        accountSecurityService = new AccountSecurityServiceImpl(
                userRepo,
                roleRepo,
                passwordEncoder,
                attempts,
                sessionRegistry,
                auditLogService
        );
        StaffContactWriterImpl staffContactWriter = new StaffContactWriterImpl(
                userRepo,
                sessionRegistry,
                auditLogService,
                objectMapper
        );
        staffContactService = new StaffContactServiceImpl(
                userRepo,
                delivery,
                staffContactWriter,
                Clock.fixed(Instant.parse("2026-11-13T10:00:00Z"), ZoneOffset.UTC)
        );
        accountOtpService = new AccountOtpServiceImpl(
                userRepo,
                validatorFactory.getValidator(),
                passwordEncoder,
                delivery,
                accountSecurityService,
                attempts
        );
    }

    @AfterEach
    void tearDown() {
        validatorFactory.close();
    }

    private User saveToStore(User user, AtomicLong idGenerator) {
        if (user.getId() == null) {
            user.setId(idGenerator.incrementAndGet());
        }
        userStore.put(user.getId(), user);
        return user;
    }

    @Test
    @DisplayName("UC28 provision-to-verification lifecycle: provision unverified -> phone edit keeps unverified -> self-OTP captures verified_email -> customer OTP stays blocked -> email change & revoke reset verification")
    void fullStaffSelfVerificationLifecycle() {
        // 1. Admin provisions new SALE_STAFF: starts unverified and remains excluded from Customer OTP recovery
        adminUserService.create(
                adminUser.getId(),
                new UserCreateRequest("staff.one@aureliabook.vn", "Nhan Vien Mot", "0912345678", 3L),
                "127.0.0.1"
        );
        User staff = userStore.values().stream()
                .filter(u -> "staff.one@aureliabook.vn".equals(u.getEmail()))
                .findFirst()
                .orElseThrow();
        assertThat(staff.isEmailVerified()).isFalse();
        assertThat(staff.isPhoneVerified()).isFalse();
        assertThat(lastInitialMailBody.get()).isNotBlank();

        assertThatThrownBy(() -> accountOtpService.recovery("staff.one@aureliabook.vn", "EMAIL", "rec-sess-0", "127.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class);

        // 2. Admin updates ONLY phone -> emailVerified stays false and audit does NOT assert email_verified:true
        adminUserService.updateStaffContact(
                adminUser.getId(),
                staff.getId(),
                new StaffContactUpdateRequest(null, "0988111222", true, "Changed phone"),
                "127.0.0.1"
        );
        assertThat(staff.getPhone()).isEqualTo("0988111222");
        assertThat(staff.isEmailVerified()).isFalse();
        assertThat(auditRecords.getLast().json()).doesNotContain("\"email_verified\":true");

        // 3. Staff logs in, re-authenticates, completes self-OTP verification -> audit captures verified_email
        String tempHash = passwordEncoder.encode("TempPass@2026");
        staff.setPasswordHash(tempHash);
        accountSecurityService.reauthenticateStaff(staff.getId(), "TempPass@2026");
        staffContactService.startSelfEmailVerification(staff.getId(), "staff-sess", "staff.verified@aureliabook.vn", "127.0.0.1");
        User verifiedStaff = staffContactService.verifySelfEmail(staff.getId(), "staff-sess", lastOtpCode.get(), "127.0.0.1");

        assertThat(verifiedStaff.isEmailVerified()).isTrue();
        assertThat(verifiedStaff.getEmail()).isEqualTo("staff.verified@aureliabook.vn");
        RecordedAudit verifyAudit = auditRecords.getLast();
        assertThat(verifyAudit.action()).isEqualTo("INTERNAL_EMAIL_VERIFIED");
        assertThat(verifyAudit.json()).contains("\"verified_email\":\"staff.verified@aureliabook.vn\"");

        // Internal staff with verified email remains blocked from Customer recovery endpoint
        assertThatThrownBy(() -> accountOtpService.recovery("staff.verified@aureliabook.vn", "EMAIL", "rec-sess-1", "127.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class);

        // 4. Admin replaces staff email -> resets verification immediately
        adminUserService.updateStaffContact(
                adminUser.getId(),
                staff.getId(),
                new StaffContactUpdateRequest("staff.reset@aureliabook.vn", null, true, "Replaced compromised mailbox"),
                "127.0.0.1"
        );
        assertThat(staff.isEmailVerified()).isFalse();

        // 5. Staff self-verifies again, then Admin revokes verification -> resets emailVerified to false
        staffContactService.startSelfEmailVerification(staff.getId(), "staff-sess-2", "staff.reset@aureliabook.vn", "127.0.0.1");
        staffContactService.verifySelfEmail(staff.getId(), "staff-sess-2", lastOtpCode.get(), "127.0.0.1");
        assertThat(staff.isEmailVerified()).isTrue();

        adminUserService.revokeEmailVerification(adminUser.getId(), staff.getId(), "127.0.0.1");
        assertThat(staff.isEmailVerified()).isFalse();
    }
}
