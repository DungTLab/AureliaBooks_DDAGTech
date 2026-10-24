package com.ddagtech.aureliabooks.seed;

import com.ddagtech.aureliabooks.dto.analytics.*;
import com.ddagtech.aureliabooks.entity.Product;
import com.ddagtech.aureliabooks.repository.AnalyticsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@DisplayName("Database Seeder & Verification for UC26 & UC27 Analytics & Excel Testing")
class AnalyticsDatabaseSeeder {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AnalyticsRepository analyticsRepository;

    @Test
    @Transactional
    void seedAnalyticsTestData() {
        String enabled = System.getProperty("ENABLE_ANALYTICS_SEEDER", System.getenv("ENABLE_ANALYTICS_SEEDER"));
        if (!"true".equalsIgnoreCase(enabled)) {
            System.out.println("⚠️ [F18 GUARD] AnalyticsDatabaseSeeder is disabled by default to guard QA/live databases. Set ENABLE_ANALYTICS_SEEDER=true to run.");
            return;
        }

        System.out.println("=== START SEEDING REALISTIC ANALYTICS ORDERS ===");

        // 1. Ensure Categories exist
        jdbc.update("""
            INSERT INTO categories (id, name, description, is_active)
            VALUES 
                (101, 'Sách Văn Học & Kỹ Năng', 'Các đầu sách bán chạy nhất', TRUE),
                (102, 'Sách Kinh Tế & Kinh Doanh', 'Sách tài chính, quản trị', TRUE),
                (103, 'Văn Phòng Phẩm & Bút Viết', 'Dụng cụ văn phòng phẩm cao cấp', TRUE),
                (104, 'Sổ Tay & Giấy Vở', 'Vở ghi chép, sổ tay', TRUE)
            ON DUPLICATE KEY UPDATE name = VALUES(name), is_active = TRUE
        """);

        // 2. Ensure Products exist (Books & Stationery)
        jdbc.update("""
            INSERT INTO products (id, barcode, title, category_id, product_type, price, original_cost, weight_grams, stock_quantity, is_active, main_image_url)
            VALUES 
                (1001, '8935244889911', 'Nhà Giả Kim - Paulo Coelho', 101, 'BOOK', 79000.00, 48000.00, 250, 150, TRUE, 'https://images.unsplash.com/photo-1544716278-ca5e3f4abd8c'),
                (1002, '8935244889928', 'Đắc Nhân Tâm - Dale Carnegie', 101, 'BOOK', 96000.00, 58000.00, 320, 85, TRUE, 'https://images.unsplash.com/photo-1544947950-fa07a98d237f'),
                (1003, '8935244889935', 'Tuổi Trẻ Đáng Giá Bao Nhiêu - Rosie Nguyễn', 101, 'BOOK', 88000.00, 52000.00, 280, 45, TRUE, 'https://images.unsplash.com/photo-1512820790803-83ca734da794'),
                (1004, '8935244889942', 'Tư Duy Nhanh Và Chậm - Daniel Kahneman', 102, 'BOOK', 185000.00, 115000.00, 500, 30, TRUE, 'https://images.unsplash.com/photo-1589829085413-56de8ae18c73'),
                (1005, '8935244889959', 'Cây Cam Ngọt Của Tôi - José Mauro', 101, 'BOOK', 108000.00, 68000.00, 300, 60, TRUE, 'https://images.unsplash.com/photo-1495446815901-a7297e633e8d'),
                (1006, '8935244889966', 'Cha Giàu Cha Nghèo - Robert Kiyosaki', 102, 'BOOK', 125000.00, 78000.00, 380, 8, TRUE, 'https://images.unsplash.com/photo-1532012164546-f432f2e3777f'),
                (2001, '8935001122001', 'Hộp 20 Bút Bi Thiên Long Gel 0.5mm Xanh', 103, 'STATIONERY', 95000.00, 62000.00, 180, 220, TRUE, 'https://images.unsplash.com/photo-1583485088034-697b5bc54ccd'),
                (2002, '8935001122002', 'Lốc 5 Cuốn Vở Kẻ Ngang Campus B5 200 Trang', 104, 'STATIONERY', 140000.00, 92000.00, 600, 180, TRUE, 'https://images.unsplash.com/photo-1517842645767-c639042777db'),
                (2003, '8935001122003', 'Bộ Bút Dạ Quang Pastel Deli 6 Màu', 103, 'STATIONERY', 55000.00, 32000.00, 120, 15, TRUE, 'https://images.unsplash.com/photo-1585336261026-61699920b7ee'),
                (2004, '8935001122004', 'Set 5 Cuộn Băng Keo Washi Tape Vintage', 103, 'STATIONERY', 45000.00, 24000.00, 80, 75, TRUE, 'https://images.unsplash.com/photo-1513542789411-b6a5d4f31634'),
                (2005, '8935001122005', 'Máy Gọt Bút Chì Điện Tử Tự Động Deli', 103, 'STATIONERY', 135000.00, 85000.00, 250, 0, TRUE, 'https://images.unsplash.com/photo-1586075010923-2dd4570fb338')
            ON DUPLICATE KEY UPDATE 
                title = VALUES(title),
                price = VALUES(price),
                original_cost = VALUES(original_cost),
                stock_quantity = VALUES(stock_quantity),
                is_active = TRUE
        """);

        // 3. Ensure a Customer User exists (id = 4 or customer@aureliabook.vn)
        jdbc.update("""
            INSERT INTO users (id, role_id, email, password_hash, full_name, phone, auth_provider, is_active)
            VALUES (4, 4, 'customer@aureliabook.vn', '$2a$12$ANd2LdYem20MpRFNMoDd1eJD.1dWA6xNF/Eg3M54pxcraU4VhIIMW', 'Lê Khách Hàng', '0944444444', 'LOCAL', TRUE)
            ON DUPLICATE KEY UPDATE full_name = VALUES(full_name), is_active = TRUE
        """);

        Long customerUserId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'customer@aureliabook.vn'", Long.class);
        if (customerUserId == null) customerUserId = 4L;

        // 4. Clean old seeded test orders to avoid duplication
        jdbc.update("DELETE FROM order_items WHERE order_id IN (SELECT id FROM orders WHERE order_code LIKE 'TEST-ORD-%')");
        jdbc.update("DELETE FROM orders WHERE order_code LIKE 'TEST-ORD-%'");

        // 5. Seed 25 realistic orders with a mix of statuses, payment methods, and dates
        String[] customerNames = {
                "Nguyễn Văn An", "Trần Thị Bích", "Lê Hoàng Cường", "Phạm Minh Đức", "Hoàng Thuỳ Dung",
                "Vũ Quốc Hải", "Đặng Thảo Linh", "Bùi Quang Nhật", "Đỗ Mai Phương", "Hồ Văn Quân"
        };
        String[] customerPhones = {
                "0901234567", "0912345678", "0923456789", "0934567890", "0945678901",
                "0956789012", "0967890123", "0978901234", "0989012345", "0990123456"
        };
        String[] addresses = {
                "Số 12 Chùa Bộc, Đống Đa, Hà Nội",
                "184 Lê Duẩn, Hải Châu, Đà Nẵng",
                "227 Nguyễn Văn Cừ, Quận 5, TP. Hồ Chí Minh",
                "45 Võ Văn Tần, Quận 3, TP. Hồ Chí Minh",
                "Khu Công Nghệ Cao, Quận 9, TP. Thủ Đức"
        };

        Object[][] catalog = {
                {1001L, "Nhà Giả Kim - Paulo Coelho", 79000.0},
                {1002L, "Đắc Nhân Tâm - Dale Carnegie", 96000.0},
                {1003L, "Tuổi Trẻ Đáng Giá Bao Nhiêu - Rosie Nguyễn", 88000.0},
                {1004L, "Tư Duy Nhanh Và Chậm - Daniel Kahneman", 185000.0},
                {1005L, "Cây Cam Ngọt Của Tôi - José Mauro", 108000.0},
                {1006L, "Cha Giàu Cha Nghèo - Robert Kiyosaki", 125000.0},
                {2001L, "Hộp 20 Bút Bi Thiên Long Gel 0.5mm Xanh", 95000.0},
                {2002L, "Lốc 5 Cuốn Vở Kẻ Ngang Campus B5 200 Trang", 140000.0},
                {2003L, "Bộ Bút Dạ Quang Pastel Deli 6 Màu", 55000.0},
                {2004L, "Set 5 Cuộn Băng Keo Washi Tape Vintage", 45000.0},
                {2005L, "Máy Gọt Bút Chì Điện Tử Tự Động Deli", 135000.0}
        };

        LocalDate today = LocalDate.now();

        long totalDeliveredOrders = 0;
        BigDecimal totalDeliveredRevenue = BigDecimal.ZERO;

        for (int i = 1; i <= 25; i++) {
            String code = String.format("TEST-ORD-%04d", i);
            String name = customerNames[(i - 1) % customerNames.length];
            String phone = customerPhones[(i - 1) % customerPhones.length];
            String address = addresses[(i - 1) % addresses.length];

            int daysAgo = (25 - i) % 12; // 0 to 11 days ago
            LocalDateTime createdAt = today.minusDays(daysAgo).atTime(9 + (i % 10), 15 * (i % 4));

            String status;
            String paymentStatus;
            LocalDateTime deliveredAt = null;

            if (i <= 20) {
                status = "DELIVERED";
                paymentStatus = "PAID";
                deliveredAt = createdAt.plusHours(24 + (i % 12));
            } else if (i <= 22) {
                status = "SHIPPING";
                paymentStatus = (i % 2 == 0) ? "PAID" : "UNPAID";
            } else if (i <= 24) {
                status = "CANCELLED";
                paymentStatus = "UNPAID";
            } else {
                status = "PENDING_CONFIRMATION";
                paymentStatus = "UNPAID";
            }

            String paymentMethod = (i % 2 == 0 || i % 5 == 0) ? "VNPAY" : "COD";
            BigDecimal shippingFee = BigDecimal.valueOf(25000);

            int item1Idx = (i * 3) % catalog.length;
            int item2Idx = (i * 7 + 1) % catalog.length;

            int qty1 = 1 + (i % 3);
            double price1 = (double) catalog[item1Idx][2];
            BigDecimal item1Total = BigDecimal.valueOf(price1).multiply(BigDecimal.valueOf(qty1));

            BigDecimal orderTotal = item1Total.add(shippingFee);

            boolean hasSecondItem = (i % 2 == 0);
            int qty2 = 1;
            double price2 = (double) catalog[item2Idx][2];
            if (hasSecondItem) {
                BigDecimal item2Total = BigDecimal.valueOf(price2).multiply(BigDecimal.valueOf(qty2));
                orderTotal = orderTotal.add(item2Total);
            }

            jdbc.update("""
                INSERT INTO orders (
                    order_code, user_id, shipping_recipient_name, shipping_phone, shipping_full_address,
                    shipping_fee, final_total_amount, order_status, payment_method, payment_status,
                    created_at, delivered_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
                    code, customerUserId, name, phone, address,
                    shippingFee, orderTotal, status, paymentMethod, paymentStatus,
                    Timestamp.valueOf(createdAt), deliveredAt != null ? Timestamp.valueOf(deliveredAt) : null
            );

            Long orderId = jdbc.queryForObject("SELECT id FROM orders WHERE order_code = ?", Long.class, code);

            jdbc.update("""
                INSERT INTO order_items (order_id, product_id, quantity, product_name_snapshot, unit_price_snapshot)
                VALUES (?, ?, ?, ?, ?)
            """,
                    orderId, (Long) catalog[item1Idx][0], qty1, (String) catalog[item1Idx][1], BigDecimal.valueOf(price1)
            );

            if (hasSecondItem) {
                jdbc.update("""
                    INSERT INTO order_items (order_id, product_id, quantity, product_name_snapshot, unit_price_snapshot)
                    VALUES (?, ?, ?, ?, ?)
                """,
                        orderId, (Long) catalog[item2Idx][0], qty2, (String) catalog[item2Idx][1], BigDecimal.valueOf(price2)
                );
            }

            if ("DELIVERED".equals(status)) {
                totalDeliveredOrders++;
                totalDeliveredRevenue = totalDeliveredRevenue.add(orderTotal);
            }
        }

        System.out.println("✅ Seeded 25 realistic orders successfully!");
        System.out.println("  • Total DELIVERED orders: " + totalDeliveredOrders);
        System.out.println("  • Total DELIVERED revenue: " + totalDeliveredRevenue + " VND");
        System.out.println("  • Non-delivered orders (SHIPPING/CANCELLED/PENDING): 5 orders (Excluded from revenue)");
    }

    @Test
    void verifyRepositoryQueriesAgainstLiveDatabase() {
        String enabled = System.getProperty("ENABLE_ANALYTICS_SEEDER", System.getenv("ENABLE_ANALYTICS_SEEDER"));
        if (!"true".equalsIgnoreCase(enabled)) {
            System.out.println("⚠️ [F18 GUARD] AnalyticsDatabaseSeeder verification is skipped by default to guard QA/live databases. Set ENABLE_ANALYTICS_SEEDER=true to run.");
            return;
        }

        LocalDateTime start = LocalDate.now().minusDays(30).atStartOfDay();
        LocalDateTime end = LocalDate.now().atTime(23, 59, 59);

        // 1. Check KPI query
        RevenueSummaryKpiDto kpi = analyticsRepository.queryRevenueSummaryKpis(start, end);
        assertThat(kpi).isNotNull();
        assertThat(kpi.getTotalOrders()).isGreaterThanOrEqualTo(20);
        assertThat(kpi.getGrossRevenue()).isGreaterThan(BigDecimal.valueOf(1000000));
        System.out.println("Verified KPI Query: totalOrders=" + kpi.getTotalOrders() + ", grossRevenue=" + kpi.getGrossRevenue());

        // 2. Check Daily trends
        List<DailyRevenueDto> dailyTrends = analyticsRepository.queryDailyRevenueTrends(start, end);
        assertThat(dailyTrends).isNotEmpty();
        System.out.println("Verified Daily Trends: found " + dailyTrends.size() + " days with delivered sales.");

        // 3. Check Payment Breakdown
        PaymentBreakdownDto payment = analyticsRepository.queryPaymentMethodBreakdown(start, end);
        assertThat(payment).isNotNull();
        assertThat(payment.getCodRevenue()).isGreaterThan(BigDecimal.ZERO);
        assertThat(payment.getVnpayRevenue()).isGreaterThan(BigDecimal.ZERO);
        System.out.println("Verified Payment Breakdown: COD=" + payment.getCodRevenue() + " VND, VNPay=" + payment.getVnpayRevenue() + " VND, Electronic Share=" + payment.getElectronicSharePercent() + "%");

        // 4. Check Top Books and Stationery
        List<TopProductSaleDto> topBooks = analyticsRepository.queryTopSellingProducts(Product.ProductType.BOOK, 5, start, end);
        List<TopProductSaleDto> topStat = analyticsRepository.queryTopSellingProducts(Product.ProductType.STATIONERY, 5, start, end);
        assertThat(topBooks).isNotEmpty();
        assertThat(topStat).isNotEmpty();
        System.out.println("Verified Top Books: " + topBooks.size() + " books. Best book: " + topBooks.get(0).getTitle() + " (" + topBooks.get(0).getTotalQuantitySold() + " sold)");
        System.out.println("Verified Top Stationery: " + topStat.size() + " items. Best stationery: " + topStat.get(0).getTitle() + " (" + topStat.get(0).getTotalQuantitySold() + " sold)");

        // 5. Check Stock Valuation
        List<StockValuationItemDto> stock = analyticsRepository.queryStockValuationReport();
        assertThat(stock).isNotEmpty();
        System.out.println("Verified Stock Valuation: " + stock.size() + " products valued.");
    }
}
