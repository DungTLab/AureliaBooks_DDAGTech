package com.ddagtech.aureliabooks.validation;

import com.ddagtech.aureliabooks.util.BusinessDate;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.time.LocalDate;

public class PastOrPresentBusinessDateValidator
        implements ConstraintValidator<PastOrPresentBusinessDate, LocalDate> {
    @Override
    public boolean isValid(LocalDate value, ConstraintValidatorContext context) {
        return value == null || !value.isAfter(BusinessDate.today(context.getClockProvider().getClock()));
    }
}
