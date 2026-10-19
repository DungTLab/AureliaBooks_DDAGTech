package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.Order;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdWithLock(@Param("id") Long id);

    /** Tra token trước khi đọc giỏ để nhận diện đơn đã commit khi khách gửi lại cùng request. */
    Optional<Order> findByOrderCode(String orderCode);

    /** Ràng buộc chủ sở hữu ngay trong truy vấn; nạp items để mapper dựng trang kết quả trong transaction. */
    @EntityGraph(attributePaths = "items")
    Optional<Order> findByOrderCodeAndUser_Id(
            String orderCode,
            Long userId
    );
}
