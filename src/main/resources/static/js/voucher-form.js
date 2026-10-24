"use strict";

document.querySelectorAll("[data-voucher-form]").forEach((form) => {
    const type = form.querySelector("[data-voucher-type]");
    const percentage = form.querySelector("[data-percentage-field]");
    const fixed = form.querySelector("[data-fixed-field]");
    const cap = form.querySelector('[name="maxDiscountAmount"]');
    const end = form.querySelector('[name="endDate"]');
    const start = form.querySelector('[name="startDate"]');
    const usage = form.querySelector('[name="usageLimit"]');
    const code = form.querySelector('[name="code"]');
    const moneyFields = ["discountPercentage", "discountAmount", "maxDiscountAmount", "minOrderValue"].map((name) =>
        form.querySelector(`[name="${name}"]`),
    );

    function update() {
        const isPercentage = type.value === "PERCENTAGE";
        percentage.hidden = !isPercentage;
        fixed.hidden = isPercentage;
        percentage.querySelector("input").disabled = !isPercentage;
        percentage.querySelector("input").required = isPercentage;
        fixed.querySelector("input").disabled = isPercentage;
        fixed.querySelector("input").required = !isPercentage;
        cap.required = isPercentage;
        cap.disabled = !isPercentage;
        form.querySelector("[data-cap-field]").hidden = !isPercentage;
        moneyFields.forEach((field) => {
            // BigDecimal validation counts trailing decimal places, including exponent notation.
            const [mantissa, exponent = "0"] = field.value.toLowerCase().split("e");
            const places = Math.max(0, (mantissa.split(".")[1] || "").length - Number(exponent));
            field.setCustomValidity(field.value && places > 2 ? "Giá trị có tối đa 2 chữ số thập phân." : "");
        });
        usage.setCustomValidity(
            usage.value && !/^[0-9]+$/.test(usage.value) ? "Số lượt sử dụng phải là số nguyên." : "",
        );
        end.setCustomValidity(
            start.value && end.value && end.valueAsNumber <= start.valueAsNumber
                ? "Ngày kết thúc phải sau ngày bắt đầu."
                : "",
        );
        form.dispatchEvent(new Event("form-validity-change"));
    }

    code.addEventListener("blur", () => {
        code.value = code.value.trim().toUpperCase();
        update();
    });
    form.addEventListener("input", update);
    form.addEventListener("change", update);
    form.addEventListener("reset", () => setTimeout(update, 0));
    window.addEventListener("pageshow", update);
    update();
});
