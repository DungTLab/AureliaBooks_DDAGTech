package com.ddagtech.aureliabooks.config;

import com.ddagtech.aureliabooks.entity.Category;
import com.ddagtech.aureliabooks.entity.Product;
import com.ddagtech.aureliabooks.entity.Role;
import com.ddagtech.aureliabooks.entity.StockMovementLog;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.repository.CategoryRepository;
import com.ddagtech.aureliabooks.repository.ProductRepository;
import com.ddagtech.aureliabooks.repository.RoleRepository;
import com.ddagtech.aureliabooks.repository.StockMovementLogRepository;
import com.ddagtech.aureliabooks.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Automatically seeds sample master data, test products, and immutable stock movement logs
 * on application startup when running in development environments.
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class DevDataInitializer implements CommandLineRunner {

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final StockMovementLogRepository stockMovementLogRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        try {
            seedRolesAndUsers();
            seedCategoriesAndProducts();
            seedStockMovementLogs();
        } catch (Exception ex) {
            log.warn("DevDataInitializer: Data seeding bypassed or partial: {}", ex.getMessage());
        }
    }

    private void seedRolesAndUsers() {
        if (roleRepository.count() == 0) {
            log.info("DevDataInitializer: Seeding foundational roles...");
            roleRepository.save(Role.builder().roleName("ROLE_ADMIN").description("System Administrator").build());
            roleRepository.save(Role.builder().roleName("ROLE_MANAGER").description("Store Manager").build());
            roleRepository.save(Role.builder().roleName("ROLE_SALE_STAFF").description("Sales Staff").build());
            roleRepository.save(Role.builder().roleName("ROLE_CUSTOMER").description("Shopper").build());
        }

        Role adminRole = roleRepository.findByRoleName("ROLE_ADMIN").orElse(null);
        Role managerRole = roleRepository.findByRoleName("ROLE_MANAGER").orElse(null);
        Role staffRole = roleRepository.findByRoleName("ROLE_SALE_STAFF").orElse(null);

        if (adminRole != null && !userRepository.existsByEmail("admin@aureliabook.vn")) {
            userRepository.save(User.builder()
                    .email("admin@aureliabook.vn")
                    .fullName("Quản Trị Viên (Dev)")
                    .passwordHash(passwordEncoder.encode("Admin@123"))
                    .phone("0988888888")
                    .role(adminRole)
                    .isActive(true)
                    .build());
        }

        if (managerRole != null && !userRepository.existsByEmail("kho.nguyen@aureliabook.vn")) {
            userRepository.save(User.builder()
                    .email("kho.nguyen@aureliabook.vn")
                    .fullName("Nguyễn Văn Kho")
                    .passwordHash(passwordEncoder.encode("Admin@123"))
                    .phone("0977777777")
                    .role(managerRole)
                    .isActive(true)
                    .build());
        }

        if (staffRole != null && !userRepository.existsByEmail("lan.tran@aureliabook.vn")) {
            userRepository.save(User.builder()
                    .email("lan.tran@aureliabook.vn")
                    .fullName("Trần Thị Lan")
                    .passwordHash(passwordEncoder.encode("Admin@123"))
                    .phone("0966666666")
                    .role(staffRole)
                    .isActive(true)
                    .build());
        }
    }

    private void seedCategoriesAndProducts() {
        if (categoryRepository.count() == 0) {
            log.info("DevDataInitializer: Seeding core categories...");
            categoryRepository.save(Category.builder().name("Văn Học - Tiểu Thuyết").description("Sách văn học").isActive(true).build());
            categoryRepository.save(Category.builder().name("Khoa Học - Kỹ Thuật").description("Sách công nghệ").isActive(true).build());
            categoryRepository.save(Category.builder().name("Văn Phòng Phẩm").description("Dụng cụ học tập").isActive(true).build());
        }

        Category bookCat = categoryRepository.findAll().stream()
                .filter(c -> c.getName().contains("Khoa Học") || c.getName().contains("Văn Học"))
                .findFirst().orElse(null);

        Category statCat = categoryRepository.findAll().stream()
                .filter(c -> c.getName().contains("Văn Phòng Phẩm"))
                .findFirst().orElse(bookCat);

        if (productRepository.count() == 0 && bookCat != null) {
            log.info("DevDataInitializer: Seeding catalog products...");

            productRepository.save(Product.builder()
                    .barcode("8935212345678")
                    .title("Clean Code - Nghệ Thuật Viết Mã Sạch")
                    .category(bookCat)
                    .productType(Product.ProductType.BOOK)
                    .price(BigDecimal.valueOf(280000))
                    .originalCost(BigDecimal.valueOf(180000))
                    .weightGrams(450)
                    .stockQuantity(45)
                    .isActive(true)
                    .build());

            productRepository.save(Product.builder()
                    .barcode("8935212345679")
                    .title("Đắc Nhân Tâm")
                    .category(bookCat)
                    .productType(Product.ProductType.BOOK)
                    .price(BigDecimal.valueOf(86000))
                    .originalCost(BigDecimal.valueOf(50000))
                    .weightGrams(320)
                    .stockQuantity(120)
                    .isActive(true)
                    .build());

            productRepository.save(Product.builder()
                    .barcode("8935212345680")
                    .title("Nhà Giả Kim")
                    .category(bookCat)
                    .productType(Product.ProductType.BOOK)
                    .price(BigDecimal.valueOf(79000))
                    .originalCost(BigDecimal.valueOf(45000))
                    .weightGrams(250)
                    .stockQuantity(80)
                    .isActive(true)
                    .build());

            productRepository.save(Product.builder()
                    .barcode("8935212345681")
                    .title("Hoàng Tử Bé (Bản Dịch Minh Họa)")
                    .category(bookCat)
                    .productType(Product.ProductType.BOOK)
                    .price(BigDecimal.valueOf(115000))
                    .originalCost(BigDecimal.valueOf(70000))
                    .weightGrams(280)
                    .stockQuantity(65)
                    .isActive(true)
                    .build());

            if (statCat != null) {
                productRepository.save(Product.builder()
                        .barcode("8935212345682")
                        .title("Bút Ký Cao Cấp Thiên Long TL-079")
                        .category(statCat)
                        .productType(Product.ProductType.STATIONERY)
                        .price(BigDecimal.valueOf(45000))
                        .originalCost(BigDecimal.valueOf(25000))
                        .weightGrams(50)
                        .stockQuantity(200)
                        .isActive(true)
                        .build());

                productRepository.save(Product.builder()
                        .barcode("8935212345683")
                        .title("Sổ Da Bìa Còng Cao Cấp Deli A5")
                        .category(statCat)
                        .productType(Product.ProductType.STATIONERY)
                        .price(BigDecimal.valueOf(85000))
                        .originalCost(BigDecimal.valueOf(48000))
                        .weightGrams(350)
                        .stockQuantity(50)
                        .isActive(true)
                        .build());
            }
        }
    }

    private void seedStockMovementLogs() {
        if (stockMovementLogRepository.count() == 0 && productRepository.count() > 0) {
            log.info("DevDataInitializer: Seeding stock movement ledger logs (FND-03)...");

            Product p1 = productRepository.findByBarcode("8935212345678").orElse(null);
            Product p2 = productRepository.findByBarcode("8935212345679").orElse(null);
            Product p3 = productRepository.findByBarcode("8935212345680").orElse(null);
            Product p4 = productRepository.findByBarcode("8935212345681").orElse(null);
            Product p5 = productRepository.findByBarcode("8935212345682").orElse(null);
            Product p6 = productRepository.findByBarcode("8935212345683").orElse(null);

            User khoUser = userRepository.findByEmail("kho.nguyen@aureliabook.vn").orElse(null);
            User lanUser = userRepository.findByEmail("lan.tran@aureliabook.vn").orElse(null);

            if (p1 != null) {
                stockMovementLogRepository.save(StockMovementLog.builder()
                        .product(p1)
                        .transactionType(StockMovementLog.TransactionType.IMPORT)
                        .quantityChange(50).previousStock(0).currentStock(50)
                        .referenceCode("GRN-202610-001")
                        .performedBy(khoUser)
                        .note("Nhập kho đợt 1 từ Nhà Xuất Bản Trẻ")
                        .createdAt(LocalDateTime.now().minusDays(5))
                        .build());

                stockMovementLogRepository.save(StockMovementLog.builder()
                        .product(p1)
                        .transactionType(StockMovementLog.TransactionType.ORDER_DEDUCT)
                        .quantityChange(-5).previousStock(50).currentStock(45)
                        .referenceCode("ORD-202610-101")
                        .performedBy(lanUser)
                        .note("Xuất kho giao đơn hàng trực tuyến #101")
                        .createdAt(LocalDateTime.now().minusDays(4))
                        .build());
            }

            if (p2 != null) {
                stockMovementLogRepository.save(StockMovementLog.builder()
                        .product(p2)
                        .transactionType(StockMovementLog.TransactionType.IMPORT)
                        .quantityChange(150).previousStock(0).currentStock(150)
                        .referenceCode("GRN-202610-002")
                        .performedBy(khoUser)
                        .note("Nhập bổ sung sách bestseller tựu trường")
                        .createdAt(LocalDateTime.now().minusDays(4))
                        .build());

                stockMovementLogRepository.save(StockMovementLog.builder()
                        .product(p2)
                        .transactionType(StockMovementLog.TransactionType.ORDER_DEDUCT)
                        .quantityChange(-30).previousStock(150).currentStock(120)
                        .referenceCode("ORD-202610-102")
                        .performedBy(lanUser)
                        .note("Xuất kho bán buôn cho thư viện trường học")
                        .createdAt(LocalDateTime.now().minusDays(3))
                        .build());
            }

            if (p3 != null) {
                stockMovementLogRepository.save(StockMovementLog.builder()
                        .product(p3)
                        .transactionType(StockMovementLog.TransactionType.IMPORT)
                        .quantityChange(100).previousStock(0).currentStock(100)
                        .referenceCode("GRN-202610-003")
                        .performedBy(khoUser)
                        .note("Nhập kho ấn bản tái bản kỷ niệm")
                        .createdAt(LocalDateTime.now().minusDays(3))
                        .build());

                stockMovementLogRepository.save(StockMovementLog.builder()
                        .product(p3)
                        .transactionType(StockMovementLog.TransactionType.ORDER_DEDUCT)
                        .quantityChange(-20).previousStock(100).currentStock(80)
                        .referenceCode("ORD-202610-103")
                        .performedBy(lanUser)
                        .note("Xuất kho giao khách hàng thanh toán VNPay")
                        .createdAt(LocalDateTime.now().minusDays(2))
                        .build());

                stockMovementLogRepository.save(StockMovementLog.builder()
                        .product(p3)
                        .transactionType(StockMovementLog.TransactionType.ORDER_DEDUCT)
                        .quantityChange(-3).previousStock(80).currentStock(77)
                        .referenceCode("ORD-202610-104")
                        .performedBy(lanUser)
                        .note("Đơn hàng bán lẻ tại quầy")
                        .createdAt(LocalDateTime.now().minusDays(2))
                        .build());

                stockMovementLogRepository.save(StockMovementLog.builder()
                        .product(p3)
                        .transactionType(StockMovementLog.TransactionType.ORDER_CANCELLED_RESTOCK)
                        .quantityChange(3).previousStock(77).currentStock(80)
                        .referenceCode("ORD-CANCEL-104")
                        .performedBy(khoUser)
                        .note("Khách báo hủy đơn trước khi bưu tá lấy hàng")
                        .createdAt(LocalDateTime.now().minusDays(1))
                        .build());
            }

            if (p4 != null) {
                stockMovementLogRepository.save(StockMovementLog.builder()
                        .product(p4)
                        .transactionType(StockMovementLog.TransactionType.IMPORT)
                        .quantityChange(70).previousStock(0).currentStock(70)
                        .referenceCode("GRN-202610-004")
                        .performedBy(khoUser)
                        .note("Nhập sách thiếu nhi minh họa màu")
                        .createdAt(LocalDateTime.now().minusDays(2))
                        .build());

                stockMovementLogRepository.save(StockMovementLog.builder()
                        .product(p4)
                        .transactionType(StockMovementLog.TransactionType.MANUAL_ADJUSTMENT)
                        .quantityChange(-5).previousStock(70).currentStock(65)
                        .referenceCode("ADJ-202610-001")
                        .performedBy(khoUser)
                        .note("Điều chỉnh kiểm kê: sách bị móp rách gáy khi bốc xếp")
                        .createdAt(LocalDateTime.now().minusDays(1))
                        .build());
            }

            if (p5 != null) {
                stockMovementLogRepository.save(StockMovementLog.builder()
                        .product(p5)
                        .transactionType(StockMovementLog.TransactionType.IMPORT)
                        .quantityChange(200).previousStock(0).currentStock(200)
                        .referenceCode("GRN-202610-005")
                        .performedBy(khoUser)
                        .note("Nhập văn phòng phẩm bút ký Thiên Long")
                        .createdAt(LocalDateTime.now().minusDays(1))
                        .build());
            }

            if (p6 != null) {
                stockMovementLogRepository.save(StockMovementLog.builder()
                        .product(p6)
                        .transactionType(StockMovementLog.TransactionType.IMPORT)
                        .quantityChange(50).previousStock(0).currentStock(50)
                        .referenceCode("GRN-202610-006")
                        .performedBy(khoUser)
                        .note("Nhập sổ da Deli chính hãng")
                        .createdAt(LocalDateTime.now())
                        .build());
            }
            log.info("DevDataInitializer: Seeding stock movement logs completed!");
        }
    }
}
