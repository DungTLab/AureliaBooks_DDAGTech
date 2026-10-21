package com.ddagtech.aureliabooks.config;

import com.ddagtech.aureliabooks.service.AdminBootstrapService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class AdminBootstrapRunnerTest {

    private final AdminBootstrapService service = mock(AdminBootstrapService.class);
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(AdminBootstrapRunner.class)
            .withBean(AdminBootstrapService.class, () -> service);

    @Test
    void disabledByDefault() {
        context.run(c -> assertThat(c).doesNotHaveBean(AdminBootstrapRunner.class));
        verifyNoInteractions(service);
    }

    @Test
    void optInLoadsDeploymentPropertiesAndNormalizesEmptyPhone() {
        context.withPropertyValues("app.bootstrap-admin.enabled=true",
                "app.bootstrap-admin.email=owner@example.test", "app.bootstrap-admin.password=Bootstrap391@")
                .run(c -> c.getBean(AdminBootstrapRunner.class).run(new DefaultApplicationArguments()));
        verify(service).initialize(argThat(r -> "owner@example.test".equals(r.email())
                && "Bootstrap391@".equals(r.password()) && r.phone() == null));
    }
}
