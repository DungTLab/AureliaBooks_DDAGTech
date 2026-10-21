'use strict';

document.querySelectorAll('[data-checkout-voucher]').forEach((panel) => {
    const input = panel.querySelector('[data-voucher-code]');
    const requestPreview = () => {
        input.value = input.value.trim().toUpperCase();
        panel.dispatchEvent(new CustomEvent('voucher:preview', {
            bubbles: true,
            detail: {code: input.value}
        }));
    };
    panel.querySelector('[data-voucher-preview]').addEventListener('click', requestPreview);
    input.addEventListener('keydown', (event) => {
        if (event.key === 'Enter') {
            event.preventDefault();
            requestPreview();
        }
    });
});
