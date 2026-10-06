package com.vedant.hisaab.service;

import com.vedant.hisaab.dto.ActivityResponse;
import com.vedant.hisaab.entity.Activity;
import com.vedant.hisaab.entity.ActivityType;
import com.vedant.hisaab.entity.Group;
import com.vedant.hisaab.entity.User;
import com.vedant.hisaab.repository.ActivityRepository;
import com.vedant.hisaab.repository.GroupMemberRepository;
import com.vedant.hisaab.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ActivityService {

    private final ActivityRepository activityRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final CurrentUserService currentUserService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Group group, User actor, ActivityType type, String message) {
        Activity activity = Activity.builder()
                .group(group)
                .actor(actor)
                .type(type)
                .message(message)
                .build();
        activityRepository.save(activity);
    }

    public List<ActivityResponse> getActivityForGroup(Long groupId) {
        Long currentUserId = currentUserService.getCurrentUser().getId();
        if (!groupMemberRepository.existsByGroupIdAndUserId(groupId, currentUserId)) {
            throw new AccessDeniedException("You are not a member of this group");
        }
        return activityRepository.findByGroupIdOrderByCreatedAtDesc(groupId).stream()
                .map(this::toResponse)
                .toList();
    }

    private ActivityResponse toResponse(Activity a) {
        return ActivityResponse.builder()
                .id(a.getId())
                .type(a.getType())
                .message(a.getMessage())
                .actorUserId(a.getActor().getId())
                .actorName(a.getActor().getName())
                .createdAt(a.getCreatedAt())
                .build();
    }
}