package com.ddagtech.aureliabooks.service;

import static org.junit.jupiter.api.Assertions.*;

import com.ddagtech.aureliabooks.dto.request.*;
import com.ddagtech.aureliabooks.entity.User;
import jakarta.validation.Validation;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class BirthDateValidationTest {

    @Test
    void registrationAndProfileEnforceSameDateBoundsWithoutMinimumAge() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (LocalDate date : List.of(
                LocalDate.of(1111, 11, 11),
                LocalDate.of(1899, 12, 31),
                LocalDate.now(),
                LocalDate.now().plusDays(1)
            )) {
                assertFalse(validator.validateValue(RegisterRequest.class, "dob", date).isEmpty());
                assertFalse(validator.validateValue(ProfileUpdateRequest.class, "dob", date).isEmpty());
            }
            for (LocalDate date : List.of(
                LocalDate.of(1900, 1, 1),
                LocalDate.of(2000, 2, 29),
                LocalDate.now().minusDays(1)
            )) {
                assertTrue(validator.validateValue(RegisterRequest.class, "dob", date).isEmpty());
                assertTrue(validator.validateValue(ProfileUpdateRequest.class, "dob", date).isEmpty());
            }
            assertTrue(validator.validateValue(RegisterRequest.class, "dob", null).isEmpty());
            assertTrue(validator.validateValue(ProfileUpdateRequest.class, "dob", null).isEmpty());
        }
    }
}
