package com.ddagtech.aureliabooks.aspect;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declarative audit annotation marking service methods whose execution should be automatically
 * intercepted and recorded into the immutable audit_logs ledger (UC29 / NFR-10).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Audited {

    /**
     * Audit action descriptor (e.g. "USER_CREATE", "ORDER_RETURN_CONFIRMED", "GOODS_RECEIPT_FINALIZE").
     *
     * @return action string
     */
    String action();

    /**
     * Affected database target table name (e.g. "users", "orders", "goods_receipts").
     *
     * @return table name string
     */
    String targetTable();
}
