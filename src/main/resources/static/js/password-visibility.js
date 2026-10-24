(() => {
    "use strict";

    document.addEventListener("click", (event) => {
        const button = event.target.closest("button[data-password-toggle]");
        if (!button) return;

        const field = button.closest("[data-password-field]");
        const input = field?.querySelector("input");
        if (!input || !["password", "text"].includes(input.type)) return;

        const visible = input.type === "password";
        input.type = visible ? "text" : "password";
        const label = `${visible ? "Ẩn" : "Hiện"} ${button.dataset.passwordLabel || "mật khẩu"}`;
        button.setAttribute("aria-pressed", String(visible));
        button.setAttribute("aria-label", label);
        button.title = label;

        const icon = button.querySelector("i");
        icon?.classList.toggle("bi-eye", !visible);
        icon?.classList.toggle("bi-eye-slash", visible);
    });
})();
