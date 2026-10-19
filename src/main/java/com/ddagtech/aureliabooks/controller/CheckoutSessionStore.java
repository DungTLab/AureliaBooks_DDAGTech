package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.dto.CheckoutConfirmation;
import com.ddagtech.aureliabooks.dto.response.CheckoutView;
import com.ddagtech.aureliabooks.entity.Order;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Bounded, session-owned reviews; separate tabs cannot replace one another's confirmation. */
@Component
public class CheckoutSessionStore {
    private static final String KEY = CheckoutSessionStore.class.getName() + ".reviews";
    private static final int LIMIT = 8;

    public CheckoutConfirmation save(HttpSession session, Long owner, CheckoutView view,
                                     Order.PaymentMethod method, String voucher) {
        synchronized (session) {
            Map<String, CheckoutConfirmation> reviews = reviews(session);
            for (CheckoutConfirmation review : reviews.values()) {
                if (owner.equals(review.userId()) && view.equals(review.reviewedCheckout())
                        && method == review.paymentMethod() && Objects.equals(voucher, review.voucherCode())) {
                    return review;
                }
            }
            String token = UUID.randomUUID().toString().replace("-", "");
            CheckoutConfirmation review = new CheckoutConfirmation(token, owner, view, method, voucher);
            reviews.put(token, review);
            while (reviews.size() > LIMIT) reviews.remove(reviews.keySet().iterator().next());
            session.setAttribute(KEY, reviews);
            return review;
        }
    }

    public CheckoutConfirmation find(HttpSession session, String token, Long owner) {
        if (session == null || token == null) return null;
        synchronized (session) {
            CheckoutConfirmation review = reviews(session).get(token);
            return review != null && owner.equals(review.userId()) ? review : null;
        }
    }

    public void remove(HttpSession session, String token) {
        if (session == null) return;
        synchronized (session) {
            Map<String, CheckoutConfirmation> reviews = reviews(session);
            reviews.remove(token);
            session.setAttribute(KEY, reviews);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, CheckoutConfirmation> reviews(HttpSession session) {
        Object value = session.getAttribute(KEY);
        return value instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, CheckoutConfirmation>) map) : new LinkedHashMap<>();
    }
}
