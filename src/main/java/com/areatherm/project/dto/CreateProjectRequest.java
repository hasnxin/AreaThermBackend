package com.areatherm.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * {@code ownerId} is a placeholder until the security task's JWT auth is
 * wired into controllers to supply the authenticated user automatically --
 * {@code Project.owner} is a required FK and there is no auth context here
 * yet, so the caller must name an existing {@code AppUser} id directly. Once
 * a security filter chain populates the request's principal, this field
 * should be dropped in favor of reading the owner off that context.
 */
public record CreateProjectRequest(
        @NotBlank String name,
        String description,
        @NotNull Long ownerId
) {
}
