package com.ddagtech.aureliabooks.controller.admin;

import com.ddagtech.aureliabooks.dto.response.StockMovementLogResponse;
import com.ddagtech.aureliabooks.entity.StockMovementLog;
import com.ddagtech.aureliabooks.service.StockLedgerService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * UC22/23/21 & FND-03 (Stock Ledger & Inbound Receipts).
 * Owner: Nguyễn Trần Đức Anh.
 */
@Controller
@RequiredArgsConstructor
public class AdminReceiptController {

    private final ObjectProvider<StockLedgerService> stockLedgerServiceProvider;

    @GetMapping("/staff/receipts/new")
    public String draft() {
        // TODO UC22/23/21: populate Model through service/DTO contracts after implementation.
        return "admin/receipts/form";
    }

    @GetMapping("/manager/receipts")
    public String review() {
        // TODO UC22/23/21: populate Model through service/DTO contracts after implementation.
        return "admin/receipts/list";
    }

    @GetMapping("/manager/stock/alerts")
    public String lowStock() {
        // TODO UC22/23/21: populate Model through service/DTO contracts after implementation.
        return "admin/stock/alerts";
    }

    @GetMapping("/manager/stock/ledger")
    public String ledger(
            @RequestParam(required = false) StockMovementLog.TransactionType type,
            @RequestParam(required = false) Long productId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            Model model) {
        StockLedgerService service = stockLedgerServiceProvider.getIfAvailable();
        if (service != null) {
            Page<StockMovementLogResponse> logs = service.getLedgerLogsWithFilter(type, productId, null, null, pageable);
            model.addAttribute("logs", logs);
        }
        model.addAttribute("selectedType", type);
        model.addAttribute("selectedProductId", productId);
        return "admin/stock/ledger";
    }
}
