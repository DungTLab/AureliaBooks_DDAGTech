(() => {
    'use strict';
    document.querySelectorAll('[data-otp-timer]').forEach(container => {
        const now = Date.now();
        const expiry = now + Math.max(0, Number(container.dataset.expiresSeconds) || 0) * 1000;
        const resend = now + Math.max(0, Number(container.dataset.resendSeconds) || 0) * 1000;
        const tick = () => {
            const left = Math.max(0, Math.ceil((expiry - Date.now()) / 1000));
            const wait = Math.max(0, Math.ceil((resend - Date.now()) / 1000));
            container.querySelectorAll('[data-otp-expiry]').forEach(node => { node.textContent = left; });
            container.querySelectorAll('[data-otp-resend-seconds]').forEach(node => { node.textContent = wait; });
            container.querySelectorAll('[data-otp-cooldown]').forEach(node => { node.hidden = wait === 0; });
            container.querySelectorAll('[data-otp-resend]').forEach(button => { button.disabled = wait > 0; });
            container.querySelectorAll('[data-otp-code-form]').forEach(form => {
                form.dataset.expired = String(left === 0);
                if (left === 0) form.querySelectorAll('button[type="submit"]').forEach(button => { button.disabled = true; });
            });
        };
        tick();
        const interval = setInterval(tick, 250);
        window.addEventListener('pageshow', tick);
        document.addEventListener('visibilitychange', tick);
        window.addEventListener('pagehide', event => { if (!event.persisted) clearInterval(interval); });
    });
})();
