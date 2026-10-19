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

/** Lưu tối đa 8 bản xác nhận theo session; mỗi tab dùng token riêng để không ghi đè nhau. */
@Component
public class CheckoutSessionStore {
    private static final String KEY = CheckoutSessionStore.class.getName() + ".reviews";
    private static final int LIMIT = 8;

    /** Tái sử dụng token khi nội dung không đổi; sinh token mới nếu khách chọn thông tin khác. */
    public CheckoutConfirmation save(HttpSession session, Long owner, CheckoutView view,
                                     Order.PaymentMethod method, String voucher) {
        // Tuần tự hóa các request cùng session để việc tìm/tạo token không bị tranh chấp.
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
            // LinkedHashMap giữ thứ tự thêm; loại bản cũ nhất khi vượt giới hạn.
            while (reviews.size() > LIMIT) reviews.remove(reviews.keySet().iterator().next());
            session.setAttribute(KEY, reviews);
            return review;
        }
    }

    /** Token chỉ có hiệu lực trong session đang giữ nó và với đúng chủ sở hữu. */
    public CheckoutConfirmation find(HttpSession session, String token, Long owner) {
        if (session == null || token == null) return null;
        synchronized (session) {
            CheckoutConfirmation review = reviews(session).get(token);
            return review != null && owner.equals(review.userId()) ? review : null;
        }
    }

    /** Xóa đúng bản đã dùng hoặc hết hiệu lực, giữ nguyên các bản xác nhận ở tab khác. */
    public void remove(HttpSession session, String token) {
        if (session == null) return;
        synchronized (session) {
            Map<String, CheckoutConfirmation> reviews = reviews(session);
            reviews.remove(token);
            session.setAttribute(KEY, reviews);
        }
    }

    /** Sao chép map trước khi sửa rồi setAttribute lại; không sửa trực tiếp map đang gắn trong session. */
    @SuppressWarnings("unchecked")
    private Map<String, CheckoutConfirmation> reviews(HttpSession session) {
        Object value = session.getAttribute(KEY);
        return value instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, CheckoutConfirmation>) map) : new LinkedHashMap<>();
    }
}
