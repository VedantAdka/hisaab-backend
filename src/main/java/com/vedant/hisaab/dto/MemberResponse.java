package com.vedant.hisaab.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
public class MemberResponse {
    private Long userId;
    private String name;
    private String email;
}