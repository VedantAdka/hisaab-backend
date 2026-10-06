package com.vedant.hisaab.service;

import com.vedant.hisaab.dto.GroupBalanceResponse;
import com.vedant.hisaab.dto.SettleUpLinkResponse;
import com.vedant.hisaab.entity.*;
import com.vedant.hisaab.repository.*;
import com.vedant.hisaab.security.CurrentUserService;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for Settle Up, Remind, and Mark Settled - the three places
 * where this app touches real money and real email, so each one is tested
 * both for its happy path and for the specific guardrail it exists to
 * enforce (never trust a client-supplied amount; never let a direct and an
 * indirect debt contradict each other).
 */
@ExtendWith(MockitoExtension.class)
class SettlementServiceTest {

    @Mock
    private ExpenseRepository expenseRepository;
    @Mock
    private SplitRepository splitRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private GroupRepository groupRepository;
    @Mock
    private SettlementRecordRepository settlementRecordRepository;
    @Mock
    private CurrentUserService currentUserService;
    @Mock
    private GroupService groupService;
    @Mock
    private JavaMailSender mailSender;

    @InjectMocks
    private SettlementService settlementService;

    private static final Long GROUP_ID = 100L;

    private User alice;
    private User bob;
    private User carol;
    private Group group;

    @BeforeEach
    void setUp() throws Exception {
        alice = TestFixtures.user(1L, "Alice");
        bob = TestFixtures.user(2L, "Bob");
        carol = TestFixtures.user(3L, "Carol");
        group = TestFixtures.group(GROUP_ID, "Weekend Trip");

        // lenient(): only the sendReminder tests actually touch
        // settlementRecordRepository or mailSender; Settle Up, Mark
        // Settled, and the direct-balances tests legitimately never call
        // them, which is correct behavior, not an unused-stub problem.
        lenient().when(settlementRecordRepository.findByGroupId(GROUP_ID)).thenReturn(List.of());
        ReflectionTestUtils.setField(settlementService, "frontendUrl", "http://localhost:5173");

        // A real (but never actually sent over SMTP) MimeMessage, so
        // MimeMessageHelper has something genuine to write headers and
        // content into - a mocked MimeMessage would reject those calls.
        lenient().when(mailSender.createMimeMessage())
                .thenReturn(new MimeMessage(Session.getInstance(new Properties())));
    }

    // ---------- getSettleUpLink ----------

    @Test
    void settleUp_usesDirectDebtWhenOneExists() {
        // Bob pays 100, split with Alice (50 each) -> Alice owes Bob 50 directly.
        currentUserIs(alice);
        when(userRepository.findById(bob.getId())).thenReturn(Optional.of(bob));
        Split bobShare = TestFixtures.split(bob, new BigDecimal("50.00"), false);
        Split aliceShare = TestFixtures.split(alice, new BigDecimal("50.00"), false);
        when(expenseRepository.findByGroupIdOrderByExpenseDateDescCreatedAtDesc(GROUP_ID))
                .thenReturn(List.of(TestFixtures.expense(bob, bobShare, aliceShare)));

        SettleUpLinkResponse response = settlementService.getSettleUpLink(GROUP_ID, bob.getId());

        assertEquals(0, response.getAmount().compareTo(new BigDecimal("50.00")));
        assertEquals(bob.getId(), response.getToUserId());
        assertTrue(response.getUpiDeepLink().startsWith("upi://pay"));
    }

    @Test
    void settleUp_fallsBackToSimplifiedPlanWhenNoDirectDebtExists() {
        // Bob pays 60, split with Carol only (30 each) -> Carol owes Bob 30.
        // Alice pays 60, split with Bob only (30 each) -> Bob owes Alice 30.
        // Alice and Carol never share a single expense directly, but the
        // simplified whole-group plan nets Bob out to zero and pairs
        // Carol -> Alice for 30 - exactly like the Balances tab would show.
        currentUserIs(carol);
        when(userRepository.findById(alice.getId())).thenReturn(Optional.of(alice));

        Split cabBob = TestFixtures.split(bob, new BigDecimal("30.00"), false);
        Split cabCarol = TestFixtures.split(carol, new BigDecimal("30.00"), false);
        Expense cab = TestFixtures.expense(bob, cabBob, cabCarol);

        Split dinnerAlice = TestFixtures.split(alice, new BigDecimal("30.00"), false);
        Split dinnerBob = TestFixtures.split(bob, new BigDecimal("30.00"), false);
        Expense dinner = TestFixtures.expense(alice, dinnerAlice, dinnerBob);

        when(expenseRepository.findByGroupIdOrderByExpenseDateDescCreatedAtDesc(GROUP_ID))
                .thenReturn(List.of(cab, dinner));

        SettleUpLinkResponse response = settlementService.getSettleUpLink(GROUP_ID, alice.getId());

        assertEquals(0, response.getAmount().compareTo(new BigDecimal("30.00")));
        assertEquals(alice.getId(), response.getToUserId());
    }

    @Test
    void settleUp_throwsWhenNothingIsOwedEitherDirectlyOrInSimplifiedPlan() {
        currentUserIs(alice);
        when(userRepository.findById(bob.getId())).thenReturn(Optional.of(bob));
        when(expenseRepository.findByGroupIdOrderByExpenseDateDescCreatedAtDesc(GROUP_ID)).thenReturn(List.of());

        assertThrows(IllegalArgumentException.class, () -> settlementService.getSettleUpLink(GROUP_ID, bob.getId()));
    }

    @Test
    void settleUp_throwsWhenOwedPersonHasNoUpiId() {
        currentUserIs(alice);
        User bobWithoutUpi = TestFixtures.user(bob.getId(), "Bob", null);
        when(userRepository.findById(bob.getId())).thenReturn(Optional.of(bobWithoutUpi));
        Split bobShare = TestFixtures.split(bobWithoutUpi, new BigDecimal("50.00"), false);
        Split aliceShare = TestFixtures.split(alice, new BigDecimal("50.00"), false);
        when(expenseRepository.findByGroupIdOrderByExpenseDateDescCreatedAtDesc(GROUP_ID))
                .thenReturn(List.of(TestFixtures.expense(bobWithoutUpi, bobShare, aliceShare)));

        assertThrows(IllegalArgumentException.class, () -> settlementService.getSettleUpLink(GROUP_ID, bob.getId()));
    }

    // ---------- sendReminder ----------

    @Test
    void sendReminder_sendsEmailWhenOtherPersonOwesCurrentUser() {
        // Bob owes Alice 50; Alice (current user) reminds Bob.
        currentUserIs(alice);
        when(userRepository.findById(bob.getId())).thenReturn(Optional.of(bob));
        when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(group));
        Split aliceShare = TestFixtures.split(alice, new BigDecimal("50.00"), false);
        Split bobShare = TestFixtures.split(bob, new BigDecimal("50.00"), false);
        when(expenseRepository.findByGroupIdOrderByExpenseDateDescCreatedAtDesc(GROUP_ID))
                .thenReturn(List.of(TestFixtures.expense(alice, aliceShare, bobShare)));

        settlementService.sendReminder(GROUP_ID, bob.getId());

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender, times(1)).send(captor.capture());
    }

    @Test
    void sendReminder_throwsAndSendsNoEmailWhenOtherPersonOwesNothing() {
        currentUserIs(alice);
        when(userRepository.findById(bob.getId())).thenReturn(Optional.of(bob));
        when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(group));
        when(expenseRepository.findByGroupIdOrderByExpenseDateDescCreatedAtDesc(GROUP_ID)).thenReturn(List.of());

        assertThrows(IllegalArgumentException.class, () -> settlementService.sendReminder(GROUP_ID, bob.getId()));
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    // ---------- markSettled ----------

    @Test
    void markSettled_marksTheMatchingDirectSplitAndRecordsNoPayment() {
        // Bob pays 100, split with Alice (50 each) -> Alice owes Bob 50
        // directly, via one real Split row. Marking this settled should
        // flip that exact split, not create a SettlementRecord.
        currentUserIs(alice);
        Split bobShare = TestFixtures.split(bob, new BigDecimal("50.00"), false);
        Split aliceShare = TestFixtures.split(alice, new BigDecimal("50.00"), false);
        when(expenseRepository.findByGroupIdOrderByExpenseDateDescCreatedAtDesc(GROUP_ID))
                .thenReturn(List.of(TestFixtures.expense(bob, bobShare, aliceShare)));

        settlementService.markSettled(GROUP_ID, bob.getId());

        verify(splitRepository, times(1)).save(aliceShare);
        assertTrue(aliceShare.isSettled());
        verify(settlementRecordRepository, never()).save(any());
        verify(userRepository, never()).findById(any());
    }

    @Test
    void markSettled_recordsAnIndirectPaymentWhenNoDirectSplitExists() {
        // Same no-direct-expense setup as the simplified fallback test
        // above: Carol owes Alice 30 only through the whole-group plan,
        // never a split they share directly. Marking this settled must
        // fall back to creating a SettlementRecord.
        currentUserIs(carol);
        when(userRepository.findById(alice.getId())).thenReturn(Optional.of(alice));
        when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(group));

        Split cabBob = TestFixtures.split(bob, new BigDecimal("30.00"), false);
        Split cabCarol = TestFixtures.split(carol, new BigDecimal("30.00"), false);
        Expense cab = TestFixtures.expense(bob, cabBob, cabCarol);

        Split dinnerAlice = TestFixtures.split(alice, new BigDecimal("30.00"), false);
        Split dinnerBob = TestFixtures.split(bob, new BigDecimal("30.00"), false);
        Expense dinner = TestFixtures.expense(alice, dinnerAlice, dinnerBob);

        when(expenseRepository.findByGroupIdOrderByExpenseDateDescCreatedAtDesc(GROUP_ID))
                .thenReturn(List.of(cab, dinner));

        settlementService.markSettled(GROUP_ID, alice.getId());

        ArgumentCaptor<SettlementRecord> captor = ArgumentCaptor.forClass(SettlementRecord.class);
        verify(settlementRecordRepository, times(1)).save(captor.capture());
        SettlementRecord saved = captor.getValue();
        assertEquals(carol.getId(), saved.getFromUser().getId());
        assertEquals(alice.getId(), saved.getToUser().getId());
        assertEquals(0, saved.getAmount().compareTo(new BigDecimal("30.00")));
        verify(splitRepository, never()).save(any());
    }

    @Test
    void markSettled_throwsWhenThereIsNothingToSettle() {
        currentUserIs(alice);
        when(userRepository.findById(bob.getId())).thenReturn(Optional.of(bob));
        when(expenseRepository.findByGroupIdOrderByExpenseDateDescCreatedAtDesc(GROUP_ID)).thenReturn(List.of());

        assertThrows(IllegalArgumentException.class, () -> settlementService.markSettled(GROUP_ID, bob.getId()));
        verify(splitRepository, never()).save(any());
        verify(settlementRecordRepository, never()).save(any());
    }

    // ---------- getDirectGroupBalances ----------

    @Test
    void directBalances_keepsEveryRealPairSeparateInsteadOfCollapsingThroughAThirdParty() {
        // Exact same dinner + cab data as BalanceServiceTest's simplify
        // test, which collapses this into ONE payment (Carol -> Alice 60).
        // The "exact" view must instead show all three real, direct debts
        // untouched: Bob->Alice 30, Carol->Alice 30, Carol->Bob 30 - proving
        // the two tabs compute from the same totals but never contradict
        // each other on what's actually owed.
        currentUserIs(alice);
        when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(group));

        Split dinnerAlice = TestFixtures.split(alice, new BigDecimal("30.00"), false);
        Split dinnerBob = TestFixtures.split(bob, new BigDecimal("30.00"), false);
        Split dinnerCarol = TestFixtures.split(carol, new BigDecimal("30.00"), false);
        Expense dinner = TestFixtures.expense(alice, dinnerAlice, dinnerBob, dinnerCarol);

        Split cabBob = TestFixtures.split(bob, new BigDecimal("30.00"), false);
        Split cabCarol = TestFixtures.split(carol, new BigDecimal("30.00"), false);
        Expense cab = TestFixtures.expense(bob, cabBob, cabCarol);

        when(expenseRepository.findByGroupIdOrderByExpenseDateDescCreatedAtDesc(GROUP_ID))
                .thenReturn(List.of(dinner, cab));

        GroupBalanceResponse response = settlementService.getDirectGroupBalances(GROUP_ID);

        assertEquals(3, response.getSettlements().size(),
                "the exact view must keep all three direct debts, not collapse them");
        assertTrue(containsSettlement(response, bob, alice, "30.00"));
        assertTrue(containsSettlement(response, carol, alice, "30.00"));
        assertTrue(containsSettlement(response, carol, bob, "30.00"));
    }

    private boolean containsSettlement(GroupBalanceResponse response, User from, User to, String amount) {
        return response.getSettlements().stream().anyMatch(s ->
                s.getFromUserId().equals(from.getId())
                        && s.getToUserId().equals(to.getId())
                        && s.getAmount().compareTo(new BigDecimal(amount)) == 0);
    }

    private void currentUserIs(User user) {
        when(currentUserService.getCurrentUser()).thenReturn(user);
    }
}
