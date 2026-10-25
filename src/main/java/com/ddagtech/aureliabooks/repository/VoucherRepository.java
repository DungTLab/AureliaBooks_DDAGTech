package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.Voucher;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.Optional;

public interface VoucherRepository extends JpaRepository<Voucher, Long> {
    boolean existsByCode(String code);
    Optional<Voucher> findByCode(String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from Voucher v where v.code = :code")
    Optional<Voucher> findByCodeForUpdate(@Param("code") String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from Voucher v where v.id = :id")
    Optional<Voucher> findByIdForUpdate(@Param("id") Long id);

    @Query("select v from Voucher v where (:active is null or v.active = :active) and v.code like :pattern escape '!'")
    Page<Voucher> search(@Param("pattern") String pattern, @Param("active") Boolean active, Pageable pageable);

    @Modifying(flushAutomatically = true)
    @Query("update Voucher v set v.usageLimit = v.usageLimit - 1 where v.id = :id and v.usageLimit > 0 and v.active = true and v.startDate <= :now and v.endDate > :now")
    int consumeRemainingUse(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true)
    @Query("update Voucher v set v.usageLimit = v.usageLimit + 1 where v.id = :id")
    int restoreRemainingUse(@Param("id") Long id);
}
