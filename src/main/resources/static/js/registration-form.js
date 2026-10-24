(() => {
    "use strict";

    const form = document.getElementById("registrationForm");
    if (!form) return;
    const submit = form.querySelector("[data-registration-submit]");
    const hint = document.getElementById("registrationHint");
    const fields = ["fullName", "phone", "dob", "password", "confirmPassword", "agreeTerms"].map((id) =>
        document.getElementById(id),
    );
    const touched = new Set();
    const feedback = new Map();
    let submitting = false;

    fields
        .filter((field) => field.id !== "agreeTerms")
        .forEach((field) => {
            const note = document.createElement("p");
            note.id = `${field.id}-feedback`;
            note.className = "min-h-[1.25rem] invisible text-sm text-red-700 mt-1";
            const wrapper = field.closest("[data-password-field]") || field;
            wrapper.insertAdjacentElement("afterend", note);
            field.setAttribute("aria-describedby", `${field.getAttribute("aria-describedby") || ""} ${note.id}`.trim());
            feedback.set(field, note);
        });

    function update() {
        const name = document.getElementById("fullName");
        const phone = document.getElementById("phone");
        const password = document.getElementById("password");
        const confirmation = document.getElementById("confirmPassword");
        name.setCustomValidity(
            name.value && (!/^[\p{L}\p{M} ]+$/u.test(name.value) || name.value.trim().length < 2)
                ? "Họ tên từ 2 đến 100 ký tự, chỉ gồm chữ cái và khoảng trắng."
                : "",
        );
        phone.setCustomValidity(
            phone.value && !/^0[0-9]{9}$/.test(phone.value) ? "Số điện thoại gồm 10 chữ số, bắt đầu bằng 0." : "",
        );
        const strong =
            Array.from(password.value).length >= 8 &&
            /\p{Lu}/u.test(password.value) &&
            /[0-9]/.test(password.value) &&
            /[^\p{L}\p{N}\s]/u.test(password.value);
        password.setCustomValidity(
            password.value && !strong
                ? "Mật khẩu từ 8 ký tự, có chữ hoa, số và ký tự đặc biệt."
                : new TextEncoder().encode(password.value).length > 72
                  ? "Mật khẩu tối đa 72 byte UTF-8."
                  : "",
        );
        confirmation.setCustomValidity(
            confirmation.value && confirmation.value !== password.value ? "Mật khẩu xác nhận chưa khớp." : "",
        );
        const invalid = fields.filter((field) => !field.validity.valid);
        submit.disabled = submitting || invalid.length > 0;
        const message = submitting
            ? "Đang gửi mã xác thực tới số điện thoại…"
            : invalid.length === 0
              ? "Thông tin hợp lệ. Bạn có thể tiếp tục đăng ký."
              : invalid.length === 1 && invalid[0].id === "agreeTerms"
                ? "Vui lòng đồng ý với điều khoản sử dụng để tiếp tục."
                : "Hoàn tất các trường bắt buộc, kiểm tra mật khẩu và đồng ý điều khoản để tiếp tục.";
        if (hint.textContent.trim() !== message) hint.textContent = message;
        fields.forEach((field) => {
            if (!touched.has(field) || !feedback.has(field)) return;
            const note = feedback.get(field);
            const invalid = !field.validity.valid;
            note.textContent = field.validity.valueMissing
                ? "Vui lòng điền trường này."
                : field.validity.rangeOverflow
                  ? "Ngày sinh phải là ngày trong quá khứ."
                  : field.validationMessage;
            note.classList.toggle("invisible", !invalid);
            field.setAttribute("aria-invalid", String(invalid));
        });
    }

    form.addEventListener("input", update);
    form.addEventListener("change", update);
    fields.forEach((field) =>
        field.addEventListener("blur", () => {
            touched.add(field);
            update();
        }),
    );
    form.addEventListener("submit", (event) => {
        update();
        if (submit.disabled) {
            event.preventDefault();
            return;
        }
        submitting = true;
        submit.textContent = "Đang xử lý…";
        form.setAttribute("aria-busy", "true");
        update();
    });
    window.addEventListener("pageshow", () => {
        submitting = false;
        submit.textContent = "Đăng ký";
        form.removeAttribute("aria-busy");
        update();
    });
    form.addEventListener("reset", () => {
        touched.clear();
        setTimeout(update, 0);
    });
    update();
})();
