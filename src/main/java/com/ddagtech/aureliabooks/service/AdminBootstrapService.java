package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.request.BootstrapAdminRequest;

public interface AdminBootstrapService {
    /** Create the first administrator only; never reset or reactivate existing accounts. */
    boolean initialize(BootstrapAdminRequest request);
}
