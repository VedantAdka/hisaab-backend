package com.vedant.hisaab.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** NEW — body for PUT /api/groups/{groupId}. Renames the group; creator-only. */
@Data
public class UpdateGroupRequest {
    @NotBlank
    private String name;
}
