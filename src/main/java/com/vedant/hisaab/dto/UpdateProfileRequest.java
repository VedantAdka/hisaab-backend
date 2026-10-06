package com.vedant.hisaab.dto; // match your actual package

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class UpdateProfileRequest {

    @NotBlank(message = "Name is required")
    private String name;

    @NotBlank(message = "Phone number is required")
    @Pattern(regexp = "^(\\+91[- ]?)?[6-9]\\d{9}$", message = "Enter a valid Indian mobile number")
    private String phoneNumber;
}