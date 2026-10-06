package com.vedant.hisaab.controller;

import com.vedant.hisaab.dto.ActivityResponse;
import com.vedant.hisaab.service.ActivityService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/groups/{groupId}/activity")
@RequiredArgsConstructor
public class ActivityController {

    private final ActivityService activityService;

    @GetMapping
    public List<ActivityResponse> getActivity(@PathVariable Long groupId) {
        return activityService.getActivityForGroup(groupId);
    }
}