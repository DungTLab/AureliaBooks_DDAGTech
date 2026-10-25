package com.ddagtech.aureliabooks.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Staff request payload for confirming receipt of a full order return (POP-DB-02 / UC14.1).
 */
public record ReturnConfirmationRequest(
        @NotNull(message = "Bắt buộc xác nhận đã nhận lại đầy đủ toàn bộ hàng hoàn.")
        @AssertTrue(message = "Bắt buộc xác nhận đã nhận lại đầy đủ toàn bộ hàng hoàn.")
        Boolean fullReturnVerified,

        @NotBlank(message = "Nhân viên phải nhập lý do trả hàng / hoàn hàng.")
        @Size(max = 255, message = "Lý do trả hàng không vượt quá 255 ký tự.")
        String returnReason,

        @Size(max = 1000, message = "Ghi chú kiểm tra không vượt quá 1000 ký tự.")
        String inspectionNote
) {
}
