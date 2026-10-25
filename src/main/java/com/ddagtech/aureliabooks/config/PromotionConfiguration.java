package com.ddagtech.aureliabooks.config;

import org.springframework.context.annotation.*;

import java.time.*;

@Configuration
public class PromotionConfiguration {
    @Bean
    public Clock promotionClock() {
        return Clock.system(ZoneId.of("Asia/Ho_Chi_Minh"));
    }
}
