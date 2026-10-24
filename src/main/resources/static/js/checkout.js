// Enhance UI interactions; backend validation and duplicate protection also apply when JavaScript is disabled.
(() => {
    // Reload via GET when the address changes so the server recalculates shipping; include only relevant selections in the URL.
    document.querySelectorAll("[data-checkout-address]").forEach((input) => {
        input.addEventListener("change", () => {
            const payment = document.querySelector('input[name="paymentMethod"]:checked');
            const destination = new URL(window.location.href);
            destination.search = "";
            destination.searchParams.set("shippingAddressId", input.value);
            if (payment) destination.searchParams.set("paymentMethod", payment.value);
            const voucher = document.querySelector('#checkoutSelection input[name="voucherCode"]');
            if (voucher?.value) destination.searchParams.set("voucherCode", voucher.value);
            window.location.assign(destination.href);
        });
    });
    // Print the displayed snapshots using the @media print stylesheet.
    document.querySelectorAll("[data-print-order]").forEach((button) => {
        button.addEventListener("click", () => window.print());
    });
})();
