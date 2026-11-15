package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.config.VNPayConfig;
import com.ddagtech.aureliabooks.dto.response.VNPayIpnResponse;
import com.ddagtech.aureliabooks.entity.Order;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.repository.OrderRepository;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.service.impl.VNPayServiceImpl;
import com.ddagtech.aureliabooks.util.VNPayUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test suite for {@link VNPayService#processIpnWebhook(Map, String)}.
 * Verifies signature verification, order reconciliation, stock restoration, and gateway status codes.
 */
@ExtendWith(MockitoExtension.class)
class VNPayIpnUnitCaseTest {

    private static final String TEST_SECRET = "UNIT_TEST_HMAC_SECRET_KEY_0123456789ABCDEF";
    private static final String TMN_CODE = "AURELIA01";
    private static final String TXN_REF = "ORD-20261107-001";
    private static final String SOURCE_IP = "203.0.113.10";

    @Spy
    private VNPayConfig vnPayConfig = new VNPayConfig();

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private StockMutationService stockMutationService;

    @Mock
    private OrderVoucherReleasePort orderVoucherReleasePort;

    @Mock
    private AuditLogService auditLogService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private VNPayServiceImpl vnPayService;

    private User customer;
    private Order pendingOrder;

    @BeforeEach
    void setUp() {
        vnPayConfig.setTmnCode(TMN_CODE);
        vnPayConfig.setHashSecret(TEST_SECRET);
        vnPayConfig.setPayUrl("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html");
        vnPayConfig.setReturnUrl("http://localhost:8080/payment/vnpay-return");
        vnPayConfig.setApiUrl("https://sandbox.vnpayment.vn/merchant_webapi/api/transaction");
        vnPayConfig.setVersion("2.1.0");
        vnPayConfig.setCommand("pay");
        vnPayConfig.setOrderType("other");
        vnPayConfig.setExpireMinutes(15);

        customer = User.builder()
                .id(1L)
                .email("giac@aureliabook.vn")
                .phone("0912345678")
                .fullName("Tran Huynh Giac")
                .build();

        pendingOrder = Order.builder()
                .id(100L)
                .orderCode(TXN_REF)
                .vnpayTxnRef(TXN_REF)
                .user(customer)
                .paymentMethod(Order.PaymentMethod.VNPAY)
                .orderStatus(Order.OrderStatus.PENDING_PAYMENT)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .finalTotalAmount(new BigDecimal("250000.00"))
                .stockDeductedAt(LocalDateTime.now())
                .build();
    }

    private Map<String, String> signedParams(String txnRef, String amountMinor, String responseCode, String txStatus) {
        Map<String, String> params = new TreeMap<>();
        params.put("vnp_Amount", amountMinor);
        params.put("vnp_BankCode", "NCB");
        params.put("vnp_OrderInfo", "Thanh toan don hang " + txnRef);
        params.put("vnp_PayDate", "20261107103000");
        params.put("vnp_ResponseCode", responseCode);
        params.put("vnp_TmnCode", TMN_CODE);
        params.put("vnp_TransactionNo", "14598201");
        if (txStatus != null) {
            params.put("vnp_TransactionStatus", txStatus);
        }
        params.put("vnp_TxnRef", txnRef);
        String hashData = VNPayUtil.buildHashData(params);
        params.put("vnp_SecureHash", VNPayUtil.hmacSHA512(TEST_SECRET, hashData));
        return params;
    }

    @Test
    @DisplayName("Should update order to confirmed and paid on verified gateway success")
    void shouldUpdateOrderAndPaymentOnVerifiedSuccess() {
        when(orderRepository.findByVnpayTxnRefWithLock(TXN_REF)).thenReturn(Optional.of(pendingOrder));

        Map<String, String> params = signedParams(TXN_REF, "25000000", "00", "00");
        VNPayIpnResponse response = vnPayService.processIpnWebhook(params, SOURCE_IP);

        assertThat(response.rspCode()).isEqualTo("00");
        assertThat(response.message()).isEqualTo("Confirm Success");
        assertThat(pendingOrder.getOrderStatus()).isEqualTo(Order.OrderStatus.PENDING_CONFIRMATION);
        assertThat(pendingOrder.getPaymentStatus()).isEqualTo(Order.PaymentStatus.PAID);
        assertThat(pendingOrder.getVnpayBankCode()).isEqualTo("NCB");
        verify(orderRepository).save(pendingOrder);
        verify(stockMutationService, never()).deductOrder(anyLong());
    }

    @Test
    @DisplayName("Should cancel order, restore stock and release voucher on verified payment failure")
    void shouldCancelOrderRestoreStockAndReleaseVoucherOnFailure() {
        when(orderRepository.findByVnpayTxnRefWithLock(TXN_REF)).thenReturn(Optional.of(pendingOrder));
        org.mockito.Mockito.doAnswer(invocation -> {
            pendingOrder.setOrderStatus(Order.OrderStatus.CANCELLED);
            return null;
        }).when(stockMutationService).restoreCancelledOrder(anyLong(), any());

        Map<String, String> params = signedParams(TXN_REF, "25000000", "24", "02");
        VNPayIpnResponse response = vnPayService.processIpnWebhook(params, SOURCE_IP);

        assertThat(response.rspCode()).isEqualTo("00");
        assertThat(response.message()).isEqualTo("Confirm Success");
        assertThat(pendingOrder.getOrderStatus()).isEqualTo(Order.OrderStatus.CANCELLED);
        assertThat(pendingOrder.getPaymentStatus()).isEqualTo(Order.PaymentStatus.UNPAID);
        verify(stockMutationService).restoreCancelledOrder(pendingOrder.getId(), customer.getId());
        verify(orderVoucherReleasePort).releaseOrderVoucherIfPresent(pendingOrder.getId());
    }

    @Test
    @DisplayName("Should reconcile payment status without reactivating order when late success arrives after cancellation")
    void shouldReconcilePaymentStatusWhenLateSuccessArrivesAfterCancellation() {
        pendingOrder.setOrderStatus(Order.OrderStatus.CANCELLED);
        pendingOrder.setPaymentStatus(Order.PaymentStatus.UNPAID);
        when(orderRepository.findByVnpayTxnRefWithLock(TXN_REF)).thenReturn(Optional.of(pendingOrder));

        Map<String, String> params = signedParams(TXN_REF, "25000000", "00", "00");
        VNPayIpnResponse response = vnPayService.processIpnWebhook(params, SOURCE_IP);

        assertThat(response.rspCode()).isEqualTo("00");
        assertThat(response.message()).isEqualTo("Confirm Success");
        assertThat(pendingOrder.getOrderStatus()).isEqualTo(Order.OrderStatus.CANCELLED);
        assertThat(pendingOrder.getPaymentStatus()).isEqualTo(Order.PaymentStatus.PAID);
        verify(orderRepository).save(pendingOrder);
        verify(stockMutationService, never()).deductOrder(anyLong());
    }

    @Test
    @DisplayName("Should return 02 Order already confirmed on duplicate IPN for settled order")
    void shouldReturnOrderAlreadyConfirmedOnDuplicateIpn() {
        pendingOrder.setOrderStatus(Order.OrderStatus.PENDING_CONFIRMATION);
        pendingOrder.setPaymentStatus(Order.PaymentStatus.PAID);
        pendingOrder.setVnpayTxnRef(TXN_REF);
        pendingOrder.recordSettledVnpayAttempt(TXN_REF, "14598201");
        when(orderRepository.findByVnpayTxnRefWithLock(TXN_REF)).thenReturn(Optional.of(pendingOrder));

        Map<String, String> params = signedParams(TXN_REF, "25000000", "00", "00");
        VNPayIpnResponse response = vnPayService.processIpnWebhook(params, SOURCE_IP);

        assertThat(response.rspCode()).isEqualTo("02");
        assertThat(response.message()).isEqualTo("Order already confirmed");
        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should return 99 Invalid Request Parameters when parameter map is null or empty")
    void shouldReturnInvalidParamsWhenParamMapIsNullOrEmpty() {
        VNPayIpnResponse resNull = vnPayService.processIpnWebhook(null, SOURCE_IP);
        assertThat(resNull.rspCode()).isEqualTo("99");
        assertThat(resNull.message()).isEqualTo("Invalid Request Parameters");

        VNPayIpnResponse resEmpty = vnPayService.processIpnWebhook(Collections.emptyMap(), SOURCE_IP);
        assertThat(resEmpty.rspCode()).isEqualTo("99");
        assertThat(resEmpty.message()).isEqualTo("Invalid Request Parameters");
    }

    @Test
    @DisplayName("Should return 97 Invalid Checksum when merchant credentials are not configured")
    void shouldReturnInvalidChecksumWhenGatewayCredentialsMissing() {
        vnPayConfig.setTmnCode("");
        vnPayConfig.setHashSecret("");

        Map<String, String> params = new TreeMap<>();
        params.put("vnp_TxnRef", TXN_REF);
        params.put("vnp_Amount", "25000000");

        VNPayIpnResponse response = vnPayService.processIpnWebhook(params, SOURCE_IP);

        assertThat(response.rspCode()).isEqualTo("97");
        assertThat(response.message()).isEqualTo("Invalid Checksum");
        verify(orderRepository, never()).findByVnpayTxnRefWithLock(any());
    }

    @Test
    @DisplayName("Should return 97 Invalid Checksum when HMAC-SHA512 signature does not match")
    void shouldReturnInvalidChecksumWhenSignatureIsInvalid() {
        Map<String, String> params = signedParams(TXN_REF, "25000000", "00", "00");
        params.put("vnp_SecureHash", "deadbeef0000invalidhash");

        VNPayIpnResponse response = vnPayService.processIpnWebhook(params, SOURCE_IP);

        assertThat(response.rspCode()).isEqualTo("97");
        assertThat(response.message()).isEqualTo("Invalid Checksum");
        verify(orderRepository, never()).findByVnpayTxnRefWithLock(any());
    }

    @Test
    @DisplayName("Should return 97 Invalid Checksum when terminal code does not match configuration")
    void shouldReturnInvalidChecksumWhenTerminalCodeMismatched() {
        Map<String, String> params = new TreeMap<>();
        params.put("vnp_Amount", "25000000");
        params.put("vnp_BankCode", "NCB");
        params.put("vnp_OrderInfo", "Thanh toan don hang " + TXN_REF);
        params.put("vnp_PayDate", "20261107103000");
        params.put("vnp_ResponseCode", "00");
        params.put("vnp_TmnCode", "OTHER_TMN");
        params.put("vnp_TransactionNo", "14598201");
        params.put("vnp_TransactionStatus", "00");
        params.put("vnp_TxnRef", TXN_REF);
        String hashData = VNPayUtil.buildHashData(params);
        params.put("vnp_SecureHash", VNPayUtil.hmacSHA512(TEST_SECRET, hashData));

        VNPayIpnResponse response = vnPayService.processIpnWebhook(params, SOURCE_IP);

        assertThat(response.rspCode()).isEqualTo("97");
        assertThat(response.message()).isEqualTo("Invalid Checksum");
        verify(orderRepository, never()).findByVnpayTxnRefWithLock(any());
    }

    @Test
    @DisplayName("Should return 01 Order not Found when txnRef does not exist in database")
    void shouldReturnOrderNotFoundWhenTxnRefDoesNotExist() {
        when(orderRepository.findByVnpayTxnRefWithLock("ORD-UNKNOWN")).thenReturn(Optional.empty());

        Map<String, String> params = signedParams("ORD-UNKNOWN", "25000000", "00", "00");
        VNPayIpnResponse response = vnPayService.processIpnWebhook(params, SOURCE_IP);

        assertThat(response.rspCode()).isEqualTo("01");
        assertThat(response.message()).isEqualTo("Order not Found");
    }

    @Test
    @DisplayName("Should return 04 Invalid Amount when transaction amount does not match order amount")
    void shouldReturnInvalidAmountWhenAmountMismatched() {
        when(orderRepository.findByVnpayTxnRefWithLock(TXN_REF)).thenReturn(Optional.of(pendingOrder));

        Map<String, String> params = signedParams(TXN_REF, "24999900", "00", "00");
        VNPayIpnResponse response = vnPayService.processIpnWebhook(params, SOURCE_IP);

        assertThat(response.rspCode()).isEqualTo("04");
        assertThat(response.message()).isEqualTo("Invalid Amount");
        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should return 04 Invalid Amount when amount parameter is non-numeric")
    void shouldReturnInvalidAmountWhenAmountIsNotNumeric() {
        when(orderRepository.findByVnpayTxnRefWithLock(TXN_REF)).thenReturn(Optional.of(pendingOrder));

        Map<String, String> params = signedParams(TXN_REF, "ABC", "00", "00");
        VNPayIpnResponse response = vnPayService.processIpnWebhook(params, SOURCE_IP);

        assertThat(response.rspCode()).isEqualTo("04");
        assertThat(response.message()).isEqualTo("Invalid Amount");
        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should return 99 when gateway returns indeterminate or incomplete transaction status")
    void shouldReturnIndeterminateStatusWhenTransactionStatusIncomplete() {
        when(orderRepository.findByVnpayTxnRefWithLock(TXN_REF)).thenReturn(Optional.of(pendingOrder));

        Map<String, String> params = signedParams(TXN_REF, "25000000", "00", "01");
        VNPayIpnResponse response = vnPayService.processIpnWebhook(params, SOURCE_IP);

        assertThat(response.rspCode()).isEqualTo("99");
        assertThat(response.message()).isEqualTo("Indeterminate or incomplete transaction status");
        verify(orderRepository, never()).save(any());
        verify(stockMutationService, never()).restoreCancelledOrder(anyLong(), any());
    }
}
