(() => {
    'use strict';

    document.querySelectorAll('input[data-birthdate]').forEach(input => {
        function validate() {
            const value = input.value;
            if (!value) { input.setCustomValidity(''); return; }
            const parts = /^(\d{2})\/(\d{2})\/(\d{4})$/.exec(value);
            if (!parts) {
                input.setCustomValidity('Vui lòng nhập ngày/tháng/năm, ví dụ: 31/12/2000.');
                return;
            }
            const [, day, month, year] = parts;
            const date = new Date(0);
            date.setUTCFullYear(Number(year), Number(month) - 1, Number(day));
            const valid = date.getUTCFullYear() === Number(year)
                && date.getUTCMonth() === Number(month) - 1 && date.getUTCDate() === Number(day);
            const iso = `${year}-${month}-${day}`;
            input.setCustomValidity(!valid ? 'Ngày sinh không hợp lệ.'
                : iso > input.dataset.maxDate ? 'Ngày sinh phải là ngày trong quá khứ.' : '');
        }

        input.addEventListener('input', () => {
            if (/^\d{8}$/.test(input.value)) {
                input.value = `${input.value.slice(0, 2)}/${input.value.slice(2, 4)}/${input.value.slice(4)}`;
            }
            validate();
        });
        input.addEventListener('change', validate);
        input.addEventListener('blur', validate);
        validate();
        input.dispatchEvent(new Event('input', { bubbles: true }));
    });
})();
