// Preview totals on the server; the form also works without JavaScript.
(() => {
    const form = document.getElementById("voucherPreview");
    if (!form) return;
    form.elements.voucherCode.addEventListener("blur", () => {
        form.elements.voucherCode.value = form.elements.voucherCode.value.trim().toUpperCase();
        form.elements.voucherCode.dispatchEvent(new Event("input", { bubbles: true }));
    });
    form.addEventListener("submit", () => {
        const payment = document.querySelector('input[name="paymentMethod"]:checked');
        if (payment) form.elements.paymentMethod.value = payment.value;
        form.elements.voucherCode.value = form.elements.voucherCode.value.trim().toUpperCase();
    });
})();
