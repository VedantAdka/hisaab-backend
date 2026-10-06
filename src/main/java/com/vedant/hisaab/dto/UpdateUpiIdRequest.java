package com.vedant.hisaab.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class UpdateUpiIdRequest {
    @NotBlank
    @Pattern(regexp = "^[\\w.\\-]{2,256}@[a-zA-Z]{2,64}$", message = "Enter a valid UPI ID, e.g. name@okhdfcbank")
    private String upiId;
}