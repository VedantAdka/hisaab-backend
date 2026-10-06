package com.vedant.hisaab.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ParticipantShare {
    @NotNull
    private Long userId;

    private BigDecimal amount;     // required when splitType = UNEQUAL
    private BigDecimal percentage; // required when splitType = PERCENTAGE
}