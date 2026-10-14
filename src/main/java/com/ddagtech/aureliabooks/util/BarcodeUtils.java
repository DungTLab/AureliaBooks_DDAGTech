package com.ddagtech.aureliabooks.util;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Tiện ích xử lý mã vạch EAN-13 theo chuẩn GS1 và quy tắc nghiệp vụ BR-02-05 (RDS Document).
 * <p>
 * Quy định chuẩn EAN-13:
 * - Độ dài: đúng 13 chữ số.
 * - Mã quốc gia Việt Nam (GS1): tiền tố 893.
 * - Thuật toán số kiểm tra (check digit - vị trí thứ 13):
 *   Tổng các chữ số ở vị trí lẻ (1, 3, 5, 7, 9, 11) * 1 + tổng các chữ số ở vị trí chẵn (2, 4, 6, 8, 10, 12) * 3.
 *   Số kiểm tra = (10 - (tổng % 10)) % 10.
 */
public final class BarcodeUtils {

    public static final String VIETNAM_GS1_PREFIX = "893";

    private BarcodeUtils() {
        // Utility class
    }

    /**
     * Tính số kiểm tra (check digit) cho chuỗi 12 chữ số đầu tiên theo thuật toán EAN-13 GS1.
     *
     * @param first12Digits Chuỗi đúng 12 chữ số
     * @return Số kiểm tra (0-9)
     */
    public static int calculateEan13Checksum(String first12Digits) {
        if (first12Digits == null || first12Digits.length() != 12 || !first12Digits.matches("\\d{12}")) {
            throw new IllegalArgumentException("Chuỗi tính số kiểm tra EAN-13 phải gồm đúng 12 chữ số.");
        }
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            int digit = first12Digits.charAt(i) - '0';
            // Vị trí 1-indexed lẻ (0-indexed chẵn): nhân 1
            // Vị trí 1-indexed chẵn (0-indexed lẻ): nhân 3
            sum += (i % 2 == 0) ? digit : digit * 3;
        }
        int remainder = sum % 10;
        return (remainder == 0) ? 0 : 10 - remainder;
    }

    /**
     * Kiểm tra một chuỗi có phải mã vạch chuẩn EAN-13 hợp lệ (13 chữ số và số kiểm tra đúng).
     *
     * @param barcode Mã vạch cần kiểm tra
     * @return true nếu hợp lệ chuẩn EAN-13, ngược lại false
     */
    public static boolean isValidEan13(String barcode) {
        if (barcode == null || barcode.length() != 13 || !barcode.matches("\\d{13}")) {
            return false;
        }
        int expectedChecksum = calculateEan13Checksum(barcode.substring(0, 12));
        int actualChecksum = barcode.charAt(12) - '0';
        return expectedChecksum == actualChecksum;
    }

    /**
     * Tự động sinh mã vạch chuẩn EAN-13 với tiền tố GS1 Việt Nam (893).
     *
     * @param subPrefix Chữ số phân loại phụ (ví dụ: "8" cho Sách, "5" cho VPP)
     * @return Chuỗi mã vạch 13 chữ số chuẩn EAN-13
     */
    public static String generateEan13(String subPrefix) {
        StringBuilder sb = new StringBuilder(VIETNAM_GS1_PREFIX);
        if (subPrefix != null) {
            for (char c : subPrefix.toCharArray()) {
                if (Character.isDigit(c) && sb.length() < 12) {
                    sb.append(c);
                }
            }
        }
        // Điền các chữ số ngẫu nhiên cho đủ 12 chữ số
        while (sb.length() < 12) {
            sb.append(ThreadLocalRandom.current().nextInt(10));
        }
        int checksum = calculateEan13Checksum(sb.toString());
        sb.append(checksum);
        return sb.toString();
    }
}
