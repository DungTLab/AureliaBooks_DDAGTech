// Enhance UI interactions; backend validation and duplicate protection also apply when JavaScript is disabled.
(() => {
    // Prevent repeated submissions from the current page while awaiting a response.
    const forms = document.querySelectorAll('[data-checkout-submit]');
    forms.forEach(form => {
        form.addEventListener('submit', event => {
            if (form.dataset.submitting === 'true') {
                event.preventDefault();
                return;
            }
            form.dataset.submitting = 'true';
            form.setAttribute('aria-busy', 'true');
            // Disable only buttons so address, payment, and token inputs are still submitted.
            Array.from(form.elements).filter(element => element.type === 'submit').forEach(button => {
                button.dataset.originalText = button.innerHTML;
                button.dataset.checkoutDisabled = 'true';
                button.disabled = true;
                button.textContent = 'Đang xử lý…';
            });
        });
    });
    // Reload via GET when the address changes so the server recalculates shipping; include only relevant selections in the URL.
    document.querySelectorAll('[data-checkout-address]').forEach(input => {
        input.addEventListener('change', () => {
            const payment = document.querySelector('input[name="paymentMethod"]:checked');
            const destination = new URL(window.location.href);
            destination.search = '';
            destination.searchParams.set('shippingAddressId', input.value);
            if (payment) destination.searchParams.set('paymentMethod', payment.value);
            const voucher = document.querySelector('#checkoutSelection input[name="voucherCode"]');
            if (voucher?.value) destination.searchParams.set('voucherCode', voucher.value);
            window.location.assign(destination.href);
        });
    });
    // Print the displayed snapshots using the @media print stylesheet.
    document.querySelectorAll('[data-print-order]').forEach(button => {
        button.addEventListener('click', () => window.print());
    });
    // Back/Forward cache may restore a submitted page; re-enable buttons disabled by this script.
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
