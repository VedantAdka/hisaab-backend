package com.vedant.hisaab.service;

import com.vedant.hisaab.dto.GroupBalanceResponse;
import com.vedant.hisaab.dto.MemberBalance;
import com.vedant.hisaab.dto.Settlement;
import com.vedant.hisaab.entity.Expense;
import com.vedant.hisaab.entity.Group;
import com.vedant.hisaab.entity.SettlementRecord;
import com.vedant.hisaab.entity.Split;
import com.vedant.hisaab.entity.User;
import com.vedant.hisaab.repository.ExpenseRepository;
import com.vedant.hisaab.repository.GroupRepository;
import com.vedant.hisaab.repository.SettlementRecordRepository;
import com.vedant.hisaab.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Service
@RequiredArgsConstructor
public class BalanceService {

    private final ExpenseRepository expenseRepository;
    private final GroupRepository groupRepository;
    private final SettlementRecordRepository settlementRecordRepository;
    private final GroupService groupService;
    private final CurrentUserService currentUserService;

    public GroupBalanceResponse getGroupBalances(Long groupId) {
        groupService.requireMember(groupId, currentUserService.getCurrentUser().getId());

        groupRepository.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("Group not found"));

        List<Expense> expenses = expenseRepository.findByGroupIdOrderByExpenseDateDescCreatedAtDesc(groupId);

        Map<User, BigDecimal> net = new LinkedHashMap<>();

        for (Expense expense : expenses) {
            for (Split split : expense.getSplits()) {
                User debtor = split.getUser();
                User creditor = expense.getPaidBy();

                if (debtor.getId().equals(creditor.getId()) || split.isSettled()) {
                    continue; // payer's own share, or already settled - not outstanding debt
                }

                net.merge(debtor, split.getAmount().negate(), BigDecimal::add);
                net.merge(creditor, split.getAmount(), BigDecimal::add);
            }
        }

        // Recorded settlements (from SettlementService.markSettled, for pairs with
        // no direct shared expense — see the simplified/exact Settle Up flow) are
        // real payments too. Netting them in here is what makes a payment made via
        // that "indirect" path actually reduce what's shown on this, the default
        // simplified Balances view — not just the separate exact-balances endpoint.
        // A payment moves the opposite direction of a debt, hence the signs being
        // flipped relative to the split loop above: paying reduces what `from`
        // owes (their net moves toward positive) and reduces what `to` is owed
        // (their net moves toward negative).
        for (SettlementRecord record : settlementRecordRepository.findByGroupId(groupId)) {
            net.merge(record.getFromUser(), record.getAmount(), BigDecimal::add);
            net.merge(record.getToUser(), record.getAmount().negate(), BigDecimal::add);
        }

        List<MemberBalance> balances = net.entrySet().stream()
                .map(e -> MemberBalance.builder()
                        .userId(e.getKey().getId())
                        .name(e.getKey().getName())
                        .netBalance(e.getValue().setScale(2, RoundingMode.HALF_UP))
                        .build())
                .toList();

        List<Settlement> settlements = simplify(net);

        return GroupBalanceResponse.builder()
                .groupId(groupId)
                .balances(balances)
                .settlements(settlements)
                .build();
    }

    private List<Settlement> simplify(Map<User, BigDecimal> net) {
        PriorityQueue<Map.Entry<User, BigDecimal>> creditors =
                new PriorityQueue<>((a, b) -> b.getValue().compareTo(a.getValue()));
        PriorityQueue<Map.Entry<User, BigDecimal>> debtors =
                new PriorityQueue<>((a, b) -> a.getValue().compareTo(b.getValue()));

        for (Map.Entry<User, BigDecimal> entry : net.entrySet()) {
            BigDecimal amount = entry.getValue().setScale(2, RoundingMode.HALF_UP);
            if (amount.compareTo(BigDecimal.ZERO) > 0) {
                creditors.add(new AbstractMap.SimpleEntry<>(entry.getKey(), amount));
            } else if (amount.compareTo(BigDecimal.ZERO) < 0) {
                debtors.add(new AbstractMap.SimpleEntry<>(entry.getKey(), amount));
            }
        }

        List<Settlement> result = new ArrayList<>();

        while (!creditors.isEmpty() && !debtors.isEmpty()) {
            Map.Entry<User, BigDecimal> creditor = creditors.poll();
            Map.Entry<User, BigDecimal> debtor = debtors.poll();

            BigDecimal creditAmt = creditor.getValue();
            BigDecimal debtAmt = debtor.getValue().abs();
            BigDecimal settleAmt = creditAmt.min(debtAmt);

            result.add(Settlement.builder()
                    .fromUserId(debtor.getKey().getId())
                    .fromUserName(debtor.getKey().getName())
                    .toUserId(creditor.getKey().getId())
                    .toUserName(creditor.getKey().getName())
                    .amount(settleAmt)
                    .build());

            BigDecimal creditorRemaining = creditAmt.subtract(settleAmt);
            BigDecimal debtorRemaining = debtAmt.subtract(settleAmt);

            if (creditorRemaining.compareTo(BigDecimal.ZERO) > 0) {
                creditors.add(new AbstractMap.SimpleEntry<>(creditor.getKey(), creditorRemaining));
            }
            if (debtorRemaining.compareTo(BigDecimal.ZERO) > 0) {
                debtors.add(new AbstractMap.SimpleEntry<>(debtor.getKey(), debtorRemaining.negate()));
            }
        }

        return result;
    }
}
