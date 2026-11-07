package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.response.ProductSummary;
import java.util.List;

public interface RecommendationService {
    List<ProductSummary> similar(Long productId);
    List<ProductSummary> personalized(Long customerId, Long currentProductId);
}
