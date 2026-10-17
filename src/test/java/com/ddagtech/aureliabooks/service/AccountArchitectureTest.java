package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.entity.Role;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.repository.RoleRepository;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.security.LoginAttemptService;
import com.ddagtech.aureliabooks.service.impl.*;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccountArchitectureTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(AccountBeans.class)
            .withBean(UserRepository.class, () -> mock(UserRepository.class))
            .withBean(RoleRepository.class, () -> mock(RoleRepository.class))
            .withBean(Validator.class, () -> mock(Validator.class))
            .withBean(PasswordEncoder.class, () -> mock(PasswordEncoder.class))
            .withBean(LoginAttemptService.class, () -> mock(LoginAttemptService.class))
            .withBean(SessionRegistry.class, () -> mock(SessionRegistry.class))
            .withBean(AuditLogService.class, () -> mock(AuditLogService.class))
            .withBean(ContactOtpDelivery.class, () -> mock(ContactOtpDelivery.class));

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import({RegistrationServiceImpl.class, AccountOtpServiceImpl.class,
        AccountSecurityServiceImpl.class, ContactChangeServiceImpl.class,
        ContactChangeWriterImpl.class, AccountPresentationServiceImpl.class, AvatarStorageImpl.class})
    static class AccountBeans {

        @Bean
        RecordingTransactions transactionManager() {
            return new RecordingTransactions();
        }
    }

    static class RecordingTransactions extends AbstractPlatformTransactionManager {

        final List<String> events = new ArrayList<>();

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            events.add("begin");
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            events.add("commit");
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            events.add("rollback");
        }
    }

    @Test
    void contractsResolveToUniqueBeansAndTransactionalWritersRemainProxied() {
        context.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            for (Class<?> contract : List.of(RegistrationService.class, AccountOtpService.class,
                    AccountSecurityService.class, ContactChangeService.class,
                    ContactChangeWriter.class, AccountPresentationService.class, AvatarStorage.class)) {
                assertThat(contract.isInterface()).isTrue();
                assertThat(ctx.getBeansOfType(contract)).hasSize(1);
            }
            assertThat(ctx.getBean(AccountOtpService.class)).isSameAs(ctx.getBean(AccountOtpService.class));
            assertThat(AopUtils.isAopProxy(ctx.getBean(ContactChangeWriter.class))).isTrue();
            assertThat(AopUtils.isAopProxy(ctx.getBean(AccountSecurityService.class))).isTrue();
            assertThat(AopUtils.isAopProxy(ctx.getBean(RegistrationService.class))).isTrue();
        });
    }

    @Test
    void contactWriterCommitsAndAuditsInsideTransaction() {
        context.run(ctx -> {
            User user = customer();
            when(ctx.getBean(UserRepository.class).findOwnerForUpdate(7L)).thenReturn(Optional.of(user));
            doAnswer(invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                return null;
            }).when(ctx.getBean(AuditLogService.class)).record(any(), any(), any(), any(), any(), any());
            ctx.getBean(ContactChangeWriter.class).apply(7L, null, "0912345678", "EMAIL", "new@example.test", "ip");
            assertThat(ctx.getBean(RecordingTransactions.class).events).containsExactly("begin", "commit");
            assertThat(user.isEmailVerified()).isTrue();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        });
    }

    @Test
    void contactWriterRollsBackWhenAuditFails() {
        context.run(ctx -> {
            when(ctx.getBean(UserRepository.class).findOwnerForUpdate(7L)).thenReturn(Optional.of(customer()));
            doThrow(new IllegalStateException("audit unavailable"))
                    .when(ctx.getBean(AuditLogService.class)).record(any(), any(), any(), any(), any(), any());
            assertThatThrownBy(() -> ctx.getBean(ContactChangeWriter.class)
                    .apply(7L, null, "0912345678", "EMAIL", "new@example.test", "ip"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(ctx.getBean(RecordingTransactions.class).events).containsExactly("begin", "rollback");
        });
    }

    private User customer() {
        return User.builder().id(7L).phone("0912345678")
                .role(Role.builder().roleName("ROLE_CUSTOMER").build()).build();
    }
}
