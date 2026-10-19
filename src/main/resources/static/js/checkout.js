// Hỗ trợ thao tác giao diện; backend vẫn kiểm tra dữ liệu và chống đặt trùng khi JavaScript bị tắt.
(() => {
    // Chặn bấm gửi liên tiếp trong cùng trang trong khi chờ phản hồi.
    const forms = document.querySelectorAll('[data-checkout-submit]');
    forms.forEach(form => {
        form.addEventListener('submit', event => {
            if (form.dataset.submitting === 'true') {
                event.preventDefault();
                return;
            }
            form.dataset.submitting = 'true';
            form.setAttribute('aria-busy', 'true');
            // Chỉ vô hiệu hóa nút; input địa chỉ/phương thức/token vẫn phải được gửi về server.
            Array.from(form.elements).filter(element => element.type === 'submit').forEach(button => {
                button.dataset.originalText = button.innerHTML;
                button.dataset.checkoutDisabled = 'true';
                button.disabled = true;
                button.textContent = 'Đang xử lý…';
            });
        });
    });
    // Đổi địa chỉ bằng GET để server tính lại cước; chỉ đưa lựa chọn cần thiết vào URL.
    document.querySelectorAll('[data-checkout-address]').forEach(input => {
        input.addEventListener('change', () => {
            const payment = document.querySelector('input[name="paymentMethod"]:checked');
            const destination = new URL(window.location.href);
            destination.search = '';
            destination.searchParams.set('shippingAddressId', input.value);
            if (payment) destination.searchParams.set('paymentMethod', payment.value);
            window.location.assign(destination.href);
        });
    });
    // Bản in dùng snapshot đang hiển thị cùng stylesheet @media print.
    document.querySelectorAll('[data-print-order]').forEach(button => {
        button.addEventListener('click', () => window.print());
    });
    // Trình duyệt có thể khôi phục trang bằng Back/Forward cache; mở lại các nút do script đã khóa.
    window.addEventListener('pageshow', () => {
        forms.forEach(form => {
            delete form.dataset.submitting;
            form.removeAttribute('aria-busy');
            Array.from(form.elements).filter(element => element.dataset.checkoutDisabled === 'true').forEach(button => {
                button.disabled = false;
                button.innerHTML = button.dataset.originalText;
                delete button.dataset.checkoutDisabled;
            });
        });
    });
})();
