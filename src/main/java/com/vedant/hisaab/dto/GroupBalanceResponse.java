package com.vedant.hisaab.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class GroupBalanceResponse {
    private Long groupId;
    private List<MemberBalance> balances;
    private List<Settlement> settlements;
}