(() => {
    "use strict";
    const menu = document.querySelector("[data-workspace-menu]");
    if (!menu) return;
    const desktop = matchMedia("(min-width: 1024px)");
    const sync = () => { menu.open = desktop.matches; };
    sync();
    desktop.addEventListener("change", sync);
})();
