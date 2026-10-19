package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.dto.response.CheckoutView;
import com.ddagtech.aureliabooks.entity.Order;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class CheckoutSessionStoreTest {
    private final CheckoutSessionStore store = new CheckoutSessionStore();
    private CheckoutView view(long address) {
        return new CheckoutView(List.of(), List.of(), address, BigDecimal.ONE, BigDecimal.ZERO,
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.valueOf(2));
    }

    @Test void repeatedReviewReusesTokenAndAnotherTabDoesNotOverwriteIt() {
        var session = new MockHttpSession();
        var first = store.save(session, 1L, view(10L), Order.PaymentMethod.COD, null);
        assertThat(store.save(session, 1L, view(10L), Order.PaymentMethod.COD, null)).isEqualTo(first);
        var second = store.save(session, 1L, view(11L), Order.PaymentMethod.COD, null);
        assertThat(first.checkoutToken()).isNotEqualTo(second.checkoutToken());
        assertThat(store.find(session, first.checkoutToken(), 1L)).isEqualTo(first);
        assertThat(store.find(session, second.checkoutToken(), 1L)).isEqualTo(second);
    }

    @Test void reviewCannotBeReadByAnotherOwnerOrAnotherSession() {
        var session = new MockHttpSession();
        var review = store.save(session, 1L, view(10L), Order.PaymentMethod.COD, null);
        assertThat(store.find(session, review.checkoutToken(), 2L)).isNull();
        assertThat(store.find(new MockHttpSession(), review.checkoutToken(), 1L)).isNull();
    }

    @Test void pendingReviewsAreBoundedAndRemovingOneDoesNotAffectAnother() {
        var session = new MockHttpSession();
        var oldest = store.save(session, 1L, view(1L), Order.PaymentMethod.COD, null);
        for (long i = 2; i <= 8; i++) store.save(session, 1L, view(i), Order.PaymentMethod.COD, null);
        var newest = store.save(session, 1L, view(9L), Order.PaymentMethod.COD, null);
        assertThat(store.find(session, oldest.checkoutToken(), 1L)).isNull();
        assertThat(store.find(session, newest.checkoutToken(), 1L)).isEqualTo(newest);
        store.remove(session, newest.checkoutToken());
        assertThat(store.find(session, newest.checkoutToken(), 1L)).isNull();
    }
}
