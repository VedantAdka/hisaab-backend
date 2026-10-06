package com.vedant.hisaab.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
public class GroupResponse {
    private Long id;
    private String name;
    private Long createdByUserId;   // NEW — lets the web app show "Delete group" to the creator only
    private List<MemberResponse> members;
}
