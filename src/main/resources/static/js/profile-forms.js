(() => {
    "use strict";

    document.querySelectorAll("[data-recovery-request]").forEach((form) => {
        const method = form.querySelector('[name="channel"]');
        const identifier = form.querySelector('[name="identifier"]');
        const sync = () => {
            const phone = method.value === "SMS";
            identifier.type = phone ? "tel" : "email";
            identifier.maxLength = phone ? 10 : 100;
            identifier.autocomplete = phone ? "tel" : "email";
            identifier.inputMode = phone ? "tel" : "email";
            if (phone) identifier.pattern = "0[0-9]{9}";
            else identifier.removeAttribute("pattern");
            form.querySelector("[data-recovery-label]").textContent = phone
                ? "Số điện thoại đã đăng ký"
                : "Email đã đăng ký";
        };
        method.addEventListener("change", () => {
            identifier.value = "";
            sync();
            form.querySelector("#recoveryError").textContent = "";
        });
        sync();
    });

    document.querySelectorAll("[data-contact-reauth]").forEach((form) => {
        const sync = () => {
            const passwordMode = form.querySelector('[name="method"]:checked')?.value === "PASSWORD";
            const password = form.querySelector('[name="password"]');
            password.required = passwordMode;
            password.disabled = !passwordMode;
            if (!passwordMode) password.value = "";
            form.querySelector("[data-current-password]").classList.toggle("hidden", !passwordMode);
            form.querySelector("[data-reauth-forgot-password]").classList.toggle("hidden", !passwordMode);
            form.querySelector("[data-reauth-submit]").textContent = passwordMode
                ? "Xác thực tài khoản"
                : "Gửi mã xác thực";
        };
        form.addEventListener("change", sync);
        sync();
    });

    document.querySelectorAll("[data-valid-form]").forEach((form) => {
        const submit = form.querySelector('button[type="submit"]');
        const fields = Array.from(form.querySelectorAll('input:not([type="hidden"]), select, textarea'));
        const feedback = form.querySelector("[data-form-feedback]");
        const password = form.querySelector('[name="newPassword"], [name="password"]');
        const confirmation = form.querySelector('[name="confirmPassword"], [name="confirmation"]');
        const notes = new Map(),
            touched = new Set();
        let submitting = false;
        if (form.hasAttribute("data-password-change")) {
            [password, confirmation].forEach((field) => {
                const note = document.createElement("p");
                note.id = `${field.id}-feedback`;
                note.className = "text-sm text-red-700 mt-2 invisible min-h-[1.25rem]";
                (field.closest("[data-password-field]") || field).insertAdjacentElement("afterend", note);
                field.setAttribute("aria-describedby", note.id);
                notes.set(field, note);
                field.addEventListener("blur", () => {
                    touched.add(field);
                    update();
                });
            });
        }

        function update() {
            if (form.hasAttribute("data-password-change")) {
                const strong =
                    Array.from(password.value).length >= 8 &&
                    /\p{Lu}/u.test(password.value) &&
                    /[0-9]/.test(password.value) &&
                    /[^\p{L}\p{N}\s]/u.test(password.value);
                const message =
                    password.value && !strong
                        ? "Mật khẩu từ 8 ký tự, có chữ hoa, số và ký tự đặc biệt."
                        : new TextEncoder().encode(password.value).length > 72
                          ? "Mật khẩu tối đa 72 byte UTF-8."
                          : "";
                password.setCustomValidity(message);
                confirmation.setCustomValidity(
                    confirmation.value && confirmation.value !== password.value ? "Xác nhận mật khẩu chưa khớp." : "",
                );
                notes.forEach((note, field) => {
                    const invalid = (touched.has(field) || field.value.length > 0) && !field.validity.valid;
                    note.textContent = field.validity.valueMissing
                        ? "Vui lòng nhập trường này."
                        : field.validationMessage;
                    note.classList.toggle("invisible", !invalid);
                    field.classList.toggle("border-red-600", invalid);
                    field.setAttribute("aria-invalid", String(invalid));
                });
                if (feedback)
                    feedback.textContent =
                        message ||
                        (confirmation.value && confirmation.value !== password.value
                            ? "Xác nhận mật khẩu chưa khớp."
                            : "") ||
                        (fields.every((field) => field.validity.valid)
                            ? "Thông tin hợp lệ. Bạn có thể tiếp tục."
                            : "Hoàn tất các trường bắt buộc để tiếp tục.");
            }
            const active = fields.filter((field) => !field.matches(":disabled"));
            submit.disabled =
                submitting ||
                form.dataset.expired === "true" ||
                active.length === 0 ||
                active.some((field) => !field.validity.valid || (field.required && !field.value.trim()));
        }

        form.addEventListener("input", update);
        form.addEventListener("change", update);
        form.addEventListener("submit", (event) => {
            if (event.defaultPrevented || form.hasAttribute("data-ajax-submit") || form.closest("#internal-password-modal")) {
                return;
            }
            update();
            if (submit.disabled) {
                event.preventDefault();
                return;
            }
            submitting = true;
            form.setAttribute("aria-busy", "true");
            update();
        });
        form.addEventListener("submit-complete", () => {
            submitting = false;
            form.removeAttribute("aria-busy");
            update();
        });
        window.addEventListener("pageshow", () => {
            submitting = false;
            form.removeAttribute("aria-busy");
            update();
        });
        form.addEventListener("reset", () => setTimeout(update, 0));
        update();
    });
})();
