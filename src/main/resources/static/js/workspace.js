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
})();
