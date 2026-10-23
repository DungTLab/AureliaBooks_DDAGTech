package com.ddagtech.aureliabooks.dto.response;

import java.time.LocalDateTime;

/** Display only operational fields, never the entire audit JSON payload. */
public record OrderActivityView(LocalDateTime recordedAt, String actor, String action, String description) {}
