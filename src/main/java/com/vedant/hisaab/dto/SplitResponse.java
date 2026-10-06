package com.vedant.hisaab.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class SplitResponse {
    private Long userId;
    private String userName;
    private BigDecimal amount;
    private boolean settled;
}