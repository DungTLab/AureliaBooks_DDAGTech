package com.ddagtech.aureliabooks.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

/** Date constraint scoped to the Vietnam business calendar; null is handled by @NotNull. */
@Documented
@Constraint(validatedBy = PastOrPresentBusinessDateValidator.class)
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
public @interface PastOrPresentBusinessDate {
    String message() default "Ngày hoàn tiền không được sau ngày hiện tại tại Việt Nam.";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
