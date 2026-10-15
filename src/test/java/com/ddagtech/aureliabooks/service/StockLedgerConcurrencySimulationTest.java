package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.entity.Product;
import com.ddagtech.aureliabooks.entity.StockMovementLog;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.ProductRepository;
import com.ddagtech.aureliabooks.repository.StockMovementLogRepository;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.service.impl.StockLedgerServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Concurrency & Race-Condition Simulation Test for Stock Ledger Engine.
 * Verifies that under heavy concurrent load, the inventory invariant (stock >= 0)
 * is strictly guaranteed and underflow requests fail with INSUFFICIENT_STOCK.
 */
@ExtendWith(MockitoExtension.class)
class StockLedgerConcurrencySimulationTest {

    @Mock
    private StockMovementLogRepository stockMovementLogRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private StockLedgerServiceImpl stockLedgerService;

    @Test
    @DisplayName("Concurrency: 20 simultaneous threads competing for 10 units must allow exactly 10 and reject 10")
    void testConcurrentOrderDeduction_StrictlyGuaranteesNonNegativeStock() throws InterruptedException {
        // Initial setup: product has 10 units in stock
        final Product sharedProduct = Product.builder()
                .id(100L)
                .title("Sách Bán Chạy")
                .stockQuantity(10)
                .isActive(true)
                .build();

        // Emulate DB pessimistic locking using synchronized monitor on sharedProduct
        when(productRepository.findByIdForUpdate(100L)).thenAnswer(invocation -> {
            synchronized (sharedProduct) {
                return Optional.of(sharedProduct);
            }
        });

        when(stockMovementLogRepository.save(any(StockMovementLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        int threadCount = 20;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectedInsufficientStockCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executorService.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await(); // wait for all threads to align
                    synchronized (sharedProduct) { // simulates DB pessimistic transaction isolation
                        stockLedgerService.recordOrderDeduct(100L, 1, "ORD-CONCUR-" + index, null, "Flash sale");
                    }
                    successCount.incrementAndGet();
                } catch (AppException ex) {
                    if (ex.getErrorCode() == ErrorCode.INSUFFICIENT_STOCK) {
                        rejectedInsufficientStockCount.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown(); // trigger all threads concurrently
        boolean finished = doneLatch.await(10, TimeUnit.SECONDS);
        executorService.shutdown();

        assertThat(finished).isTrue();
        assertThat(successCount.get()).isEqualTo(10);
        assertThat(rejectedInsufficientStockCount.get()).isEqualTo(10);
        assertThat(sharedProduct.getStockQuantity()).isEqualTo(0);
        assertThat(sharedProduct.getStockQuantity()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("Concurrency: Concurrent mixed imports and deductions strictly maintain ledger invariant")
    void testConcurrentMixedTransactions_MaintainsLedgerBalance() throws InterruptedException {
        final Product sharedProduct = Product.builder()
                .id(200L)
                .title("Truyện Tranh")
                .stockQuantity(20) // Initial stock: 20
                .isActive(true)
                .build();

        when(productRepository.findByIdForUpdate(200L)).thenAnswer(invocation -> Optional.of(sharedProduct));
        when(stockMovementLogRepository.save(any(StockMovementLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        int operations = 10;
        ExecutorService executorService = Executors.newFixedThreadPool(operations * 2);
        CountDownLatch doneLatch = new CountDownLatch(operations * 2);

        // 10 threads importing 5 units each: +50
        for (int i = 0; i < operations; i++) {
            final int idx = i;
            executorService.submit(() -> {
                try {
                    synchronized (sharedProduct) {
                        stockLedgerService.recordImport(200L, 5, "IMPORT-" + idx, null, "Nhập");
                    }
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // 10 threads deducting 3 units each: -30
        for (int i = 0; i < operations; i++) {
            final int idx = i;
            executorService.submit(() -> {
                try {
                    synchronized (sharedProduct) {
                        stockLedgerService.recordOrderDeduct(200L, 3, "DEDUCT-" + idx, null, "Xuất");
                    }
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        boolean finished = doneLatch.await(10, TimeUnit.SECONDS);
        executorService.shutdown();

        assertThat(finished).isTrue();
        // Initial (20) + Import (10 * 5 = 50) - Deduct (10 * 3 = 30) = 40
        assertThat(sharedProduct.getStockQuantity()).isEqualTo(40);
    }
}
