package com.vedant.hisaab.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AddMemberRequest {
    @NotBlank(message = "Provide the member's email or phone number")
    private String identifier;
}