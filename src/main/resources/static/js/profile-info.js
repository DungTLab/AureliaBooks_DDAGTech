(() => {
    "use strict";

    const form = document.getElementById("profileInfoForm");
    if (!form) return;
    const button = form.querySelector("[data-profile-save]");
    const fields = Array.from(form.querySelectorAll("input[name], select[name]")).filter(
        (field) => !field.readOnly && field.type !== "hidden",
    );
    const snapshot = () => JSON.stringify(fields.map((field) => [field.name, field.value.trim()]));
    const original = snapshot();
    let submitting = false;

    function update() {
        const name = form.querySelector("[name=fullName]");
        name.setCustomValidity(
            name.value.trim().length < 2 || !/^[\p{L}\p{M} ]+$/u.test(name.value)
                ? "Họ tên từ 2 đến 100 ký tự, chỉ gồm chữ cái và khoảng trắng."
                : "",
        );
        button.disabled = submitting || snapshot() === original || fields.some((field) => !field.validity.valid);
    }

    form.addEventListener("input", update);
    form.addEventListener("change", update);
    form.addEventListener("reset", () => setTimeout(update, 0));
    form.addEventListener("submit", (event) => {
        update();
        if (button.disabled) {
            event.preventDefault();
            return;
        }
        submitting = true;
        button.textContent = "Đang lưu…";
        form.setAttribute("aria-busy", "true");
        update();
    });
    window.addEventListener("pageshow", () => {
        submitting = false;
        button.textContent = "Lưu thay đổi";
        form.removeAttribute("aria-busy");
        update();
    });
    update();
})();
