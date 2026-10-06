package com.vedant.hisaab.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class MemberBalance {
    private Long userId;
    private String name;
    private BigDecimal netBalance; // positive = owed to them, negative = they owe
}