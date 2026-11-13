package com.ddagtech.aureliabooks.util;

import com.ddagtech.aureliabooks.dto.response.AuditLogResponse;
import com.ddagtech.aureliabooks.entity.AuditLog;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.mapper.AuditLogMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AuditLogLabelFormatter} and system actor naming in {@link AuditLogMapper}.
 */
class AuditLogLabelFormatterTest {

    private final AuditLogLabelFormatter formatter = AuditLogLabelFormatter.INSTANCE;
    private final AuditLogMapper mapper = new AuditLogMapper();

    @ParameterizedTest
    @ValueSource(strings = {
            "USER_CREATE",
            "USER_STATUS_TOGGLE",
            "USER_ROLE_UPDATE",
            "RESET_STAFF_PASSWORD",
            "STAFF_PASSWORD_CHANGED",
            "LOGIN_FAILED",
            "LOGOUT",
            "ACCOUNT_REGISTERED",
            "PASSWORD_RESET",
            "PASSWORD_CHANGED",
            "PASSWORD_CREATED",
            "GOOGLE_LINKED",
            "GOOGLE_UNLINKED",
            "PROFILE_EMAIL_CHANGED",
            "PROFILE_PHONE_CHANGED",
            "ADMIN_BOOTSTRAPPED",
            "VNPAY_IPN_CALLBACK",
            "VNPAY_PAYMENT_SUCCESS",
            "VNPAY_PAYMENT_FAILED_CANCELLED",
            "VNPAY_IPN_INDETERMINATE",
            "VNPAY_LATE_SUCCESS_RECONCILIATION",
            "VNPAY_DUPLICATE_PAYMENT_RECONCILIATION",
            "VNPAY_TIMEOUT_RECONCILED_PAID",
            "VNPAY_TIMEOUT_CANCELLED_RESTOCK",
            "ORDER_PLACED",
            "ORDER_STATUS_CHANGED",
            "UPDATE_ORDER_STATUS",
            "ORDER_CANCELLED_BY_CUSTOMER",
            "ORDER_DEDUCT_STOCK",
            "ORDER_CANCELLED_RESTOCK",
            "ORDER_RETURN_CONFIRMED",
            "ORDER_RETURN_INSPECTION_CONFIRMED",
            "ORDER_REFUND_CONFIRMED",
            "RECEIPT_CREATE_DRAFT",
            "RECEIPT_UPDATE_DRAFT",
            "RECEIPT_DELETE_DRAFT",
            "RECEIVE_GOODS",
            "GOODS_RECEIPT_FINALIZE",
            "VOUCHER_CREATED",
            "VOUCHER_STATUS_CHANGED",
            "VOUCHER_REDEEMED",
            "VOUCHER_RELEASED"
    })
    @DisplayName("Known audit action codes map to concise Vietnamese labels rather than raw SCREAMING_SNAKE_CASE")
    void formatAction_KnownCodes_ReturnsLocalizedVietnameseLabel(String action) {
        String formatted = formatter.formatAction(" " + action + " ");

        assertThat(formatted).isNotBlank();
        assertThat(formatted).isNotEqualTo(action);
        assertThat(formatted).doesNotContain("_");
        assertThat(formatter.badgeClasses(action)).isNotBlank();
        assertThat(formatter.badgeIcon(action)).startsWith("bi-");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "users",
            "orders",
            "goods_receipts",
            "vouchers",
            "products",
            "suppliers",
            "categories",
            "shipping_addresses",
            "roles",
            "audit_logs"
    })
    @DisplayName("Known target table names map to localized Vietnamese entity labels")
    void formatTargetTable_KnownTables_ReturnsLocalizedVietnameseLabel(String tableName) {
        String formatted = formatter.formatTargetTable(" " + tableName.toUpperCase() + " ");

        assertThat(formatted).isNotBlank();
        assertThat(formatted).isNotEqualToIgnoringCase(tableName);
        assertThat(formatted).doesNotContain("_");
    }

    @Test
    @DisplayName("Null, blank, and unmapped audit codes degrade gracefully without errors")
    void formatters_HandleNullBlankAndUnknownGracefully() {
        assertThat(formatter.formatAction(null)).isEqualTo("Không xác định");
        assertThat(formatter.formatAction("   ")).isEqualTo("Không xác định");
        assertThat(formatter.formatAction("CUSTOM_AUDIT_EVENT")).isEqualTo("CUSTOM_AUDIT_EVENT");
        assertThat(formatter.badgeClasses("CUSTOM_AUDIT_EVENT")).contains("bg-slate-100");
        assertThat(formatter.badgeIcon("CUSTOM_AUDIT_EVENT")).isEqualTo("bi-activity");

        assertThat(formatter.formatTargetTable(null)).isEqualTo("Không xác định");
        assertThat(formatter.formatTargetTable("  ")).isEqualTo("Không xác định");
        assertThat(formatter.formatTargetTable("custom_table")).isEqualTo("custom_table");
    }

    @Test
    @DisplayName("AuditLogMapper and AuditLogResponse default null actor to Vietnamese automated system label")
    void mapperAndResponse_NullUser_UsesVietnameseSystemLabel() {
        AuditLog systemLog = new AuditLog();
        systemLog.setId(10L);
        systemLog.setUser(null);
        systemLog.setAction("VNPAY_TIMEOUT_CANCELLED_RESTOCK");
        systemLog.setTargetTable("orders");
        systemLog.setTargetId(42L);
        systemLog.setDetailsJson("{\"orderId\":42}");
        systemLog.setCreatedAt(LocalDateTime.of(2026, 11, 13, 10, 0));

        AuditLogResponse fromMapper = mapper.toResponse(systemLog);
        AuditLogResponse fromFactory = AuditLogResponse.fromEntity(systemLog);

        assertThat(fromMapper.operatorName()).isEqualTo("Hệ thống / Tự động");
        assertThat(fromFactory.operatorName()).isEqualTo("Hệ thống / Tự động");

        User admin = User.builder().id(1L).fullName("Nguyễn Văn Quản Trị").email("admin@aureliabook.vn").build();
        systemLog.setUser(admin);
        assertThat(mapper.toResponse(systemLog).operatorName()).isEqualTo("Nguyễn Văn Quản Trị");
    }
}
