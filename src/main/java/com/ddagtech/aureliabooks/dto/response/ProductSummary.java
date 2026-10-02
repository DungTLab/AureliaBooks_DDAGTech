package com.ddagtech.aureliabooks.dto.response;

import java.math.BigDecimal;

public record ProductSummary(Long id, String title, BigDecimal price, Integer stockQuantity, String mainImageUrl) {}
