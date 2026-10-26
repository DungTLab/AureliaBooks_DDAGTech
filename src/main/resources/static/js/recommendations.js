(() => {
    "use strict";
    document.querySelectorAll("[data-recommendation-form]").forEach((form) => {
        const total = form.querySelector("[data-recommendation-total]");
        function update() {
            const cents = Array.from(form.querySelectorAll("input[data-price]:checked"))
                .reduce((sum, input) => sum + Math.round(Number(input.dataset.price) * 100),
                    Math.round(Number(form.dataset.basePrice) * 100));
            total.textContent = new Intl.NumberFormat("vi-VN", {style: "currency", currency: "VND"}).format(cents / 100);
        }
        form.addEventListener("change", update);
        form.addEventListener("submit", () => {
            form.setAttribute("aria-busy", "true");
            form.querySelectorAll("button[type=submit]").forEach((button) => { button.disabled = true; });
        });
        window.addEventListener("pageshow", () => {
            form.removeAttribute("aria-busy");
            form.querySelectorAll("button[type=submit]").forEach((button) => { button.disabled = false; });
        });
        update();
    });
})();
