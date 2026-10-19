package com.ddagtech.aureliabooks.controller.admin;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.request.GoodsReceiptForm;
import com.ddagtech.aureliabooks.dto.request.GoodsReceiptItemForm;
import com.ddagtech.aureliabooks.dto.request.QuickCreateProductRequest;
import com.ddagtech.aureliabooks.dto.response.*;
import com.ddagtech.aureliabooks.entity.GoodsReceipt;
import com.ddagtech.aureliabooks.entity.Product;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.CategoryRepository;
import com.ddagtech.aureliabooks.repository.ProductRepository;
import com.ddagtech.aureliabooks.repository.SupplierRepository;
import com.ddagtech.aureliabooks.security.CustomUserDetails;
import com.ddagtech.aureliabooks.service.GoodsReceiptService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Controller managing Goods Receipts (UC22 Draft, UC23 Finalize) and Stock Alerts (UC21).
 * Routes:
 *  - /staff/receipts/** -> Sale Staff, Manager, Admin
 *  - /manager/receipts/** -> Manager, Admin
 *  - /manager/stock/alerts & /staff/stock/alerts -> Sale Staff, Manager, Admin
 *
 * Owner: Nguyễn Trần Đức Anh.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class AdminReceiptController {

    private final GoodsReceiptService goodsReceiptService;
    private final SupplierRepository supplierRepository;
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;

    // =========================================================================
    // UC22: Draft Goods Receipts (Sale Staff, Manager, Admin)
    // =========================================================================

    /**
     * UC22: Displays form to draft a new goods receipt.
     * Optionally pre-populates item rows when single or multiple product IDs are passed from inventory management.
     */
    @GetMapping({"/staff/receipts/new", "/manager/receipts/new"})
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'SALE_STAFF')")
    public String draftForm(
            @AuthenticationPrincipal CustomUserDetails user,
            @RequestParam(name = "productId", required = false) List<Long> productIds,
            @RequestParam(name = "productIds", required = false) List<Long> productIdsPlural,
            Model model
    ) {
        populateFormReferenceData(model);
        String baseUri = isManagerOrAdmin(user) ? "/manager/receipts" : "/staff/receipts";
        model.addAttribute("baseUri", baseUri);

        if (!model.containsAttribute("receiptForm")) {
            List<Long> targetIds = new ArrayList<>();
            if (productIds != null) targetIds.addAll(productIds);
            if (productIdsPlural != null) targetIds.addAll(productIdsPlural);
            targetIds = new ArrayList<>(new java.util.LinkedHashSet<>(targetIds));

            List<GoodsReceiptItemForm> initialItems = new ArrayList<>();
            if (!targetIds.isEmpty()) {
                List<Product> preselectedProducts = productRepository.findAllById(targetIds);
                for (Product p : preselectedProducts) {
                    BigDecimal cost = p.getOriginalCost() != null ? p.getOriginalCost() : BigDecimal.ZERO;
                    initialItems.add(new GoodsReceiptItemForm(p.getId(), 1, cost));
                }
            }
            model.addAttribute("receiptForm", new GoodsReceiptForm(null, "", initialItems));
        }
        model.addAttribute("isEdit", false);
        return "admin/receipts/form";
    }

    /**
     * UC22: Processes submission to create a DRAFT goods receipt.
     * Enforces that stock levels are NOT modified at this stage.
     */
    @PostMapping("/staff/receipts")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'SALE_STAFF')")
    public String createDraft(
            @AuthenticationPrincipal CustomUserDetails user,
            @Valid @ModelAttribute("receiptForm") GoodsReceiptForm form,
            BindingResult bindingResult,
            RedirectAttributes redirectAttributes,
            Model model
    ) {
        if (user == null || user.getId() == null) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        Long staffId = user.getId();

        if (bindingResult.hasErrors()) {
            String firstError = bindingResult.getAllErrors().get(0).getDefaultMessage();
            redirectAttributes.addFlashAttribute("errorMessage", firstError);
            redirectAttributes.addFlashAttribute("receiptForm", form);
            return "redirect:/staff/receipts/new";
        }

        try {
            Long receiptId = goodsReceiptService.createDraft(staffId, form);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Đã lưu phiếu nhập kho ở trạng thái NHÁP (DRAFT) thành công! Tồn kho chưa bị thay đổi.");
            return isManagerOrAdmin(user) ? "redirect:/manager/receipts" : "redirect:/staff/receipts";
        } catch (AppException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
            redirectAttributes.addFlashAttribute("receiptForm", form);
            return "redirect:/staff/receipts/new";
        }
    }

    /**
     * Displays form to edit an existing DRAFT goods receipt.
     */
    @GetMapping({"/staff/receipts/{id}/edit", "/manager/receipts/{id}/edit"})
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'SALE_STAFF')")
    public String editForm(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails user,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        String baseUri = isManagerOrAdmin(user) ? "/manager/receipts" : "/staff/receipts";
        model.addAttribute("baseUri", baseUri);

        try {
            GoodsReceiptDetailDto detail = goodsReceiptService.getReceiptDetail(id);
            if (detail.status() == GoodsReceipt.Status.RECEIVED) {
                redirectAttributes.addFlashAttribute("errorMessage",
                        "Phiếu nhập kho đã hoàn tất (RECEIVED), không được phép chỉnh sửa!");
                return isManagerOrAdmin(user) ? "redirect:/manager/receipts" : "redirect:/staff/receipts";
            }

            populateFormReferenceData(model);

            List<GoodsReceiptItemForm> itemForms = detail.items().stream()
                    .map(i -> new GoodsReceiptItemForm(i.productId(), i.receivedQuantity(), i.unitCost()))
                    .toList();
            GoodsReceiptForm form = new GoodsReceiptForm(detail.supplierId(), detail.note(), itemForms);

            model.addAttribute("receiptForm", form);
            model.addAttribute("receiptDetail", detail);
            model.addAttribute("isEdit", true);
            model.addAttribute("receiptId", id);
            return "admin/receipts/form";
        } catch (AppException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
            return isManagerOrAdmin(user) ? "redirect:/manager/receipts" : "redirect:/staff/receipts";
        }
    }

    /**
     * UC22: Updates an existing DRAFT goods receipt.
     */
    @PostMapping({"/staff/receipts/{id}/update", "/manager/receipts/{id}/update"})
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'SALE_STAFF')")
    public String updateDraft(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails user,
            @Valid @ModelAttribute("receiptForm") GoodsReceiptForm form,
            BindingResult bindingResult,
            RedirectAttributes redirectAttributes
    ) {
        if (user == null || user.getId() == null) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        Long staffId = user.getId();

        if (bindingResult.hasErrors()) {
            String firstError = bindingResult.getAllErrors().get(0).getDefaultMessage();
            redirectAttributes.addFlashAttribute("errorMessage", firstError);
            return "redirect:/staff/receipts/" + id + "/edit";
        }

        try {
            goodsReceiptService.updateDraft(staffId, id, form);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Cập nhật phiếu nhập kho nháp thành công! Tồn kho chưa bị thay đổi.");
            return isManagerOrAdmin(user) ? "redirect:/manager/receipts" : "redirect:/staff/receipts";
        } catch (AppException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
            return "redirect:/staff/receipts/" + id + "/edit";
        }
    }

    /**
     * UC22: Deletes an existing DRAFT goods receipt.
     */
    @PostMapping({"/staff/receipts/{id}/delete", "/manager/receipts/{id}/delete"})
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'SALE_STAFF')")
    public String deleteDraft(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails user,
            RedirectAttributes redirectAttributes
    ) {
        if (user == null || user.getId() == null) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        Long staffId = user.getId();
        try {
            goodsReceiptService.deleteDraft(staffId, id);
            redirectAttributes.addFlashAttribute("successMessage", "Đã xóa phiếu nhập kho nháp thành công.");
        } catch (AppException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return isManagerOrAdmin(user) ? "redirect:/manager/receipts" : "redirect:/staff/receipts";
    }

    /**
     * Displays receipts list for staff / manager.
     */
    @GetMapping({"/manager/receipts", "/staff/receipts"})
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'SALE_STAFF')")
    public String listReceipts(
            jakarta.servlet.http.HttpServletRequest request,
            @RequestParam(required = false) GoodsReceipt.Status status,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Model model
    ) {
        String baseUri = request != null && request.getRequestURI() != null ? request.getRequestURI() : "/manager/receipts";
        Page<ReceiptSummary> receiptsPage = goodsReceiptService.getReceipts(
                status,
                supplierId,
                PageRequest.of(page, size, Sort.by("createdAt").descending())
        );

        model.addAttribute("baseUri", baseUri);
        model.addAttribute("receiptsPage", receiptsPage);
        model.addAttribute("selectedStatus", status);
        model.addAttribute("selectedSupplierId", supplierId);
        model.addAttribute("suppliers", supplierRepository.findByIsActiveTrueOrderByNameAsc());

        return "admin/receipts/list";
    }

    // =========================================================================
    // UC23: Finalize Goods Receipts & Increment Stock (Manager, Admin)
    // =========================================================================

    /**
     * UC23: Finalizes DRAFT goods receipt into RECEIVED in an atomic transaction.
     * Increments inventory ATP stock, updates product cost, and seals receipt lines as immutable.
     */
    @PostMapping("/manager/receipts/{id}/finalize")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public String finalizeReceipt(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails user,
            RedirectAttributes redirectAttributes
    ) {
        if (user == null || user.getId() == null) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        Long managerId = user.getId();
        try {
            goodsReceiptService.receive(managerId, id);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Duyệt nhập kho thành công! Đã tăng tồn kho, cập nhật giá vốn và hoàn tất chứng từ.");
        } catch (AppException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/manager/receipts";
    }

    /**
     * AJAX/REST endpoint to fetch full receipt details for modal viewing.
     */
    @GetMapping("/api/receipts/{id}")
    @ResponseBody
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'SALE_STAFF')")
    public ResponseEntity<GoodsReceiptDetailDto> getReceiptApi(@PathVariable Long id) {
        GoodsReceiptDetailDto detail = goodsReceiptService.getReceiptDetail(id);
        return ResponseEntity.ok(detail);
    }

    // =========================================================================
    // UC21: Monitor Inventory & Stock Alerts (Sale Staff, Manager, Admin)
    // =========================================================================

    /**
     * UC21: Displays real-time inventory monitoring and stock alert badges.
     * Thresholds: 0 (Hết hàng / Danger), 1-10 (Tồn kho thấp / Warning), >10 (An toàn / Success).
     */
    @GetMapping({"/manager/stock/alerts", "/staff/stock/alerts", "/inventory/stock/alerts"})
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'SALE_STAFF')")
    public String stockAlerts(
            jakarta.servlet.http.HttpServletRequest request,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Product.ProductType productType,
            @RequestParam(required = false) String alertLevel,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size,
            Model model
    ) {
        String baseUri = request != null && request.getRequestURI() != null ? request.getRequestURI() : "/manager/stock/alerts";
        StockMetricSummary metrics = goodsReceiptService.getStockMetrics();
        Page<ProductStockAlertDto> alertsPage = goodsReceiptService.getStockAlerts(
                keyword,
                categoryId,
                productType,
                alertLevel,
                PageRequest.of(page, size, Sort.by("stockQuantity").ascending().and(Sort.by("id").descending()))
        );

        model.addAttribute("baseUri", baseUri);
        model.addAttribute("metrics", metrics);
        model.addAttribute("alertsPage", alertsPage);
        model.addAttribute("categories", categoryRepository.findByIsActiveTrueOrderByNameAsc());
        model.addAttribute("keyword", keyword);
        model.addAttribute("selectedCategoryId", categoryId);
        model.addAttribute("selectedProductType", productType);
        model.addAttribute("selectedAlertLevel", alertLevel);

        return "admin/stock/alerts";
    }

    // =========================================================================
    // Quick Create Product API (UC22 On-the-fly Inbound Product Creation)
    // =========================================================================

    /**
     * REST endpoint to quickly create a product on-the-fly directly from the receipt form modal.
     */
    @PostMapping("/api/products/quick-create")
    @ResponseBody
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'SALE_STAFF')")
    public ResponseEntity<?> quickCreateProduct(
            @Valid @RequestBody QuickCreateProductRequest request,
            BindingResult bindingResult
    ) {
        if (bindingResult.hasErrors()) {
            String error = bindingResult.getAllErrors().get(0).getDefaultMessage();
            return ResponseEntity.badRequest().body(Map.of("message", error));
        }
        try {
            QuickProductResponse response = goodsReceiptService.quickCreateProduct(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to quick-create product: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void populateFormReferenceData(Model model) {
        model.addAttribute("suppliers", supplierRepository.findByIsActiveTrueOrderByNameAsc());
        model.addAttribute("products", productRepository.findByIsActiveTrueOrderByTitleAsc());
        model.addAttribute("categories", categoryRepository.findByIsActiveTrueOrderByNameAsc());
        java.util.Set<Long> textbookIds = goodsReceiptService.getTextbookProductIds();
        model.addAttribute("textbookIds", textbookIds != null ? textbookIds : java.util.Collections.emptySet());
    }

    private boolean isManagerOrAdmin(CustomUserDetails user) {
        if (user == null || user.getAuthorities() == null) {
            return false;
        }
        return user.getAuthorities().stream().anyMatch(a ->
                "ROLE_ADMIN".equals(a.getAuthority()) || "ROLE_MANAGER".equals(a.getAuthority()));
    }
}
