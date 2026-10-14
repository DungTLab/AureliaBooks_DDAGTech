package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.request.GoodsReceiptForm;
import com.ddagtech.aureliabooks.dto.request.GoodsReceiptItemForm;
import com.ddagtech.aureliabooks.dto.request.QuickCreateProductRequest;
import com.ddagtech.aureliabooks.dto.response.*;
import com.ddagtech.aureliabooks.entity.*;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.*;
import com.ddagtech.aureliabooks.util.BarcodeUtils;
import com.ddagtech.aureliabooks.service.AuditLogService;
import com.ddagtech.aureliabooks.service.GoodsReceiptService;
import com.ddagtech.aureliabooks.service.StockMutationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Service implementation for UC21 (Stock Alerts), UC22 (Draft Goods Receipts), and UC23 (Finalize Goods Receipts).
 * Strictly enforces:
 *  - DRAFT status zero-mutation on stock levels (UC22).
 *  - BR-01-01 import thresholds: <=100 for general items, <=500 for textbooks on initial batches.
 *  - Schema unit_cost >= 0 compliance (allowing 0-cost donation/sampling imports).
 *  - Immutable locking of RECEIVED receipts (UC23): prohibit edit/delete after posting.
 *  - Single-transaction document posting through StockMutationService.
 *  - Stock alert classification: 0 (out of stock), 1-10 (low stock), >10 (safe stock) (UC21).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoodsReceiptServiceImpl implements GoodsReceiptService {

    private final GoodsReceiptRepository goodsReceiptRepository;
    private final GoodsReceiptItemRepository goodsReceiptItemRepository;
    private final ProductRepository productRepository;
    private final SupplierRepository supplierRepository;
    private final UserRepository userRepository;
    private final BookRepository bookRepository;
    private final CategoryRepository categoryRepository;
    private final PublisherRepository publisherRepository;
    private final BrandRepository brandRepository;
    private final StationeryRepository stationeryRepository;
    private final StockMutationService stockMutationService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    /**
     * UC22: Creates a DRAFT goods receipt without modifying product stock levels.
     * Enforces BR-01-01 initial batch import cap (100 for general items, 500 for textbooks).
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createDraft(Long staffId, GoodsReceiptForm form) {
        if (staffId == null) {
            throw new AppException(ErrorCode.UNAUTHENTICATED, "Cần xác thực nhân viên trước khi lập phiếu nhập.");
        }
        User staff = userRepository.findById(staffId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED, "Không tìm thấy tài khoản nhân viên."));

        validateForm(form);

        Supplier supplier = supplierRepository.findById(form.supplierId())
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy nhà cung cấp."));

        if (!Boolean.TRUE.equals(supplier.getIsActive())) {
            throw new AppException(ErrorCode.SUPPLIER_INACTIVE, "Nhà cung cấp đã ngừng hoạt động hoặc không hợp lệ.");
        }

        String receiptCode = generateReceiptCode();
        BigDecimal totalAmount = BigDecimal.ZERO;
        List<GoodsReceiptItem> itemsToPersist = new ArrayList<>();

        GoodsReceipt receipt = GoodsReceipt.builder()
                .receiptCode(receiptCode)
                .supplier(supplier)
                .createdBy(staff)
                .status(GoodsReceipt.Status.DRAFT)
                .totalAmount(BigDecimal.ZERO)
                .note(form.note() != null ? form.note().trim() : null)
                .receivedAt(null)
                .receivedBy(null)
                .build();

        GoodsReceipt savedReceipt = goodsReceiptRepository.save(receipt);

        for (GoodsReceiptItemForm itemForm : form.items()) {
            Product product = productRepository.findById(itemForm.productId())
                    .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy sản phẩm ID: " + itemForm.productId()));

            validateItemRules(product, itemForm);

            BigDecimal lineTotal = itemForm.unitCost().multiply(BigDecimal.valueOf(itemForm.receivedQuantity()));
            totalAmount = totalAmount.add(lineTotal);

            GoodsReceiptItem item = GoodsReceiptItem.builder()
                    .receipt(savedReceipt)
                    .product(product)
                    .receivedQuantity(itemForm.receivedQuantity())
                    .unitCost(itemForm.unitCost())
                    .build();
            itemsToPersist.add(item);
        }

        goodsReceiptItemRepository.saveAll(itemsToPersist);

        savedReceipt.setTotalAmount(totalAmount);
        goodsReceiptRepository.save(savedReceipt);

        // Record audit log for draft creation
        Map<String, Object> auditDetails = new HashMap<>();
        auditDetails.put("receiptCode", savedReceipt.getReceiptCode());
        auditDetails.put("supplierId", supplier.getId());
        auditDetails.put("itemCount", itemsToPersist.size());
        auditDetails.put("totalAmount", totalAmount);
        auditLogService.record(staffId, "RECEIPT_CREATE_DRAFT", "goods_receipts", savedReceipt.getId(), toJson(auditDetails), null);

        log.info("Created DRAFT goods receipt id={} code={} by staffId={}", savedReceipt.getId(), savedReceipt.getReceiptCode(), staffId);
        return savedReceipt.getId();
    }

    /**
     * UC22: Updates an existing DRAFT goods receipt.
     * Prohibits modification if receipt is already RECEIVED.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateDraft(Long staffId, Long receiptId, GoodsReceiptForm form) {
        if (receiptId == null) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Mã phiếu nhập không được để trống.");
        }

        GoodsReceipt receipt = goodsReceiptRepository.findById(receiptId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy phiếu nhập kho."));

        // Immutable Lock check: Prohibit modifying finalized receipts
        if (receipt.getStatus() == GoodsReceipt.Status.RECEIVED) {
            throw new AppException(ErrorCode.RECEIPT_ALREADY_RECEIVED, "Phiếu nhập kho đã hoàn tất (RECEIVED), không thể chỉnh sửa.");
        }

        validateForm(form);

        Supplier supplier = supplierRepository.findById(form.supplierId())
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy nhà cung cấp."));

        if (!Boolean.TRUE.equals(supplier.getIsActive())) {
            throw new AppException(ErrorCode.SUPPLIER_INACTIVE, "Nhà cung cấp đã ngừng hoạt động hoặc không hợp lệ.");
        }

        // Delete old items and re-insert new items
        goodsReceiptItemRepository.deleteByReceiptId(receiptId);

        BigDecimal totalAmount = BigDecimal.ZERO;
        List<GoodsReceiptItem> itemsToPersist = new ArrayList<>();

        for (GoodsReceiptItemForm itemForm : form.items()) {
            Product product = productRepository.findById(itemForm.productId())
                    .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy sản phẩm ID: " + itemForm.productId()));

            validateItemRules(product, itemForm);

            BigDecimal lineTotal = itemForm.unitCost().multiply(BigDecimal.valueOf(itemForm.receivedQuantity()));
            totalAmount = totalAmount.add(lineTotal);

            GoodsReceiptItem item = GoodsReceiptItem.builder()
                    .receipt(receipt)
                    .product(product)
                    .receivedQuantity(itemForm.receivedQuantity())
                    .unitCost(itemForm.unitCost())
                    .build();
            itemsToPersist.add(item);
        }

        goodsReceiptItemRepository.saveAll(itemsToPersist);

        receipt.setSupplier(supplier);
        receipt.setNote(form.note() != null ? form.note().trim() : null);
        receipt.setTotalAmount(totalAmount);
        goodsReceiptRepository.save(receipt);

        Map<String, Object> auditDetails = new HashMap<>();
        auditDetails.put("receiptCode", receipt.getReceiptCode());
        auditDetails.put("supplierId", supplier.getId());
        auditDetails.put("itemCount", itemsToPersist.size());
        auditDetails.put("totalAmount", totalAmount);
        auditLogService.record(staffId, "RECEIPT_UPDATE_DRAFT", "goods_receipts", receipt.getId(), toJson(auditDetails), null);

        log.info("Updated DRAFT goods receipt id={} code={} by staffId={}", receiptId, receipt.getReceiptCode(), staffId);
    }

    /**
     * UC22: Deletes a DRAFT goods receipt.
     * Prohibits deletion if receipt is already RECEIVED.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDraft(Long staffId, Long receiptId) {
        if (receiptId == null) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Mã phiếu nhập không được để trống.");
        }

        GoodsReceipt receipt = goodsReceiptRepository.findById(receiptId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy phiếu nhập kho."));

        // Immutable Lock check: Prohibit deleting finalized receipts
        if (receipt.getStatus() == GoodsReceipt.Status.RECEIVED) {
            throw new AppException(ErrorCode.RECEIPT_ALREADY_RECEIVED, "Phiếu nhập kho đã hoàn tất (RECEIVED), không thể xóa.");
        }

        goodsReceiptItemRepository.deleteByReceiptId(receiptId);
        goodsReceiptRepository.delete(receipt);

        Map<String, Object> auditDetails = new HashMap<>();
        auditDetails.put("receiptCode", receipt.getReceiptCode());
        auditLogService.record(staffId, "RECEIPT_DELETE_DRAFT", "goods_receipts", receiptId, toJson(auditDetails), null);

        log.info("Deleted DRAFT goods receipt id={} by staffId={}", receiptId, staffId);
    }

    /**
     * UC23: Finalizes DRAFT goods receipt into RECEIVED status in a single transaction.
     * Increments inventory stock, updates product cost, and stamps immutable receiver metadata.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void receive(Long managerId, Long receiptId) {
        if (receiptId == null) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Mã phiếu nhập không được để trống.");
        }
        if (managerId == null) {
            throw new AppException(ErrorCode.UNAUTHENTICATED, "Cần xác thực Quản lý (Manager) để duyệt phiếu nhập.");
        }

        GoodsReceipt receipt = goodsReceiptRepository.findById(receiptId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy phiếu nhập kho."));

        if (receipt.getStatus() == GoodsReceipt.Status.RECEIVED) {
            throw new AppException(ErrorCode.RECEIPT_ALREADY_RECEIVED, "Phiếu nhập kho này đã được duyệt hoàn tất trước đó.");
        }

        // Delegate to atomic StockMutationService
        stockMutationService.receiveReceipt(receiptId, managerId);
        log.info("Finalized goods receipt id={} to RECEIVED by managerId={}", receiptId, managerId);
    }

    /**
     * UC21 / Interface contract: Retrieves products at or below the specified stock threshold.
     */
    @Override
    @Transactional(readOnly = true)
    public Page<ProductSummary> lowStock(int threshold, Pageable pageable) {
        return productRepository.findByStockQuantityLessThanEqual(threshold, pageable)
                .map(p -> new ProductSummary(
                        p.getId(),
                        p.getTitle(),
                        p.getPrice(),
                        p.getStockQuantity(),
                        p.getMainImageUrl(),
                        p.getProductType(),
                        null,
                        null
                ));
    }

    /**
     * Lists goods receipts with optional status and supplier filters.
     */
    @Override
    @Transactional(readOnly = true)
    public Page<ReceiptSummary> getReceipts(GoodsReceipt.Status status, Long supplierId, Pageable pageable) {
        Page<GoodsReceipt> receipts = goodsReceiptRepository.findWithFilters(status, supplierId, pageable);
        return receipts.map(gr -> {
            int itemCount = gr.getItems() != null ? gr.getItems().size() : 0;
            if (itemCount == 0) {
                itemCount = goodsReceiptItemRepository.findByReceiptId(gr.getId()).size();
            }
            return new ReceiptSummary(
                    gr.getId(),
                    gr.getReceiptCode(),
                    gr.getStatus().name(),
                    gr.getTotalAmount(),
                    gr.getSupplier() != null ? gr.getSupplier().getName() : "-",
                    gr.getCreatedBy() != null ? gr.getCreatedBy().getFullName() : "-",
                    gr.getReceivedBy() != null ? gr.getReceivedBy().getFullName() : "-",
                    gr.getCreatedAt(),
                    gr.getReceivedAt(),
                    itemCount
            );
        });
    }

    /**
     * Retrieves full receipt details including immutable items and creator/receiver audit details.
     */
    @Override
    @Transactional(readOnly = true)
    public GoodsReceiptDetailDto getReceiptDetail(Long receiptId) {
        GoodsReceipt gr = goodsReceiptRepository.findById(receiptId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy phiếu nhập kho."));

        List<GoodsReceiptItem> items = goodsReceiptItemRepository.findByReceiptId(receiptId);
        List<GoodsReceiptItemDetailDto> itemDtos = items.stream().map(item -> {
            Product prod = item.getProduct();
            boolean isTextbook = false;
            if (prod != null) {
                Optional<Book> bookOpt = bookRepository.findById(prod.getId());
                isTextbook = bookOpt.isPresent() && Boolean.TRUE.equals(bookOpt.get().getIsTextbook());
            }

            return new GoodsReceiptItemDetailDto(
                    item.getId(),
                    prod != null ? prod.getId() : null,
                    prod != null ? prod.getTitle() : "-",
                    prod != null ? prod.getBarcode() : "-",
                    prod != null && prod.getCategory() != null ? prod.getCategory().getName() : "-",
                    prod != null ? prod.getProductType() : Product.ProductType.BOOK,
                    isTextbook,
                    item.getReceivedQuantity(),
                    item.getUnitCost()
            );
        }).toList();

        return new GoodsReceiptDetailDto(
                gr.getId(),
                gr.getReceiptCode(),
                gr.getSupplier() != null ? gr.getSupplier().getId() : null,
                gr.getSupplier() != null ? gr.getSupplier().getName() : "-",
                gr.getSupplier() != null ? gr.getSupplier().getPhone() : "-",
                gr.getSupplier() != null ? gr.getSupplier().getEmail() : "-",
                gr.getCreatedBy() != null ? gr.getCreatedBy().getId() : null,
                gr.getCreatedBy() != null ? gr.getCreatedBy().getFullName() : "-",
                gr.getStatus(),
                gr.getTotalAmount(),
                gr.getNote(),
                gr.getReceivedAt(),
                gr.getReceivedBy() != null ? gr.getReceivedBy().getId() : null,
                gr.getReceivedBy() != null ? gr.getReceivedBy().getFullName() : "-",
                gr.getCreatedAt(),
                gr.getUpdatedAt(),
                itemDtos
        );
    }

    /**
     * UC21: Aggregates real-time inventory threshold metrics:
     *  - Out of stock: exactly 0
     *  - Low stock: 1..10
     *  - Safe stock: > 10
     */
    @Override
    @Transactional(readOnly = true)
    public StockMetricSummary getStockMetrics() {
        long total = productRepository.count();
        long outOfStock = productRepository.countByStockQuantityEquals(0);
        long lowStock = productRepository.countByStockQuantityBetween(1, 10);
        long safeStock = productRepository.countByStockQuantityGreaterThan(10);

        return new StockMetricSummary(total, outOfStock, lowStock, safeStock);
    }

    /**
     * UC21: Filters products by catalog criteria and stock alert levels with pagination.
     */
    @Override
    @Transactional(readOnly = true)
    public Page<ProductStockAlertDto> getStockAlerts(
            String keyword,
            Long categoryId,
            Product.ProductType productType,
            String alertLevel,
            Pageable pageable
    ) {
        Specification<Product> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (keyword != null && !keyword.trim().isEmpty()) {
                String search = "%" + keyword.trim().toLowerCase() + "%";
                Predicate titlePred = cb.like(cb.lower(root.get("title")), search);
                Predicate barcodePred = cb.like(cb.lower(root.get("barcode")), search);
                predicates.add(cb.or(titlePred, barcodePred));
            }

            if (categoryId != null) {
                predicates.add(cb.equal(root.get("category").get("id"), categoryId));
            }

            if (productType != null) {
                predicates.add(cb.equal(root.get("productType"), productType));
            }

            if (alertLevel != null && !alertLevel.trim().isEmpty()) {
                String level = alertLevel.trim().toUpperCase();
                switch (level) {
                    case "OUT_OF_STOCK" -> predicates.add(cb.equal(root.get("stockQuantity"), 0));
                    case "LOW_STOCK" -> predicates.add(cb.and(
                            cb.greaterThanOrEqualTo(root.get("stockQuantity"), 1),
                            cb.lessThanOrEqualTo(root.get("stockQuantity"), 10)
                    ));
                    case "SAFE_STOCK" -> predicates.add(cb.greaterThan(root.get("stockQuantity"), 10));
                }
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<Product> products = productRepository.findAll(spec, pageable);

        return products.map(prod -> {
            boolean isTextbook = false;
            Optional<Book> bookOpt = bookRepository.findById(prod.getId());
            if (bookOpt.isPresent() && Boolean.TRUE.equals(bookOpt.get().getIsTextbook())) {
                isTextbook = true;
            }
            String catName = prod.getCategory() != null ? prod.getCategory().getName() : "-";
            return ProductStockAlertDto.of(prod, catName, isTextbook);
        });
    }

    // =========================================================================
    // Private Validation & Helper Methods
    // =========================================================================

    private void validateForm(GoodsReceiptForm form) {
        if (form == null) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Dữ liệu phiếu nhập không được để trống.");
        }
        if (form.supplierId() == null) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Vui lòng chọn nhà cung cấp.");
        }
        if (form.items() == null || form.items().isEmpty()) {
            throw new AppException(ErrorCode.RECEIPT_EMPTY_ITEMS, "Phiếu nhập kho phải có ít nhất một dòng sản phẩm.");
        }
    }

    /**
     * Validates line item constraints:
     *  - receivedQuantity > 0
     *  - unitCost >= 0 (permits promotional/zero-cost stock items per schema check)
     *  - BR-01-01 import cap on initial batch: <=100 for general items, <=500 for textbooks
     */
    private void validateItemRules(Product product, GoodsReceiptItemForm itemForm) {
        if (itemForm.receivedQuantity() == null || itemForm.receivedQuantity() <= 0) {
            throw new AppException(ErrorCode.RECEIPT_ITEM_QUANTITY_INVALID,
                    "Số lượng nhập của sản phẩm '" + product.getTitle() + "' phải lớn hơn 0.");
        }

        if (itemForm.unitCost() == null || itemForm.unitCost().compareTo(BigDecimal.ZERO) < 0) {
            throw new AppException(ErrorCode.RECEIPT_ITEM_COST_INVALID,
                    "Đơn giá nhập của sản phẩm '" + product.getTitle() + "' không được âm.");
        }

        // BR-01-01 Initial Import Batch Cap Check
        boolean isFirstImport = isProductFirstImport(product.getId(), product.getStockQuantity());
        if (isFirstImport) {
            boolean isTextbook = isProductTextbook(product.getId());
            if (isTextbook) {
                if (itemForm.receivedQuantity() > 500) {
                    throw new AppException(ErrorCode.RECEIPT_IMPORT_LIMIT_EXCEEDED,
                            "Số lượng nhập đợt đầu cho sách giáo khoa (SGK) không được vượt quá 500 đơn vị (Sản phẩm: '"
                                    + product.getTitle() + "', Yêu cầu: " + itemForm.receivedQuantity() + ").");
                }
            } else {
                if (itemForm.receivedQuantity() > 100) {
                    throw new AppException(ErrorCode.RECEIPT_IMPORT_LIMIT_EXCEEDED,
                            "Số lượng nhập đợt đầu cho sản phẩm thông thường không được vượt quá 100 đơn vị (Sản phẩm: '"
                                    + product.getTitle() + "', Yêu cầu: " + itemForm.receivedQuantity() + ").");
                }
            }
        }
    }

    /**
     * Determines whether a product is in its initial import batch.
     * Evaluates true if product currently has zero stock OR has never had a finalized (RECEIVED) receipt.
     */
    private boolean isProductFirstImport(Long productId, Integer currentStock) {
        if (currentStock == null || currentStock == 0) {
            return true;
        }
        return !goodsReceiptItemRepository.hasReceivedReceiptForProduct(productId);
    }

    /**
     * Evaluates whether a product is a general education textbook (SGK).
     */
    private boolean isProductTextbook(Long productId) {
        Optional<Book> bookOpt = bookRepository.findById(productId);
        return bookOpt.isPresent() && Boolean.TRUE.equals(bookOpt.get().getIsTextbook());
    }

    /**
     * Generates a unique, standardized receipt code adhering to VARCHAR(32) schema constraint.
     * Format: GR-yyyyMMdd-XXXXX
     */
    private String generateReceiptCode() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        String datePart = LocalDateTime.now().format(formatter);
        String code;
        int attempts = 0;
        do {
            int randomSuffix = ThreadLocalRandom.current().nextInt(10000, 99999);
            code = "GR-" + datePart + "-" + randomSuffix;
            attempts++;
            if (attempts > 50) {
                code = "GR-" + datePart + "-" + System.currentTimeMillis() % 100000;
                break;
            }
        } while (goodsReceiptRepository.existsByReceiptCode(code));
        return code;
    }

    private String toJson(Object object) {
        if (objectMapper == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize audit log details JSON: {}", e.getMessage());
            return null;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public QuickProductResponse quickCreateProduct(QuickCreateProductRequest request) {
        log.info("Quick-creating product on-the-fly: title='{}', type={}", request.title(), request.productType());

        // 1. Resolve Category
        Category category = null;
        if (request.categoryId() != null) {
            category = categoryRepository.findById(request.categoryId()).orElse(null);
        }
        if (category == null) {
            String defaultCatName = request.productType() == Product.ProductType.BOOK ? "Sách tổng hợp" : "Văn phòng phẩm";
            category = categoryRepository.findAll().stream()
                    .filter(c -> c.getName().equalsIgnoreCase(defaultCatName))
                    .findFirst()
                    .orElseGet(() -> categoryRepository.save(Category.builder()
                            .name(defaultCatName)
                            .isActive(true)
                            .description("Danh mục tự động tạo khi lập phiếu nhập kho")
                            .build()));
        }

        // 2. Resolve Barcode (Theo chuẩn EAN-13 quy định trong tài liệu BR-02-05, UC-10, UC-12)
        String barcode = request.barcode();
        if (barcode != null && !barcode.isBlank()) {
            barcode = barcode.trim();
            if (!BarcodeUtils.isValidEan13(barcode)) {
                throw new AppException(ErrorCode.INVALID_INPUT_DATA,
                        "Mã vạch không đúng định dạng chuẩn EAN-13 (yêu cầu 13 chữ số kèm số kiểm tra hợp lệ per BR-02-05).");
            }
            if (productRepository.existsByBarcode(barcode)) {
                throw new AppException(ErrorCode.INVALID_INPUT_DATA,
                        "Mã vạch " + barcode + " đã tồn tại trong hệ thống (vi phạm BR-02-05).");
            }
        } else {
            String subPrefix = (request.productType() == Product.ProductType.BOOK) ? "8" : "5";
            do {
                barcode = BarcodeUtils.generateEan13(subPrefix);
            } while (productRepository.existsByBarcode(barcode));
        }

        // 3. Persist Product & Validate Price >= Cost (Tránh bán lỗ)
        BigDecimal cost = request.originalCost() != null ? request.originalCost() : BigDecimal.ZERO;
        if (request.price() != null && cost.compareTo(request.price()) > 0) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Giá niêm yết không được thấp hơn giá vốn nhập (tránh bán lỗ).");
        }

        Product product = Product.builder()
                .barcode(barcode)
                .title(request.title().trim())
                .category(category)
                .productType(request.productType())
                .price(request.price())
                .originalCost(cost)
                .weightGrams(200)
                .stockQuantity(0) // Luôn = 0 khi mới tạo; tăng tồn kho khi duyệt phiếu (UC23)
                .isActive(true)
                .build();
        product = productRepository.saveAndFlush(product);

        // 4. Persist subtype (Book or Stationery)
        // Lưu ý: Tuyệt đối không set .productId(product.getId()) tại đây!
        // Do Book/Stationery có @MapsId, việc gán ID trước khi save sẽ khiến Spring Data JPA hiểu nhầm
        // entity này đã tồn tại (isNew() == false) và thực hiện merge() thay vì persist(),
        // dẫn tới lỗi OptimisticLockException: "Row was updated or deleted by another transaction".
        // Khi để productId = null, Hibernate sẽ tự động đồng bộ ID từ product khi persist.
        boolean isTextbook = request.isTextbookSafe();
        if (request.productType() == Product.ProductType.BOOK) {
            Publisher publisher = publisherRepository.findAll().stream().findFirst().orElseGet(() ->
                    publisherRepository.save(Publisher.builder()
                            .name("Nhà xuất bản Giáo Dục")
                            .address("Hà Nội")
                            .build()));
            Book book = Book.builder()
                    .product(product)
                    .isbn(barcode)
                    .publisher(publisher)
                    .isTextbook(isTextbook)
                    .language("Tiếng Việt")
                    .coverType(Book.CoverType.PAPERBACK)
                    .build();
            bookRepository.save(book);
        } else {
            Brand brand = brandRepository.findAll().stream().findFirst().orElseGet(() ->
                    brandRepository.save(Brand.builder()
                            .name("Thiên Long")
                            .originCountry("Việt Nam")
                            .build()));
            Stationery stationery = Stationery.builder()
                    .product(product)
                    .brand(brand)
                    .warrantyMonths(0)
                    .build();
            stationeryRepository.save(stationery);
        }

        int initialQty = request.initialQuantity() != null && request.initialQuantity() > 0 ? request.initialQuantity() : 1;

        return new QuickProductResponse(
                product.getId(),
                product.getTitle(),
                product.getBarcode(),
                product.getPrice(),
                product.getOriginalCost(),
                product.getProductType().name(),
                isTextbook,
                initialQty
        );
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Set<Long> getTextbookProductIds() {
        return bookRepository.findAll().stream()
                .filter(b -> Boolean.TRUE.equals(b.getIsTextbook()))
                .map(Book::getProductId)
                .collect(java.util.stream.Collectors.toSet());
    }
}
