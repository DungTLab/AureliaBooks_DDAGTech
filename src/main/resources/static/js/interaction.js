(() => {
    "use strict";

    document.querySelectorAll("dialog[data-managed-dialog]").forEach((dialog) => {
        if (dialog.dataset.open === "true" && !dialog.open) dialog.showModal();
        dialog.addEventListener("keydown", (event) => {
            if (event.key !== "Tab") return;
            const controls = Array.from(dialog.querySelectorAll('a[href],button,input,select,textarea,[tabindex]'))
                .filter((element) => !element.disabled && element.tabIndex >= 0 && element.getClientRects().length);
            const first = controls[0], last = controls[controls.length - 1];
            if (!first) { event.preventDefault(); return; }
            if (event.shiftKey && (document.activeElement === first || document.activeElement === dialog)) {
                event.preventDefault(); last.focus();
            } else if (!event.shiftKey && document.activeElement === last) {
                event.preventDefault(); first.focus();
            }
        });
        dialog.addEventListener("click", (event) => {
            const rect = dialog.getBoundingClientRect();
            if (event.target === dialog && (event.clientX < rect.left || event.clientX > rect.right
                || event.clientY < rect.top || event.clientY > rect.bottom)) dialog.close();
        });
    });

    document.querySelectorAll("[data-filter-toggle]").forEach((button) => {
        const panel = document.getElementById(button.getAttribute("aria-controls"));
        if (!panel) return;
        button.closest("aside").classList.add("is-enhanced");
        const desktop = matchMedia("(min-width: 1024px)");
        function sync() {
            panel.hidden = !desktop.matches;
            button.setAttribute("aria-expanded", String(!panel.hidden));
        }
        sync();
        desktop.addEventListener("change", sync);
        button.addEventListener("click", () => {
            panel.hidden = !panel.hidden;
            button.setAttribute("aria-expanded", String(!panel.hidden));
        });
    });
})();
