package com.ddagtech.aureliabooks.dto.request;

import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.time.*;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

class RefundBusinessDateValidationTest {
    static Stream<Arguments> midnightClocks() {
        return Stream.of("UTC", "Asia/Ho_Chi_Minh").flatMap(zone ->
                Stream.of("2026-10-29T16:59:59Z", "2026-10-29T17:00:00Z",
                                "2026-10-29T18:00:00Z", "2026-10-29T23:59:59Z",
                                "2026-10-30T00:00:00Z")
                        .map(instant -> Arguments.of(Clock.fixed(Instant.parse(instant), ZoneId.of(zone)))));
    }

    @ParameterizedTest
    @MethodSource("midnightClocks")
    void refundUsesTheFormBusinessDayRegardlessOfTheValidatorZone(Clock clock) {
        ZoneId vietnam = ZoneId.of("Asia/Ho_Chi_Minh");
        try (var clocks = mockStatic(Clock.class, CALLS_REAL_METHODS);
             var factory = Validation.byDefaultProvider().configure().clockProvider(() -> clock)
                .buildValidatorFactory()) {
            clocks.when(() -> Clock.system(vietnam)).thenReturn(clock.withZone(vietnam));
            var validator = factory.getValidator();
            LocalDate formDay = clock.instant().atZone(vietnam).toLocalDate();
            assertThat(validator.validate(request(formDay))).isEmpty();
            assertThat(validator.validate(request(formDay.minusDays(1)))).isEmpty();
            assertThat(validator.validate(request(formDay.plusDays(1))))
                    .extracting(v -> v.getPropertyPath().toString()).containsExactly("completedDateValid");
            assertThat(validator.validate(request(null)))
                    .extracting(v -> v.getPropertyPath().toString()).containsExactly("completedDate");

            // The DTO's Vietnam-day check must not change unrelated validators' clock policy.
            LocalDate validatorDay = LocalDate.now(clock);
            assertThat(validator.validateValue(ProfileUpdateRequest.class, "dob", validatorDay)).isNotEmpty();
            assertThat(validator.validateValue(ProfileUpdateRequest.class, "dob", validatorDay.minusDays(1))).isEmpty();
        }
    }

    private RefundConfirmationRequest request(LocalDate date) {
        return new RefundConfirmationRequest(new BigDecimal("100000"), "Chuyển khoản", date, "Receipt 123", true);
    }
}
