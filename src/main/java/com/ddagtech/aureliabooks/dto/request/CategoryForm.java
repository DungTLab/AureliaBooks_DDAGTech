package com.ddagtech.aureliabooks.dto.request;

/** UC20. Owner: Huỳnh Nhật Duy. TODO reject self/descendant parent cycles. */
public record CategoryForm(String name, Long parentId, String description, Boolean active) {}
