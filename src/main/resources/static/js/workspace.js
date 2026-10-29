(() => {
    "use strict";
    const menu = document.querySelector("[data-workspace-menu]");
    if (menu) {
        const desktop = matchMedia("(min-width: 1024px)");
        const sync = () => { menu.open = desktop.matches; };
        sync();
        desktop.addEventListener("change", sync);
    }

    const pwdForm = document.querySelector("form[data-password-change]");
    const modal = document.getElementById("internal-password-modal");
    const submitBtn = pwdForm ? pwdForm.querySelector('button[type="submit"]') : null;
    const errBox = modal ? modal.querySelector("[data-modal-error]") : null;
    const errText = modal ? modal.querySelector("[data-modal-error-text]") : null;
    let inFlight = false;

    const restoreButton = () => {
        inFlight = false;
        if (pwdForm) {
            delete pwdForm.dataset.inFlight;
            delete pwdForm.dataset.submitting;
            pwdForm.removeAttribute("aria-busy");
            pwdForm.dispatchEvent(new CustomEvent("submit-complete"));
        }
        if (submitBtn) {
            submitBtn.disabled = false;
            submitBtn.textContent = "Lưu thay đổi";
        }
    };

    document.addEventListener("click", (e) => {
        const openBtn = e.target.closest("[data-open-modal]");
        if (openBtn) {
            const targetId = openBtn.getAttribute("data-open-modal");
            const targetModal = document.getElementById(targetId);
            if (targetModal) {
                targetModal.classList.remove("hidden");
                targetModal.setAttribute("aria-hidden", "false");
                const firstInput = targetModal.querySelector("input:not([type=hidden])");
                if (firstInput) firstInput.focus();
            }
        }
        const closeBtn = e.target.closest("[data-close-modal]");
        if (closeBtn) {
            const targetId = closeBtn.getAttribute("data-close-modal");
            const targetModal = document.getElementById(targetId);
            if (targetModal) {
                targetModal.classList.add("hidden");
                targetModal.setAttribute("aria-hidden", "true");
                if (targetId === "internal-password-modal" && pwdForm) {
                    pwdForm.reset();
                    if (errBox) errBox.classList.add("hidden");
                    restoreButton();
                    pwdForm.dispatchEvent(new Event("input", { bubbles: true }));
                }
            }
        }
    });

    document.addEventListener("keydown", (e) => {
        if (e.key === "Escape") {
            document.querySelectorAll("[role=dialog]:not(.hidden)").forEach((targetModal) => {
                targetModal.classList.add("hidden");
                targetModal.setAttribute("aria-hidden", "true");
                if (targetModal.id === "internal-password-modal" && pwdForm) {
                    pwdForm.reset();
                    if (errBox) errBox.classList.add("hidden");
                    restoreButton();
                    pwdForm.dispatchEvent(new Event("input", { bubbles: true }));
                }
            });
        }
    });

    if (pwdForm) {
        pwdForm.addEventListener("submit", async (e) => {
            e.preventDefault();

            if (inFlight || pwdForm.dataset.inFlight === "true" || pwdForm.dataset.submitting === "true") {
                return;
            }

            if (!pwdForm.checkValidity()) {
                return;
            }

            inFlight = true;
            pwdForm.dataset.inFlight = "true";
            pwdForm.dataset.submitting = "true";
            pwdForm.setAttribute("aria-busy", "true");

            if (errBox) errBox.classList.add("hidden");

            if (submitBtn) {
                submitBtn.disabled = true;
                submitBtn.textContent = "Đang lưu...";
            }

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
                const data = await response.json().catch(() => ({}));
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
                    restoreButton();
                }
            } catch (err) {
                if (errBox && errText) {
                    errText.textContent = "Không thể kết nối máy chủ. Vui lòng thử lại.";
                    errBox.classList.remove("hidden");
                }
                restoreButton();
            }
        });
    }
})();
