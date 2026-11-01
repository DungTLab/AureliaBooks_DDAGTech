(() => {
    "use strict";
    document.querySelectorAll("[data-image-uploader]").forEach((panel) => {
        const form = panel.closest("form");
        const url = form.querySelector("#mainImageUrl");
        const file = panel.querySelector("[data-image-file]");
        const button = panel.querySelector("[data-image-upload]");
        const status = panel.querySelector("[data-image-status]");
        const preview = panel.querySelector("[data-image-preview]");
        const csrf = panel.querySelector("[data-image-csrf]");
        let busy = false;
        let localPreview;
        const internalUrl = /^\/products\/images\/[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
        function discardPreview() {
            if (localPreview) URL.revokeObjectURL(localPreview);
            localPreview = undefined;
            preview.hidden = true;
            preview.removeAttribute("src");
        }
        file.addEventListener("change", () => {
            discardPreview();
            status.textContent = "";
            const selected = file.files[0];
            if (selected && selected.size > 0 && selected.size <= 5 * 1024 * 1024
                    && ["image/png", "image/jpeg"].includes(selected.type)) {
                localPreview = URL.createObjectURL(selected);
                preview.src = localPreview;
                preview.hidden = false;
            }
        });
        url.addEventListener("input", () => {
            if (!busy) { discardPreview(); status.textContent = ""; }
        });
        form.addEventListener("submit", (event) => {
            if (busy) {
                event.preventDefault();
                event.stopImmediatePropagation();
                status.textContent = "Vui lòng đợi tải ảnh hoàn tất trước khi lưu.";
            }
        }, true);
        window.addEventListener("pagehide", discardPreview);
        button.addEventListener("click", async () => {
            if (busy) return;
            const selected = file.files[0];
            if (!selected || !selected.size || selected.size > 5 * 1024 * 1024
                    || !["image/png", "image/jpeg"].includes(selected.type)) {
                status.textContent = "Chọn ảnh PNG/JPEG hợp lệ, tối đa 5 MiB.";
                return;
            }
            if (!csrf.value || !csrf.dataset.header) {
                status.textContent = "Phiên làm việc không hợp lệ. Vui lòng tải lại trang.";
                return;
            }
            const wasBlocked = form.dataset.serverBlocked;
            busy = true;
            button.disabled = true;
            file.disabled = true;
            url.readOnly = true;
            form.dataset.serverBlocked = "true";
            form.dispatchEvent(new Event("input", {bubbles: true}));
            status.textContent = "Đang tải ảnh…";
            try {
                const body = new FormData();
                body.append("file", selected);
                const response = await fetch(panel.dataset.uploadUrl, {
                    method: "POST", body, credentials: "same-origin", redirect: "error",
                    headers: {Accept: "application/json", "X-Requested-With": "XMLHttpRequest",
                        [csrf.dataset.header]: csrf.value}
                });
                if (!response.ok) {
                    const messages = {400: "Ảnh không hợp lệ (PNG/JPEG, tối đa 5 MiB, 16 triệu pixel).",
                        401: "Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.",
                        403: "Bạn không có quyền hoặc phiên bảo vệ đã hết hạn. Hãy tải lại trang.",
                        413: "File quá lớn. Vui lòng chọn ảnh tối đa 5 MiB.",
                        503: "Dịch vụ ảnh tạm thời gián đoạn. Vui lòng thử lại sau."};
                    throw new Error(messages[response.status] || "Không thể tải ảnh lên. Vui lòng thử lại.");
                }
                const result = await response.json();
                if (!internalUrl.test(result.imageUrl || "")) throw new Error("Phản hồi ảnh không hợp lệ.");
                url.value = result.imageUrl;
                status.textContent = "Đã tải ảnh. Hãy lưu sản phẩm để hiển thị ảnh này.";
            } catch (error) {
                status.textContent = error instanceof TypeError ? "Không thể kết nối dịch vụ ảnh. Hãy thử lại."
                    : error.message;
            } finally {
                busy = false;
                button.disabled = false;
                file.disabled = false;
                url.readOnly = false;
                if (wasBlocked === undefined) delete form.dataset.serverBlocked;
                else form.dataset.serverBlocked = wasBlocked;
                // Notify existing form validation without discarding the local preview.
                form.dispatchEvent(new Event("input", {bubbles: true}));
            }
        });
    });
})();
