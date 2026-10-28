(() => {
    "use strict";
    const menu = document.querySelector("[data-workspace-menu]");
    if (menu) {
        const desktop = matchMedia("(min-width: 1024px)");
        const sync = () => { menu.open = desktop.matches; };
        sync();
        desktop.addEventListener("change", sync);
    }

    document.addEventListener("click", (e) => {
        const openBtn = e.target.closest("[data-open-modal]");
        if (openBtn) {
            const targetId = openBtn.getAttribute("data-open-modal");
            const modal = document.getElementById(targetId);
            if (modal) {
                modal.classList.remove("hidden");
                modal.setAttribute("aria-hidden", "false");
                const firstInput = modal.querySelector("input:not([type=hidden])");
                if (firstInput) firstInput.focus();
            }
        }
        const closeBtn = e.target.closest("[data-close-modal]");
        if (closeBtn) {
            const targetId = closeBtn.getAttribute("data-close-modal");
            const modal = document.getElementById(targetId);
            if (modal) {
                modal.classList.add("hidden");
                modal.setAttribute("aria-hidden", "true");
            }
        }
    });

    document.addEventListener("keydown", (e) => {
        if (e.key === "Escape") {
            document.querySelectorAll("[role=dialog]:not(.hidden)").forEach((modal) => {
                modal.classList.add("hidden");
                modal.setAttribute("aria-hidden", "true");
            });
        }
    });

    const pwdForm = document.querySelector("form[data-password-change]");
    if (pwdForm) {
        pwdForm.addEventListener("submit", async (e) => {
            e.preventDefault();
            const modal = document.getElementById("internal-password-modal");
            const errBox = modal ? modal.querySelector("[data-modal-error]") : null;
            const errText = modal ? modal.querySelector("[data-modal-error-text]") : null;
            if (errBox) errBox.classList.add("hidden");

            const formData = new FormData(pwdForm);
            try {
                const response = await fetch(pwdForm.action, {
                    method: "POST",
                    headers: {
                        "Accept": "application/json",
                        "X-Requested-With": "XMLHttpRequest"
                    },
                    body: formData
                });
                const data = await response.json();
                if (response.ok && data.success) {
                    if (modal) {
                        modal.classList.add("hidden");
                        modal.setAttribute("aria-hidden", "true");
                    }
                    window.location.reload();
                } else {
                    if (errBox && errText) {
                        errText.textContent = data.message || "Đã xảy ra lỗi khi đổi mật khẩu.";
                        errBox.classList.remove("hidden");
                    }
                }
            } catch (err) {
                if (errBox && errText) {
                    errText.textContent = "Không thể kết nối máy chủ. Vui lòng thử lại.";
                    errBox.classList.remove("hidden");
                }
            }
        });
    }
})();
