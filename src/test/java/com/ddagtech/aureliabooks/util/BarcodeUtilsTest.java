package com.ddagtech.aureliabooks.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BarcodeUtils - Kiểm thử tiện ích EAN-13 theo BR-02-05")
class BarcodeUtilsTest {

    @Test
    @DisplayName("Tính đúng số kiểm tra EAN-13 cho mã chuẩn quốc tế")
    void calculateEan13Checksum_validInputs() {
        // "893850597401" -> 9 (Mã sách VN chuẩn)
        assertThat(BarcodeUtils.calculateEan13Checksum("893850597401")).isEqualTo(9);

        // "400638133393" -> 1 (Bút STABILO)
        assertThat(BarcodeUtils.calculateEan13Checksum("400638133393")).isEqualTo(1);
    }

    @Test
    @DisplayName("Ném exception khi đầu vào tính checksum không đúng 12 chữ số")
    void calculateEan13Checksum_invalidInputs() {
        assertThatThrownBy(() -> BarcodeUtils.calculateEan13Checksum("123"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BarcodeUtils.calculateEan13Checksum("1234567890123"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BarcodeUtils.calculateEan13Checksum("12345678901A"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BarcodeUtils.calculateEan13Checksum(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"8938505974019", "4006381333931"})
    @DisplayName("Xác thực thành công các mã vạch chuẩn EAN-13")
    void isValidEan13_true(String validBarcode) {
        assertThat(BarcodeUtils.isValidEan13(validBarcode)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "8938505974018", // Sai check digit (đúng là 9)
            "4006381333930", // Sai check digit (đúng là 1)
            "123456789012",  // Thiếu 1 chữ số (12 số)
            "12345678901234",// Thừa 1 chữ số (14 số)
            "893850597401A", // Ký tự không phải số
            "BK-123456",     // Mã chữ
            ""
    })
    @DisplayName("Từ chối các mã vạch không đúng chuẩn EAN-13")
    void isValidEan13_false(String invalidBarcode) {
        assertThat(BarcodeUtils.isValidEan13(invalidBarcode)).isFalse();
    }

    @Test
    @DisplayName("isValidEan13 trả về false khi null")
    void isValidEan13_null() {
        assertThat(BarcodeUtils.isValidEan13(null)).isFalse();
    }

    @Test
    @DisplayName("Tự động sinh mã EAN-13 luôn hợp lệ 13 chữ số và bắt đầu bằng 893")
    void generateEan13_validStructure() {
        for (int i = 0; i < 50; i++) {
            String barcodeBook = BarcodeUtils.generateEan13("8");
            assertThat(barcodeBook).hasSize(13);
            assertThat(barcodeBook).startsWith("8938");
            assertThat(BarcodeUtils.isValidEan13(barcodeBook)).isTrue();

            String barcodeStationery = BarcodeUtils.generateEan13("5");
            assertThat(barcodeStationery).hasSize(13);
            assertThat(barcodeStationery).startsWith("8935");
            assertThat(BarcodeUtils.isValidEan13(barcodeStationery)).isTrue();
        }
    }
}
