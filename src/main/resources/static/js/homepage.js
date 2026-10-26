(() => {
    "use strict";
    document.querySelectorAll("img[data-product-image]").forEach((image) => {
        function fallback() {
            if (image.dataset.fallbackApplied === "true") return;
            image.dataset.fallbackApplied = "true";
            image.src = image.dataset.fallbackSrc;
        }
        image.addEventListener("error", fallback, {once: true});
        if (image.complete && image.naturalWidth === 0) fallback();
    });
})();
