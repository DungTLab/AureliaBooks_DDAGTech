package com.ddagtech.aureliabooks.dto.request;

import com.ddagtech.aureliabooks.entity.Book;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BookFormValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    private BookForm createValidBookFormWithIsbn(String isbn) {
        return new BookForm(
                null,
                "893500000001",
                "Tiêu đề sách kiểm thử",
                1L,
                BigDecimal.valueOf(100000),
                BigDecimal.valueOf(60000),
                200,
                null,
                null,
                "Mô tả",
                isbn,
                1L,
                Set.of(1L),
                null,
                null,
                false,
                2024,
                null,
                300,
                "Tiếng Việt",
                Book.CoverType.PAPERBACK
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "080442957X",        // Valid ISBN-10 with check digit X (regression target)
            "080442957x",        // Valid ISBN-10 with check digit lowercase x
            "0-8044-2957-X",      // Valid ISBN-10 with hyphens and X
            "0-8044-2957-1",      // Valid ISBN-10 with numeric check digit and hyphens
            "0804429571",        // Valid ISBN-10 numeric
            "9780306406157",     // Valid ISBN-13
            "978-0-306-40615-7"   // Valid ISBN-13 with hyphens
    })
    @DisplayName("Should accept valid ISBN-10 (including check digit X) and valid ISBN-13")
    void shouldAcceptValidIsbn(String validIsbn) {
        BookForm form = createValidBookFormWithIsbn(validIsbn);
        Set<ConstraintViolation<BookForm>> violations = validator.validate(form);

        assertThat(violations)
                .filteredOn(v -> v.getPropertyPath().toString().equals("isbn"))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "abc",               // Non-digit string
            "080442957",         // Only 9 digits without check digit
            "080442957XX",       // Too many check characters
            "978030640615",      // 12 digits (incomplete ISBN-13)
            "97803064061578"     // 14 digits (exceeds ISBN-13)
    })
    @DisplayName("Should reject invalid ISBN strings")
    void shouldRejectInvalidIsbn(String invalidIsbn) {
        BookForm form = createValidBookFormWithIsbn(invalidIsbn);
        Set<ConstraintViolation<BookForm>> violations = validator.validate(form);

        assertThat(violations)
                .filteredOn(v -> v.getPropertyPath().toString().equals("isbn"))
                .isNotEmpty();
    }
}
