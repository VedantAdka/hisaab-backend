package com.vedant.hisaab.service;

import com.vedant.hisaab.dto.GroupBalanceResponse;
import com.vedant.hisaab.dto.Settlement;
import com.vedant.hisaab.entity.*;
import com.vedant.hisaab.repository.ExpenseRepository;
import com.vedant.hisaab.repository.GroupRepository;
import com.vedant.hisaab.repository.SettlementRecordRepository;
import com.vedant.hisaab.security.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the net-balance calculation and the simplify() greedy
 * matching algorithm behind the Balances tab. This is the single most
 * important piece of logic in the whole app - it's what decides who owes
 * who, and a silent bug here means someone pays the wrong amount - so it's
 * tested with plain, hand-checkable arithmetic rather than mocked
 * assumptions about what the "right" answer should be.
 *
 * Every number below is worked out by hand in the comment above each test,
 * so a failure tells you immediately which step of the math broke.
 */
@ExtendWith(MockitoExtension.class)
class BalanceServiceTest {

    @Mock
    private ExpenseRepository expenseRepository;
    @Mock
    private GroupRepository groupRepository;
    @Mock
    private SettlementRecordRepository settlementRecordRepository;
    @Mock
    private GroupService groupService;
    @Mock
    private CurrentUserService currentUserService;

    @InjectMocks
    private BalanceService balanceService;

    private static final Long GROUP_ID = 100L;

    private User alice;
    private User bob;
    private User carol;
    private Group group;

    @BeforeEach
    void setUp() {
        alice = TestFixtures.user(1L, "Alice");
        bob = TestFixtures.user(2L, "Bob");
        carol = TestFixtures.user(3L, "Carol");
        group = TestFixtures.group(GROUP_ID, "Weekend Trip");

        // lenient(): these three apply to most tests below, but the two
        // access-control tests (non-member, group not found) deliberately
        // short-circuit before ever reaching one or more of these calls -
        // that's correct behavior, not a reason for Mockito's strict-stub
        // checker to fail the test.
        lenient().when(currentUserService.getCurrentUser()).thenReturn(alice);
        lenient().when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(group));
        lenient().when(settlementRecordRepository.findByGroupId(GROUP_ID)).thenReturn(List.of());
    }

    @Test
    void twoPersonSplit_debtorOwesExactlyTheirShare() {
        // Alice pays 100, split equally -> Bob owes Alice 50.
        Split aliceShare = TestFixtures.split(alice, new BigDecimal("50.00"), false);
        Split bobShare = TestFixtures.split(bob, new BigDecimal("50.00"), false);
        Expense expense = TestFixtures.expense(alice, aliceShare, bobShare);
        when(expenseRepository.findByGroupIdOrderByExpenseDateDescCreatedAtDesc(GROUP_ID))
                .thenReturn(List.of(expense));

        GroupBalanceResponse response = balanceService.getGroupBalances(GROUP_ID);

        assertEquals(0, balanceOf(response, alice).compareTo(new BigDecimal("50.00")));
        assertEquals(0, balanceOf(response, bob).compareTo(new BigDecimal("-50.00")));

        assertEquals(1, response.getSettlements().size());
        Settlement settlement = response.getSettlements().get(0);
        assertEquals(bob.getId(), settlement.getFromUserId());
        assertEquals(alice.getId(), settlement.getToUserId());
        assertEquals(0, settlement.getAmount().compareTo(new BigDecimal("50.00")));
    }

    @Test
    void settledSplitNeverCountsAsOutstandingDebt() {
        // Same 100/50-50 split as above, but Bob's share is already marked
        // settled -> there should be no outstanding debt tracked at all,
        // not even a zeroed-out one.
        Split aliceShare = TestFixtures.split(alice, new BigDecimal("50.00"), false);
        Split bobShare = TestFixtures.split(bob, new BigDecimal("50.00"), true); // already settled
        Expense expense = TestFixtures.expense(alice, aliceShare, bobShare);
        when(expenseRepository.findByGroupIdOrderByExpenseDateDescCreatedAtDesc(GROUP_ID))
                .thenReturn(List.of(expense));

        GroupBalanceResponse response = balanceService.getGroupBalances(GROUP_ID);

        assertTrue(response.getBalances().isEmpty(), "a settled split should never be tracked as a balance");
        assertTrue(response.getSettlements().isEmpty());
    }

    @Test
    void recordedPaymentCancelsOutTheDebtItPays() {
        // Bob owes Alice 50 from an expense, then pays her back the full 50
        // via a recorded SettlementRecord (the "indirect settle-up" path
        // built in an earlier round) -> net should land on exactly zero,
        // leaving nothing left to settle.
        Split aliceShare = TestFixtures.split(alice, new BigDecimal("50.00"), false);
        Split bobShare = TestFixtures.split(bob, new BigDecimal("50.00"), false);
        Expense expense = TestFixtures.expense(alice, aliceShare, bobShare);
        when(expenseRepository.findByGroupIdOrderByExpenseDateDescCreatedAtDesc(GROUP_ID))
                .thenReturn(List.of(expense));

        SettlementRecord payment = TestFixtures.settlementRecord(group, bob, alice, new BigDecimal("50.00"));
        when(settlementRecordRepository.findByGroupId(GROUP_ID)).thenReturn(List.of(payment));

        GroupBalanceResponse response = balanceService.getGroupBalances(GROUP_ID);

        assertTrue(response.getSettlements().isEmpty(), "a fully paid-off debt should produce no settlement");
    }

    @Test
    void threePersonGroup_simplifyCollapsesChainedDebtIntoOnePayment() {
        // Dinner: Alice pays 90, split 3 ways (30 each) -> Bob and Carol
        // each owe Alice 30.
        // Cab: Bob pays 60, split with Carol only (30 each) -> Carol owes
        // Bob 30.
        //
        // Raw debts would be 3 payments (Bob->Alice 30, Carol->Alice 30,
        // Carol->Bob 30). Net balances: Alice +60, Bob 0 (he received 30
        // from Carol and owed 30 to Alice - a wash), Carol -60. simplify()
        // should collapse all of that into exactly ONE payment: Carol pays
        // Alice 60 directly, skipping Bob - who never shared a rupee with
        // Carol or Alice end-to-end but still nets to zero correctly.
        Split dinnerAlice = TestFixtures.split(alice, new BigDecimal("30.00"), false);
        Split dinnerBob = TestFixtures.split(bob, new BigDecimal("30.00"), false);
        Split dinnerCarol = TestFixtures.split(carol, new BigDecimal("30.00"), false);
        Expense dinner = TestFixtures.expense(alice, dinnerAlice, dinnerBob, dinnerCarol);

        Split cabBob = TestFixtures.split(bob, new BigDecimal("30.00"), false);
        Split cabCarol = TestFixtures.split(carol, new BigDecimal("30.00"), false);
        Expense cab = TestFixtures.expense(bob, cabBob, cabCarol);

        when(expenseRepository.findByGroupIdOrderByExpenseDateDescCreatedAtDesc(GROUP_ID))
                .thenReturn(List.of(dinner, cab));

        GroupBalanceResponse response = balanceService.getGroupBalances(GROUP_ID);

        assertEquals(1, response.getSettlements().size(),
                "simplify() should collapse this down to a single Carol -> Alice payment");
        Settlement only = response.getSettlements().get(0);
        assertEquals(carol.getId(), only.getFromUserId());
        assertEquals(alice.getId(), only.getToUserId());
        assertEquals(0, only.getAmount().compareTo(new BigDecimal("60.00")));
    }

    @Test
    void groupNotFound_throwsIllegalArgumentException() {
        Long missingGroupId = 999L;
        when(groupRepository.findById(missingGroupId)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> balanceService.getGroupBalances(missingGroupId));
    }

    @Test
    void nonMemberIsRejectedBeforeAnyBalanceIsComputed() {
        doThrow(new IllegalArgumentException("Not a member of this group"))
                .when(groupService).requireMember(GROUP_ID, alice.getId());

        assertThrows(IllegalArgumentException.class, () -> balanceService.getGroupBalances(GROUP_ID));
        verifyNoInteractions(expenseRepository);
    }

    private BigDecimal balanceOf(GroupBalanceResponse response, User user) {
        return response.getBalances().stream()
                .filter(b -> b.getUserId().equals(user.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No balance entry for " + user.getName()))
                .getNetBalance();
    }
}
