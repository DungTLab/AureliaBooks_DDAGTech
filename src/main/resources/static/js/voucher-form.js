'use strict';

document.querySelectorAll('[data-voucher-form]').forEach((form) => {
    const type = form.querySelector('[data-voucher-type]');
    const percentage = form.querySelector('[data-percentage-field]');
    const fixed = form.querySelector('[data-fixed-field]');
    const cap = form.querySelector('[name="maxDiscountAmount"]');
    const update = () => {
        const isPercentage = type.value === 'PERCENTAGE';
        percentage.hidden = !isPercentage;
        fixed.hidden = isPercentage;
        percentage.querySelector('input').disabled = !isPercentage;
        percentage.querySelector('input').required = isPercentage;
        fixed.querySelector('input').disabled = isPercentage;
        fixed.querySelector('input').required = !isPercentage;
        cap.required = isPercentage;
    };
    type.addEventListener('change', update);
    update();
});
