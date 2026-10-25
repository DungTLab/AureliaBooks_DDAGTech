package com.ddagtech.aureliabooks.mapper;

import com.ddagtech.aureliabooks.dto.request.VoucherForm;
import com.ddagtech.aureliabooks.dto.response.VoucherSummary;
import com.ddagtech.aureliabooks.entity.*;
import org.springframework.stereotype.Component;

@Component
public class VoucherMapper {
    public Voucher toEntity(VoucherForm form, User creator) {
        Voucher v = new Voucher();
        v.setCode(form.code());
        v.setDescription(form.description());
        v.setScope(form.scope());
        v.setType(form.type());
        v.setDiscountPercentage(form.discountPercentage());
        v.setDiscountAmount(form.discountAmount());
        v.setMaxDiscountAmount(form.maxDiscountAmount());
        v.setMinOrderValue(form.minOrderValue());
        v.setStartDate(form.startDate());
        v.setEndDate(form.endDate());
        v.setUsageLimit(form.usageLimit());
        v.setCreatedBy(creator);
        v.setActive(true);
        return v;
    }

    public VoucherSummary toSummary(Voucher v, long redemptions) {
        return new VoucherSummary(v.getId(), v.getCode(), v.getDescription(), v.getScope(), v.getType(),
                v.getDiscountPercentage(), v.getDiscountAmount(), v.getMaxDiscountAmount(),
                v.getMinOrderValue(), v.getStartDate(), v.getEndDate(), v.getUsageLimit(),
                Boolean.TRUE.equals(v.getActive()), redemptions);
    }
}
