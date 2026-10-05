package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.response.StockMovementLogResponse;
import com.ddagtech.aureliabooks.entity.Product;
import com.ddagtech.aureliabooks.entity.StockMovementLog;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.ProductRepository;
import com.ddagtech.aureliabooks.repository.StockMovementLogRepository;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.service.StockLedgerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * FND-03 Double-entry Stock Ledger Service Engine Implementation.
 * Owner: Nguyễn Trần Đức Anh.
 * Enforces transaction-safe stock balance updates and strictly immutable append-only logs.
 * Adheres to:
 * - BR-01-04 (Stock Ledger Balance Verification: current_stock = previous_stock + quantity_change)
 * - BR-04-03 (Strict Prevention of Negative Stock)
 * - BR-08-02 (Tamper-Evident Audit Ledger Immutability)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockLedgerServiceImpl implements StockLedgerService {

    private final StockMovementLogRepository stockMovementLogRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public void append(Long productId, StockMovementLog.TransactionType type,
                       int quantityChange, int previousStock, int currentStock,
                       String referenceCode, Long performedByUserId, String note) {
        if (productId == null) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Mã sản phẩm không được để trống.");
        }
        if (type == null) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Loại giao dịch biến động kho không được để trống.");
        }
        if (referenceCode == null || referenceCode.trim().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Mã chứng từ tham chiếu (referenceCode) không được để trống.");
        }
        if (quantityChange == 0) {
            throw new AppException(ErrorCode.INVALID_QUANTITY_CHANGE, "Biến động số lượng kho phải khác 0 (quantity_change <> 0).");
        }
        if (previousStock < 0 || currentStock < 0) {
            throw new AppException(ErrorCode.NEGATIVE_STOCK_NOT_ALLOWED, "Số lượng tồn kho không được phép âm (chk_slog_stocks).");
        }
        if ((long) previousStock + quantityChange != (long) currentStock) {
            throw new AppException(ErrorCode.STOCK_LEDGER_BALANCE_MISMATCH,
                    String.format("Vi phạm cân bằng thẻ kho (BR-01-04): Tồn mới (%d) != Tồn cũ (%d) + Biến động (%d)",
                            currentStock, previousStock, quantityChange));
        }

        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new AppException(ErrorCode.PRODUCT_NOT_FOUND, "Không tìm thấy sản phẩm có ID: " + productId));

        if (Boolean.FALSE.equals(product.getIsActive())) {
            throw new AppException(ErrorCode.PRODUCT_INACTIVE, "Sản phẩm hiện đang bị vô hiệu hóa hoặc ngừng kinh doanh.");
        }

        User performedBy = null;
        if (performedByUserId != null) {
            performedBy = userRepository.findById(performedByUserId).orElse(null);
        }

        StockMovementLog movementLog = StockMovementLog.builder()
                .product(product)
                .transactionType(type)
                .quantityChange(quantityChange)
                .previousStock(previousStock)
                .currentStock(currentStock)
                .referenceCode(referenceCode)
                .performedBy(performedBy)
                .note(note)
                .build();

        stockMovementLogRepository.save(movementLog);
        log.info("Stock ledger appended: productId={}, type={}, change={}, prev={}, current={}, ref={}",
                productId, type, quantityChange, previousStock, currentStock, referenceCode);
    }

    @Override
    @Transactional
    public StockMovementLog processMovement(Long productId, StockMovementLog.TransactionType type,
                                            int quantityChange, String referenceCode, Long performedByUserId, String note) {
        if (productId == null) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Mã sản phẩm không được để trống.");
        }
        if (type == null) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Loại giao dịch biến động kho không được để trống.");
        }
        if (referenceCode == null || referenceCode.trim().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Mã chứng từ tham chiếu (referenceCode) không được để trống.");
        }
        if (quantityChange == 0) {
            throw new AppException(ErrorCode.INVALID_QUANTITY_CHANGE, "Biến động số lượng kho phải khác 0.");
        }

        // 1. Lock product row to prevent concurrent race conditions
        Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new AppException(ErrorCode.PRODUCT_NOT_FOUND, "Không tìm thấy sản phẩm có ID: " + productId));

        // Sanity check: inactive product cannot undergo stock movements
        if (Boolean.FALSE.equals(product.getIsActive())) {
            log.warn("Stock movement rejected: product {} is inactive.", productId);
            throw new AppException(ErrorCode.PRODUCT_INACTIVE, "Sản phẩm hiện đang bị vô hiệu hóa hoặc ngừng kinh doanh.");
        }

        int previousStock = product.getStockQuantity() != null ? product.getStockQuantity() : 0;
        long resultingStock = (long) previousStock + quantityChange;

        // Overflow check (Integer.MAX_VALUE)
        if (resultingStock > Integer.MAX_VALUE) {
            log.error("Stock overflow detected for product {}: previous={}, change={}, resulting={}",
                    productId, previousStock, quantityChange, resultingStock);
            throw new AppException(ErrorCode.STOCK_OVERFLOW,
                    String.format("Số lượng tồn kho vượt quá giới hạn tối đa (%d).", Integer.MAX_VALUE));
        }

        // 2. Strict non-negative inventory check (BR-04-03, chk_prod_stock)
        if (resultingStock < 0) {
            log.warn("Stock underflow rejected for product {}: previousStock={}, requestedChange={}, resultingStock={}",
                    productId, previousStock, quantityChange, resultingStock);
            throw new AppException(ErrorCode.INSUFFICIENT_STOCK,
                    String.format("Không đủ tồn kho. Tồn hiện tại: %d, yêu cầu giảm: %d.", previousStock, Math.abs(quantityChange)));
        }

        int currentStock = (int) resultingStock;

        // Invariant double-check: current_stock = previous_stock + quantity_change
        if (currentStock != previousStock + quantityChange) {
            throw new AppException(ErrorCode.STOCK_LEDGER_BALANCE_MISMATCH,
                    "Vi phạm cân bằng thẻ kho (current_stock = previous_stock + quantity_change)");
        }

        // 3. Update physical product stock
        product.setStockQuantity(currentStock);
        productRepository.save(product);

        // 4. Resolve acting user if provided
        User performedBy = null;
        if (performedByUserId != null) {
            performedBy = userRepository.findById(performedByUserId).orElse(null);
        }

        // 5. Append immutable ledger row
        StockMovementLog movementLog = StockMovementLog.builder()
                .product(product)
                .transactionType(type)
                .quantityChange(quantityChange)
                .previousStock(previousStock)
                .currentStock(currentStock)
                .referenceCode(referenceCode)
                .performedBy(performedBy)
                .note(note)
                .build();

        StockMovementLog savedLog = stockMovementLogRepository.save(movementLog);
        log.info("Stock ledger movement processed: product='{}', prev={}, change={}, current={}, type={}, ref={}",
                product.getTitle(), previousStock, quantityChange, currentStock, type, referenceCode);

        return savedLog;
    }

    @Override
    @Transactional
    public StockMovementLog recordImport(Long productId, int quantity, String referenceCode, Long performedByUserId, String note) {
        if (quantity <= 0) {
            throw new AppException(ErrorCode.INVALID_QUANTITY_CHANGE, "Số lượng nhập kho phải lớn hơn 0.");
        }
        return processMovement(productId, StockMovementLog.TransactionType.IMPORT, quantity, referenceCode, performedByUserId, note);
    }

    @Override
    @Transactional
    public StockMovementLog recordOrderDeduct(Long productId, int quantity, String referenceCode, Long performedByUserId, String note) {
        if (quantity <= 0) {
            throw new AppException(ErrorCode.INVALID_QUANTITY_CHANGE, "Số lượng trừ kho phải lớn hơn 0.");
        }
        return processMovement(productId, StockMovementLog.TransactionType.ORDER_DEDUCT, -quantity, referenceCode, performedByUserId, note);
    }

    @Override
    @Transactional
    public StockMovementLog recordOrderCancelledRestock(Long productId, int quantity, String referenceCode, Long performedByUserId, String note) {
        if (quantity <= 0) {
            throw new AppException(ErrorCode.INVALID_QUANTITY_CHANGE, "Số lượng hoàn kho hủy đơn phải lớn hơn 0.");
        }
        return processMovement(productId, StockMovementLog.TransactionType.ORDER_CANCELLED_RESTOCK, quantity, referenceCode, performedByUserId, note);
    }

    @Override
    @Transactional
    public StockMovementLog recordManualAdjustment(Long productId, int quantityChange, String referenceCode, Long performedByUserId, String note) {
        if (quantityChange == 0) {
            throw new AppException(ErrorCode.INVALID_QUANTITY_CHANGE, "Biến động số lượng kiểm kê phải khác 0.");
        }
        return processMovement(productId, StockMovementLog.TransactionType.MANUAL_ADJUSTMENT, quantityChange, referenceCode, performedByUserId, note);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StockMovementLogResponse> getLedgerLogs(Pageable pageable) {
        return stockMovementLogRepository.findAllWithDetails(pageable).map(StockMovementLogResponse::fromEntity);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StockMovementLogResponse> getLedgerLogsWithFilter(
            StockMovementLog.TransactionType transactionType,
            Long productId,
            LocalDateTime startDate,
            LocalDateTime endDate,
            Pageable pageable) {
        return stockMovementLogRepository.findWithFilters(transactionType, productId, startDate, endDate, pageable)
                .map(StockMovementLogResponse::fromEntity);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StockMovementLogResponse> getLogsByReferenceCode(String referenceCode) {
        return stockMovementLogRepository.findByReferenceCode(referenceCode)
                .stream()
                .map(StockMovementLogResponse::fromEntity)
                .collect(Collectors.toList());
    }
}
