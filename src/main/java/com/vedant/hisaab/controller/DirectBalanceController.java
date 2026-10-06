package com.vedant.hisaab.controller;

import com.vedant.hisaab.dto.GroupBalanceResponse;
import com.vedant.hisaab.service.SettlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The "exact" companion to BalanceController's simplified /balances endpoint
 * — same response shape (GroupBalanceResponse), but every settlement entry
 * is a real, direct pairwise debt rather than the fewest-transactions plan.
 * A brand new, separate endpoint on purpose: it leaves the existing
 * /balances endpoint and BalanceService completely untouched.
 */
@RestController
@RequestMapping("/api/groups/{groupId}/balances/direct")
@RequiredArgsConstructor
public class DirectBalanceController {

    private final SettlementService settlementService;

    @GetMapping
    public GroupBalanceResponse getDirectBalances(@PathVariable Long groupId) {
        return settlementService.getDirectGroupBalances(groupId);
    }
}
