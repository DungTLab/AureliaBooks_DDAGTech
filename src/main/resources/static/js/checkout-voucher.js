// Each preview submits the other applied code so applying one scope preserves the other.
(() => {
    document.querySelectorAll("[data-voucher-preview]").forEach((form) => {
        const input = form.querySelector("[data-voucher-code]");
        input.addEventListener("blur", () => {
            input.value = input.value.trim().toUpperCase();
            input.dispatchEvent(new Event("input", { bubbles: true }));
        });
        form.addEventListener("submit", () => {
            const payment = document.querySelector('input[name="paymentMethod"]:checked');
            if (payment) form.elements.paymentMethod.value = payment.value;
            input.value = input.value.trim().toUpperCase();
        });
    });
})();
