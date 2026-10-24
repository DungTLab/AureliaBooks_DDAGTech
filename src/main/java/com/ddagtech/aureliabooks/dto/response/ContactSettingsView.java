package com.ddagtech.aureliabooks.dto.response;

/** Contact form capabilities; no password hash or persistence entity crosses into the controller. */
public record ContactSettingsView(boolean passwordRequired, boolean emailPresent, boolean phonePresent) {}
