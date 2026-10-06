package com.vedant.hisaab.service;

import com.vedant.hisaab.dto.*;
import com.vedant.hisaab.entity.*;
import com.vedant.hisaab.repository.ExpenseRepository;
import com.vedant.hisaab.repository.GroupRepository;
import com.vedant.hisaab.repository.UserRepository;
import com.vedant.hisaab.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExpenseService {

    private final ExpenseRepository expenseRepository;
    private final GroupRepository groupRepository;
    private final UserRepository userRepository;
    private final CurrentUserService currentUserService;
    private final GroupService groupService;
    private final ActivityService activityService;

    @Transactional
    public ExpenseResponse createExpense(Long groupId, CreateExpenseRequest request) {
        User currentUser = currentUserService.getCurrentUser();
        groupService.requireMember(groupId, currentUser.getId());

        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("Group not found"));

        User paidBy = request.getPaidByUserId() != null
                ? userRepository.findById(request.getPaidByUserId())
                .orElseThrow(() -> new IllegalArgumentException("paidByUserId not found"))
                : currentUser;
        groupService.requireMember(groupId, paidBy.getId());

        if (request.getParticipants() == null || request.getParticipants().isEmpty()) {
            throw new IllegalArgumentException("At least one participant is required");
        }

        Map<User, BigDecimal> shares = computeShares(request, groupId);

        Expense expense = Expense.builder()
                .group(group)
                .description(request.getDescription())
                .amount(request.getAmount())
                .currency(request.getCurrency() == null ? "INR" : request.getCurrency())
                .category(request.getCategory())
                .splitType(request.getSplitType())
                .paidBy(paidBy)
                .addedBy(currentUser) // NEW — whoever is logged in when the expense is created
                .expenseDate(request.getExpenseDate() == null ? LocalDate.now() : request.getExpenseDate())
                .notes(request.getNotes())
                .receiptPhotoUrl(request.getReceiptPhotoUrl())
                .build();
        expense = expenseRepository.save(expense);

        List<Split> splits = new ArrayList<>();
        for (Map.Entry<User, BigDecimal> entry : shares.entrySet()) {
            splits.add(Split.builder()
                    .expense(expense)
                    .user(entry.getKey())
                    .amount(entry.getValue())
                    .settled(entry.getKey().getId().equals(paidBy.getId())) // payer owes nobody for their own share
                    .build());
        }
        expense.setSplits(splits);
        expense = expenseRepository.save(expense);
        // expense is reassigned above, so it isn't effectively final — a lambda
        // can't capture it directly. Snapshot it into a final local first.
        final Expense savedExpense = expense;
        recordActivitySafely(() -> activityService.record(group, currentUser, ActivityType.EXPENSE_ADDED,
                currentUser.getName() + " added \"" + savedExpense.getDescription() + "\" (₹" + savedExpense.getAmount() + ", paid by " + paidBy.getName() + ")"));
        return toResponse(expense);
    }

    /**
     * NEW — full replace of an existing expense: same validation as create,
     * plus an authorization check (see requireEditRights) and a wholesale
     * replacement of the splits (simplest correct way to go from N
     * participants/shares to a possibly-different N).
     */
    @Transactional
    public ExpenseResponse updateExpense(Long groupId, Long expenseId, CreateExpenseRequest request) {
        User currentUser = currentUserService.getCurrentUser();
        groupService.requireMember(groupId, currentUser.getId());

        Expense expense = expenseRepository.findById(expenseId)
                .orElseThrow(() -> new IllegalArgumentException("Expense not found"));
        if (!expense.getGroup().getId().equals(groupId)) {
            throw new IllegalArgumentException("Expense not found in this group");
        }
        requireEditRights(expense, currentUser);

        User paidBy = request.getPaidByUserId() != null
                ? userRepository.findById(request.getPaidByUserId())
                .orElseThrow(() -> new IllegalArgumentException("paidByUserId not found"))
                : expense.getPaidBy();
        groupService.requireMember(groupId, paidBy.getId());

        if (request.getParticipants() == null || request.getParticipants().isEmpty()) {
            throw new IllegalArgumentException("At least one participant is required");
        }

        Map<User, BigDecimal> shares = computeShares(request, groupId);

        expense.setDescription(request.getDescription());
        expense.setAmount(request.getAmount());
        expense.setCurrency(request.getCurrency() == null ? "INR" : request.getCurrency());
        expense.setCategory(request.getCategory());
        expense.setSplitType(request.getSplitType());
        expense.setPaidBy(paidBy);
        expense.setExpenseDate(request.getExpenseDate() == null ? LocalDate.now() : request.getExpenseDate());
        expense.setNotes(request.getNotes());
        expense.setReceiptPhotoUrl(request.getReceiptPhotoUrl());

        // orphanRemoval = true on Expense.splits means clearing the list and
        // re-adding deletes the old Split rows and inserts fresh ones.
        expense.getSplits().clear();
        for (Map.Entry<User, BigDecimal> entry : shares.entrySet()) {
            expense.getSplits().add(Split.builder()
                    .expense(expense)
                    .user(entry.getKey())
                    .amount(entry.getValue())
                    .settled(entry.getKey().getId().equals(paidBy.getId()))
                    .build());
        }

        expense = expenseRepository.save(expense);
        // same reassignment issue as in createExpense — snapshot before the lambda.
        final Expense savedExpense = expense;
        recordActivitySafely(() -> activityService.record(savedExpense.getGroup(), currentUser, ActivityType.EXPENSE_UPDATED,
                currentUser.getName() + " updated \"" + savedExpense.getDescription() + "\""));
        return toResponse(expense);
    }

    /** NEW — same dual authorization as update. */
    @Transactional
    public void deleteExpense(Long groupId, Long expenseId) {
        User currentUser = currentUserService.getCurrentUser();
        groupService.requireMember(groupId, currentUser.getId());

        Expense expense = expenseRepository.findById(expenseId)
                .orElseThrow(() -> new IllegalArgumentException("Expense not found"));
        if (!expense.getGroup().getId().equals(groupId)) {
            throw new IllegalArgumentException("Expense not found in this group");
        }
        requireEditRights(expense, currentUser);

        recordActivitySafely(() -> activityService.record(expense.getGroup(), currentUser, ActivityType.EXPENSE_DELETED,
                currentUser.getName() + " deleted \"" + expense.getDescription() + "\" (₹" + expense.getAmount() + ")"));
        expenseRepository.delete(expense);
    }

    /**
     * NEW — activity-feed logging is a nice-to-have, not something that
     * should ever be able to fail a real mutation. This was the actual cause
     * of "Internal Server Error" on edit/delete expense and remove-member:
     * each of those calls activityService.record(...) with one of the brand
     * new ActivityType values (EXPENSE_UPDATED, EXPENSE_DELETED,
     * MEMBER_REMOVED) added in this round. If the activities table's type
     * column has a leftover length/CHECK constraint from before those values
     * existed (common when ddl-auto=update doesn't touch an existing
     * column's constraint), the insert throws and — since nothing caught it
     * — the whole request died as a bare 500, even though the actual
     * expense/group change had already been saved. Wrapping just the
     * activity-log call means the real operation always succeeds even if
     * logging it doesn't; worth still checking your activities table's
     * column definition for the type/activity_type column so the feed
     * itself keeps working, but that's now a separate, non-blocking issue.
     */
    private void recordActivitySafely(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("Failed to record activity (the actual operation still succeeded): {}", e.getMessage());
        }
    }

    /**
     * NEW — edit/delete rights belong to whoever paid for the expense OR
     * whoever originally added it, per Vedant's requirement that both people
     * should have that power when they're different. addedBy is nullable
     * (rows created before this feature shipped won't have it backfilled),
     * so a null addedBy simply falls back to "payer only" for those older
     * rows rather than throwing.
     */
    private void requireEditRights(Expense expense, User currentUser) {
        boolean isPayer = expense.getPaidBy().getId().equals(currentUser.getId());
        boolean isAdder = expense.getAddedBy() != null && expense.getAddedBy().getId().equals(currentUser.getId());
        if (!isPayer && !isAdder) {
            throw new AccessDeniedException("Only the person who paid for this expense or who added it can change it");
        }
    }

    private Map<User, BigDecimal> computeShares(CreateExpenseRequest request, Long groupId) {
        List<ParticipantShare> participants = request.getParticipants();
        BigDecimal total = request.getAmount().setScale(2, RoundingMode.HALF_UP);
        Map<User, BigDecimal> result = new LinkedHashMap<>();

        List<User> users = new ArrayList<>();
        for (ParticipantShare p : participants) {
            User u = userRepository.findById(p.getUserId())
                    .orElseThrow(() -> new IllegalArgumentException("Participant userId not found: " + p.getUserId()));
            groupService.requireMember(groupId, u.getId());
            users.add(u);
        }

        switch (request.getSplitType()) {
            case EQUAL -> {
                int n = users.size();
                BigDecimal base = total.divide(BigDecimal.valueOf(n), 2, RoundingMode.DOWN);
                BigDecimal distributed = base.multiply(BigDecimal.valueOf(n));
                BigDecimal remainder = total.subtract(distributed); // leftover paise
                for (int i = 0; i < n; i++) {
                    BigDecimal share = (i == 0) ? base.add(remainder) : base;
                    result.put(users.get(i), share);
                }
            }
            case UNEQUAL -> {
                BigDecimal sum = BigDecimal.ZERO;
                for (int i = 0; i < participants.size(); i++) {
                    BigDecimal amt = participants.get(i).getAmount();
                    if (amt == null) {
                        throw new IllegalArgumentException("amount is required for every participant when splitType is UNEQUAL");
                    }
                    amt = amt.setScale(2, RoundingMode.HALF_UP);
                    sum = sum.add(amt);
                    result.put(users.get(i), amt);
                }
                if (sum.compareTo(total) != 0) {
                    throw new IllegalArgumentException("Participant amounts (" + sum + ") must add up to the total (" + total + ")");
                }
            }
            case PERCENTAGE -> {
                BigDecimal pctSum = BigDecimal.ZERO;
                for (ParticipantShare p : participants) {
                    if (p.getPercentage() == null) {
                        throw new IllegalArgumentException("percentage is required for every participant when splitType is PERCENTAGE");
                    }
                    pctSum = pctSum.add(p.getPercentage());
                }
                if (pctSum.compareTo(BigDecimal.valueOf(100)) != 0) {
                    throw new IllegalArgumentException("Percentages must add up to 100 (got " + pctSum + ")");
                }
                BigDecimal distributed = BigDecimal.ZERO;
                for (int i = 0; i < participants.size(); i++) {
                    BigDecimal amt;
                    if (i == participants.size() - 1) {
                        amt = total.subtract(distributed); // last participant absorbs rounding
                    } else {
                        amt = total.multiply(participants.get(i).getPercentage())
                                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                        distributed = distributed.add(amt);
                    }
                    result.put(users.get(i), amt);
                }
            }
        }
        return result;
    }

    public List<ExpenseResponse> getExpensesForGroup(Long groupId) {
        groupService.requireMember(groupId, currentUserService.getCurrentUser().getId());
        return expenseRepository.findByGroupIdOrderByExpenseDateDescCreatedAtDesc(groupId)
                .stream().map(this::toResponse).toList();
    }



    private ExpenseResponse toResponse(Expense expense) {
        List<SplitResponse> splits = expense.getSplits().stream()
                .map(s -> SplitResponse.builder()
                        .userId(s.getUser().getId())
                        .userName(s.getUser().getName())
                        .amount(s.getAmount())
                        .settled(s.isSettled())
                        .build())
                .toList();
        return ExpenseResponse.builder()
                .id(expense.getId())
                .description(expense.getDescription())
                .amount(expense.getAmount())
                .currency(expense.getCurrency())
                .category(expense.getCategory())
                .splitType(expense.getSplitType())
                .paidByUserId(expense.getPaidBy().getId())
                .paidByName(expense.getPaidBy().getName())
                .addedByUserId(expense.getAddedBy() != null ? expense.getAddedBy().getId() : null)
                .addedByName(expense.getAddedBy() != null ? expense.getAddedBy().getName() : null)
                .expenseDate(expense.getExpenseDate())
                .notes(expense.getNotes())
                .receiptPhotoUrl(expense.getReceiptPhotoUrl())
                .splits(splits)
                .build();
    }
}
