(() => {
    const forms = document.querySelectorAll('[data-checkout-submit]');
    forms.forEach(form => {
        form.addEventListener('submit', event => {
            if (form.dataset.submitting === 'true') {
                event.preventDefault();
                return;
            }
            form.dataset.submitting = 'true';
            form.setAttribute('aria-busy', 'true');
            // Disable only buttons; selected form values must still be submitted.
            Array.from(form.elements).filter(element => element.type === 'submit').forEach(button => {
                button.dataset.originalText = button.innerHTML;
                button.dataset.checkoutDisabled = 'true';
                button.disabled = true;
                button.textContent = 'Đang xử lý…';
            });
        });
    });
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
    document.querySelectorAll('[data-print-order]').forEach(button => {
        button.addEventListener('click', () => window.print());
    });
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
