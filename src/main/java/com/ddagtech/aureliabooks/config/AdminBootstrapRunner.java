package com.ddagtech.aureliabooks.config;

import com.ddagtech.aureliabooks.dto.request.BootstrapAdminRequest;
import com.ddagtech.aureliabooks.service.AdminBootstrapService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.bootstrap-admin", name = "enabled", havingValue = "true")
public class AdminBootstrapRunner implements ApplicationRunner {

    private final AdminBootstrapService service;
    private final String email;
    private final String password;
    private final String phone;

    public AdminBootstrapRunner(AdminBootstrapService service,
            @Value("${app.bootstrap-admin.email:}") String email,
            @Value("${app.bootstrap-admin.password:}") String password,
            @Value("${app.bootstrap-admin.phone:}") String phone) {
        this.service = service;
        this.email = email;
        this.password = password;
        this.phone = phone;
    }

    @Override
    public void run(ApplicationArguments args) {
        service.initialize(new BootstrapAdminRequest(email, password, phone.isBlank() ? null : phone.strip()));
    }
}
