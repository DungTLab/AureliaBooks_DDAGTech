package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.request.*;
import com.ddagtech.aureliabooks.dto.response.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface OrderStateMachineService {
    Page<StaffOrderQueueView> getOrderQueue(OrderQueueFilter filter, Pageable pageable, Long staffId);
    PackagingChecklistDTO getPackagingChecklist(Long orderId, Long staffId);
    void transitionStatus(Long orderId, OrderTransitionRequest request, Long staffId);
    void confirmExternalRefund(Long orderId, RefundConfirmationRequest request, Long staffId);
}
