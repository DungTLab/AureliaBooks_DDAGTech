const initializeCart = () => {
    document.querySelectorAll('[data-quantity-control]').forEach(control => {
        const input = control.querySelector('input[name="quantity"]');
        const buttons = control.querySelectorAll('[data-quantity-step]');
        const minimum = Number(input.min || 1);
        const maximum = Number(input.max || 99);
        const refresh = () => {
            const quantity = Number(input.value);
            buttons.forEach(button => {
                const step = Number(button.dataset.quantityStep);
                button.disabled = input.disabled || !Number.isInteger(quantity)
                    || input.value === '' || (step < 0 ? quantity <= minimum : quantity >= maximum);
            });
        };
        buttons.forEach(button => {
            button.addEventListener('click', () => {
                const quantity = Number(input.value);
                if (!Number.isInteger(quantity) || input.value === '') return;
                input.value = Math.min(maximum, Math.max(minimum, quantity + Number(button.dataset.quantityStep)));
                input.dispatchEvent(new Event('input', { bubbles: true }));
                if (input.form?.hasAttribute('data-cart-quantity')) input.form.requestSubmit();
            });
        });
        input.addEventListener('input', refresh);
        if (input.form?.hasAttribute('data-cart-quantity')) {
            input.addEventListener('change', () => input.form.requestSubmit());
            input.addEventListener('keydown', event => {
                if (event.key !== 'Enter' || event.isComposing) return;
                event.preventDefault();
                input.form.requestSubmit();
            });
        }
        refresh();
    });

    const dialog = document.getElementById('removeCartItemDialog');
    let pendingRemoval = null;
    document.querySelectorAll('form[data-cart-form]').forEach(form => {
        form.addEventListener('submit', event => {
            if (form.hasAttribute('data-cart-remove') && dialog && typeof dialog.showModal === 'function'
                && form.dataset.confirmed !== 'true') {
                event.preventDefault();
                pendingRemoval = form;
                document.getElementById('removeCartProductTitle').textContent = form.dataset.productTitle;
                dialog.showModal();
                return;
            }
            if (form.dataset.submitting === 'true') {
                event.preventDefault();
                return;
            }
            form.dataset.submitting = 'true';
            form.querySelectorAll('button').forEach(button => { button.disabled = true; });
            form.setAttribute('aria-busy', 'true');
        });
    });
    if (dialog) {
        document.getElementById('cancelCartRemoval').addEventListener('click', () => dialog.close());
        dialog.addEventListener('close', () => { pendingRemoval = null; });
        document.getElementById('confirmCartRemoval').addEventListener('click', () => {
            const form = pendingRemoval;
            if (!form) return;
            pendingRemoval = null;
            form.dataset.confirmed = 'true';
            dialog.close();
            form.requestSubmit();
        });
    }
    window.addEventListener('pageshow', event => {
        if (event.persisted) window.location.reload();
    });
};

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initializeCart, { once: true });
} else {
    initializeCart();
}
