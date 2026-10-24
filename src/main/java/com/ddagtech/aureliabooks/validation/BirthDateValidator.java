package com.ddagtech.aureliabooks.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.time.LocalDate;

public class BirthDateValidator implements ConstraintValidator<BirthDate, LocalDate> {
    public static final LocalDate MINIMUM = LocalDate.of(1900, 1, 1);

    public static boolean isValidDate(LocalDate value, LocalDate today) {
        return value == null || (!value.isBefore(MINIMUM) && value.isBefore(today));
    }

    @Override
    public boolean isValid(LocalDate value, ConstraintValidatorContext context) {
        LocalDate today = LocalDate.now(context.getClockProvider().getClock());
        if (value != null && !value.isBefore(today)) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate("Ngày sinh phải là ngày trong quá khứ.")
                    .addConstraintViolation();
            return false;
        }
        return isValidDate(value, today);
    }
}
