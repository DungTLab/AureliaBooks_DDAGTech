document.addEventListener('DOMContentLoaded', () => {
    document.querySelectorAll('[data-print-checklist]').forEach(button => {
        button.addEventListener('click', () => window.print());
    });
    document.querySelectorAll('form[data-confirm-action]').forEach(form => {
        form.addEventListener('submit', event => {
            if (!window.confirm(form.dataset.confirmAction)) event.preventDefault();
        });
    });
});
