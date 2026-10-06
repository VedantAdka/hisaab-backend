package com.vedant.hisaab.dto;

import com.vedant.hisaab.entity.ActivityType;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class ActivityResponse {
    private Long id;
    private ActivityType type;
    private String message;
    private Long actorUserId;
    private String actorName;
    private Instant createdAt;
}