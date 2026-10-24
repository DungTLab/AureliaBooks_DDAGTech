package com.ddagtech.aureliabooks.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

/** Optional birth date from January 1, 1900 through the day before today. */
@Documented
@Constraint(validatedBy = BirthDateValidator.class)
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
public @interface BirthDate {
    String message() default "Ngày sinh phải từ 01/01/1900 và trước hôm nay.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
