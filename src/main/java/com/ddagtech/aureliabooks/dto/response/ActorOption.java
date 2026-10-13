package com.ddagtech.aureliabooks.dto.response;

/**
 * Immutable DTO representing an actor option for audit log filtering (UC29).
 *
 * @param id user identifier of the actor
 * @param name full display name of the actor
 * @param email email address of the actor
 */
public record ActorOption(
        Long id,
        String name,
        String email
) {}
