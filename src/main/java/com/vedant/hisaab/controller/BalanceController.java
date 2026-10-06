package com.vedant.hisaab.controller;

import com.vedant.hisaab.dto.GroupBalanceResponse;
import com.vedant.hisaab.service.BalanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/groups/{groupId}/balances")
@RequiredArgsConstructor
public class BalanceController {

    private final BalanceService balanceService;

    @GetMapping
    public GroupBalanceResponse getBalances(@PathVariable Long groupId) {
        return balanceService.getGroupBalances(groupId);
    }
}