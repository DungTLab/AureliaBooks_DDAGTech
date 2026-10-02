package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.request.*;
import com.ddagtech.aureliabooks.dto.response.*;
import org.springframework.data.domain.*;

/** UC29. Owner: Trần Huỳnh Giác. Sprint 1 scaffold; business implementation pending. */
public interface AuditLogService {
    void record(Long authenticatedUserId, String action, String targetTable,
            Long targetId, String detailsJson, String ipAddress);
    // TODO: add typed read filters; redact credentials and tokens before append.
}
