package com.vedant.hisaab.controller;

import com.vedant.hisaab.service.SettlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * A brand new, separate controller on purpose — same reasoning as
 * DirectBalanceController last round — so this doesn't require touching
 * SettlementController.java's existing routes at all.
 */
@RestController
@RequestMapping("/api/groups/{groupId}/members/{otherUserId}/remind")
@RequiredArgsConstructor
public class ReminderController {

    private final SettlementService settlementService;

    @PostMapping
    public Map<String, String> sendReminder(@PathVariable Long groupId, @PathVariable Long otherUserId) {
        settlementService.sendReminder(groupId, otherUserId);
        return Map.of("status", "sent");
    }
}
