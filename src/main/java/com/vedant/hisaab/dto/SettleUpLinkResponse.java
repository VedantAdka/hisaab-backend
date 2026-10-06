package com.vedant.hisaab.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class SettleUpLinkResponse {
    private Long fromUserId;
    private Long toUserId;
    private String toUserName;
    private BigDecimal amount;
    private String upiId;
    private String upiDeepLink;
}