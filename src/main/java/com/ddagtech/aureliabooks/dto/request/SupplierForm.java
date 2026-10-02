package com.ddagtech.aureliabooks.dto.request;


/** UC30: SQL has no tax_code column. Owner: Nguyễn Trần Đức Anh. Sprint 1 scaffold; business implementation pending. */
public record SupplierForm(
        String name,
        String contactName,
        String phone,
        String email,
        String address) {
    // TODO: field validation and business validation are owned by the assigned developer.
}
