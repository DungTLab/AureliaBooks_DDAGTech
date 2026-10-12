package com.ddagtech.aureliabooks.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request form DTO for Supplier creation and update (UC30).
 * Strictly mapped to SQL schema: name, contact_name, phone, email, address, is_active.
 * Note: Database schema has no tax_code or payment_terms column.
 *
 * @param name company / business name (max 150)
 * @param contactName representative contact person (max 100)
 * @param phone telephone number (max 15)
 * @param email contact email address (max 100)
 * @param address physical / warehouse address (max 255)
 * @param isActive active status flag
 */
public record SupplierForm(
        @NotBlank(message = "Tên nhà cung cấp không được để trống")
        @Size(max = 150, message = "Tên nhà cung cấp tối đa 150 ký tự")
        String name,

        @Size(max = 100, message = "Tên người liên hệ tối đa 100 ký tự")
        String contactName,

        @NotBlank(message = "Số điện thoại không được để trống")
        @Pattern(regexp = "^(0|\\+84)[0-9]{9,10}$", message = "Số điện thoại không đúng định dạng (10-11 chữ số)")
        @Size(max = 15, message = "Số điện thoại tối đa 15 ký tự")
        String phone,

        @Size(max = 100, message = "Email tối đa 100 ký tự")
        @Email(message = "Email không đúng định dạng")
        String email,

        @Size(max = 255, message = "Địa chỉ tối đa 255 ký tự")
        String address,

        Boolean isActive
) {
    public SupplierForm {
        name = name != null ? name.trim() : null;
        contactName = contactName != null && !contactName.isBlank() ? contactName.trim() : null;
        phone = phone != null ? phone.trim() : null;
        email = email != null && !email.isBlank() ? email.trim() : null;
        address = address != null && !address.isBlank() ? address.trim() : null;
        isActive = isActive != null ? isActive : true;
    }

    public static SupplierForm of(String name, String contactName, String phone, String email, String address) {
        return new SupplierForm(name, contactName, phone, email, address, true);
    }
}
