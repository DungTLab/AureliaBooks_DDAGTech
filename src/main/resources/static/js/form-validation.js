// Keep submit availability in sync with browser constraints and server-provided blocking states.
(() => {
    "use strict";

    document.querySelectorAll("form[data-form-validation], form[data-checkout-submit]").forEach((form) => {
        const originalLabels = new Map();
        const touched = new Set();
        const notes = new Map();
        let submitting = false;

        const controls = () => Array.from(form.elements).filter((field) => field.willValidate);
        const buttons = () => Array.from(form.elements).filter((field) => field.type === "submit");
        const requiredValues = () => (form.dataset.requiredValues || "").split(/\s+/).filter(Boolean);
        const valuePresent = (name) =>
            Array.from(form.elements).some(
                (field) =>
                    field.name === name &&
                    !field.matches(":disabled") &&
                    (field.type === "radio" || field.type === "checkbox" ? field.checked : !!field.value.trim()),
            );

        function errorMessage(field) {
            const validity = field.validity;
            if (validity.customError) return field.validationMessage;
            if (validity.valueMissing) return "Vui lòng điền trường này.";
            if (validity.rangeUnderflow) return `Giá trị không nhỏ hơn ${field.min}.`;
            if (validity.rangeOverflow) return `Giá trị không vượt quá ${field.max}.`;
            if (validity.stepMismatch) return "Giá trị chưa đúng số chữ số hoặc bước tăng cho phép.";
            if (validity.badInput) return "Vui lòng nhập giá trị hợp lệ.";
            if (validity.patternMismatch || validity.typeMismatch) return "Thông tin chưa đúng định dạng yêu cầu.";
            return "Vui lòng kiểm tra lại trường này.";
        }

        function update() {
            const fields = controls();
            fields.forEach((field) => {
                if (field.dataset.basicValidityError === "true") {
                    field.setCustomValidity("");
                    delete field.dataset.basicValidityError;
                }
                const text = field.tagName === "TEXTAREA" || ["text", "search", "tel", "email"].includes(field.type);
                if (text && field.required && !field.value.trim()) {
                    field.setCustomValidity("Vui lòng điền trường này.");
                    field.dataset.basicValidityError = "true";
                } else if (text && field.maxLength >= 0 && field.value.length > field.maxLength) {
                    field.setCustomValidity(`Trường này tối đa ${field.maxLength} ký tự.`);
                    field.dataset.basicValidityError = "true";
                }
                if (field.id && !["radio", "checkbox", "submit"].includes(field.type)) {
                    if (!notes.has(field)) {
                        const note = document.createElement("p");
                        note.id = `${field.id}-validation`;
                        note.className = "form-field-error";
                        note.hidden = true;
                        field.insertAdjacentElement("afterend", note);
                        field.setAttribute(
                            "aria-describedby",
                            `${field.getAttribute("aria-describedby") || ""} ${note.id}`.trim(),
                        );
                        notes.set(field, note);
                    }
                    const invalid = touched.has(field) && !field.validity.valid;
                    notes.get(field).textContent = errorMessage(field);
                    notes.get(field).hidden = !invalid;
                    field.setAttribute("aria-invalid", String(invalid));
                }
            });
            const invalid = fields.some((field) => !field.validity.valid);
            const blocked =
                form.dataset.serverBlocked === "true" || requiredValues().some((name) => !valuePresent(name));
            buttons().forEach((button) => {
                button.disabled = submitting || invalid || blocked;
            });
            const feedback = form.querySelector("[data-form-feedback]");
            if (feedback)
                feedback.textContent = submitting
                    ? "Đang xử lý…"
                    : blocked
                      ? form.dataset.blockedMessage || "Vui lòng chọn đầy đủ thông tin để tiếp tục."
                      : invalid
                        ? "Hoàn tất các trường bắt buộc và kiểm tra thông tin chưa hợp lệ."
                        : "Thông tin hợp lệ. Bạn có thể tiếp tục.";
        }

        form.addEventListener("input", (event) => {
            touched.add(event.target);
            update();
        });
        form.addEventListener("change", (event) => {
            touched.add(event.target);
            update();
        });
        form.addEventListener("focusout", (event) => {
            touched.add(event.target);
            update();
        });
        // Form-associated controls outside the form do not bubble events through it.
        document.addEventListener("change", (event) => {
            if (event.target.form === form && !form.contains(event.target)) update();
        });
        form.addEventListener("form-validity-change", update);
        form.addEventListener("reset", () => {
            touched.clear();
            setTimeout(update, 0);
        });
        form.addEventListener("submit", (event) => {
            if (event.defaultPrevented) return;
            update();
            if (
                submitting ||
                form.dataset.serverBlocked === "true" ||
                requiredValues().some((name) => !valuePresent(name)) ||
                !form.checkValidity()
            ) {
                event.preventDefault();
                return;
            }
            submitting = true;
            form.setAttribute("aria-busy", "true");
            buttons().forEach((button) => {
                originalLabels.set(button, button.innerHTML);
                button.textContent = "Đang xử lý…";
            });
            update();
        });
        window.addEventListener("pageshow", () => {
            submitting = false;
            form.removeAttribute("aria-busy");
            originalLabels.forEach((label, button) => {
                button.innerHTML = label;
            });
            originalLabels.clear();
            update();
        });
        update();
    });
})();
