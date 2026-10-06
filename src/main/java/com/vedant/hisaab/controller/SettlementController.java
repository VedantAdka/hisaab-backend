package com.vedant.hisaab.controller;

import com.vedant.hisaab.dto.SettleUpLinkResponse;
import com.vedant.hisaab.service.SettlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/groups/{groupId}/members/{otherUserId}/settle-up")
@RequiredArgsConstructor
public class SettlementController {

    private final SettlementService settlementService;

    @GetMapping
    public SettleUpLinkResponse getSettleUpLink(@PathVariable Long groupId, @PathVariable Long otherUserId) {
        return settlementService.getSettleUpLink(groupId, otherUserId);
    }

    @PostMapping
    public Map<String, String> markSettled(@PathVariable Long groupId, @PathVariable Long otherUserId) {
        settlementService.markSettled(groupId, otherUserId);
        return Map.of("status", "settled");
    }
}