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

/** Keep up to eight reviews per session; separate tokens prevent browser tabs from overwriting each other. */
@Component
public class CheckoutSessionStore {
    private static final String KEY = CheckoutSessionStore.class.getName() + ".reviews";
    private static final int LIMIT = 8;

    /** Reuse the token for unchanged selections and issue a new token when review details change. */
    public CheckoutConfirmation save(HttpSession session, Long owner, CheckoutView view,
                                     Order.PaymentMethod method, String voucher) {
        // Serialize requests within the same session to prevent competing token lookups and creation.
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
            // LinkedHashMap preserves insertion order, allowing the oldest review to be evicted at the limit.
            while (reviews.size() > LIMIT) reviews.remove(reviews.keySet().iterator().next());
            session.setAttribute(KEY, reviews);
            return review;
        }
    }

    /** A token is valid only within the session holding it and for its recorded owner. */
    public CheckoutConfirmation find(HttpSession session, String token, Long owner) {
        if (session == null || token == null) return null;
        synchronized (session) {
            CheckoutConfirmation review = reviews(session).get(token);
            return review != null && owner.equals(review.userId()) ? review : null;
        }
    }

    /** Remove only the completed or invalid review, preserving reviews in other tabs. */
    public void remove(HttpSession session, String token) {
        if (session == null) return;
        synchronized (session) {
            Map<String, CheckoutConfirmation> reviews = reviews(session);
            reviews.remove(token);
            session.setAttribute(KEY, reviews);
        }
    }

    /** Copy the map before editing and set the session attribute again instead of mutating the stored map directly. */
    @SuppressWarnings("unchecked")
    private Map<String, CheckoutConfirmation> reviews(HttpSession session) {
        Object value = session.getAttribute(KEY);
        return value instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, CheckoutConfirmation>) map) : new LinkedHashMap<>();
    }
}
