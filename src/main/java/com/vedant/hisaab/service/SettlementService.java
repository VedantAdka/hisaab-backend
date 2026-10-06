package com.vedant.hisaab.service;

import com.vedant.hisaab.dto.GroupBalanceResponse;
import com.vedant.hisaab.dto.MemberBalance;
import com.vedant.hisaab.dto.Settlement;
import com.vedant.hisaab.dto.SettleUpLinkResponse;
import com.vedant.hisaab.entity.Expense;
import com.vedant.hisaab.entity.Group;
import com.vedant.hisaab.entity.SettlementRecord;
import com.vedant.hisaab.entity.Split;
import com.vedant.hisaab.entity.User;
import com.vedant.hisaab.repository.ExpenseRepository;
import com.vedant.hisaab.repository.GroupRepository;
import com.vedant.hisaab.repository.SettlementRecordRepository;
import com.vedant.hisaab.repository.SplitRepository;
import com.vedant.hisaab.repository.UserRepository;
import com.vedant.hisaab.security.CurrentUserService;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

@Service
@RequiredArgsConstructor
public class SettlementService {

    private final ExpenseRepository expenseRepository;
    private final SplitRepository splitRepository;
    private final UserRepository userRepository;
    private final GroupRepository groupRepository;
    private final SettlementRecordRepository settlementRecordRepository;
    private final CurrentUserService currentUserService;
    private final GroupService groupService;
    private final JavaMailSender mailSender;

    @Value("${app.frontend-url:http://localhost:5173}")
    private String frontendUrl;

    @Value("${spring.mail.username:}")
    private String fromAddress;

    public SettleUpLinkResponse getSettleUpLink(Long groupId, Long otherUserId) {
        User currentUser = currentUserService.getCurrentUser();
        groupService.requireMember(groupId, currentUser.getId());
        groupService.requireMember(groupId, otherUserId);

        User otherUser = userRepository.findById(otherUserId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        BigDecimal net = netOwedByCurrentUserTo(groupId, currentUser, otherUser);
        BigDecimal amountIOwe;

        if (net.compareTo(BigDecimal.ZERO) < 0) {
            // A real, direct debt exists between exactly these two people (they've
            // literally shared an expense, or already recorded a settlement,
            // between just the two of them) — use that.
            amountIOwe = net.negate().setScale(2, RoundingMode.HALF_UP);
        } else {
            // No direct debt between exactly these two — but the Balances tab's
            // simplified, whole-group plan can still route a payment through this
            // exact pair. Falling back to that same number means Settle Up never
            // contradicts what the Balances tab already showed for this pair.
            amountIOwe = simplifiedAmountOwedTo(groupId, currentUser, otherUser);
            if (amountIOwe.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("You don't owe " + otherUser.getName() + " anything");
            }
        }

        if (otherUser.getUpiId() == null || otherUser.getUpiId().isBlank()) {
            throw new IllegalArgumentException(otherUser.getName() + " hasn't added a UPI ID yet");
        }

        String link = buildUpiLink(otherUser.getUpiId(), otherUser.getName(), amountIOwe);

        return SettleUpLinkResponse.builder()
                .fromUserId(currentUser.getId())
                .toUserId(otherUser.getId())
                .toUserName(otherUser.getName())
                .amount(amountIOwe)
                .upiId(otherUser.getUpiId())
                .upiDeepLink(link)
                .build();
    }

    /**
     * The "Remind" button: emails otherUser that they owe currentUser money in
     * this group. The amount is always recomputed here, the same way (and for
     * the same reason) getSettleUpLink recomputes rather than trusting a
     * client-supplied figure — whatever the email says is guaranteed to match
     * what the app itself would show, and a client can't nudge it to claim an
     * arbitrary amount.
     */
    public void sendReminder(Long groupId, Long otherUserId) {
        User currentUser = currentUserService.getCurrentUser();
        groupService.requireMember(groupId, currentUser.getId());
        groupService.requireMember(groupId, otherUserId);

        User otherUser = userRepository.findById(otherUserId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("Group not found"));

        BigDecimal net = netOwedByCurrentUserTo(groupId, currentUser, otherUser); // positive = they owe me
        BigDecimal amountTheyOweMe;

        if (net.compareTo(BigDecimal.ZERO) > 0) {
            amountTheyOweMe = net.setScale(2, RoundingMode.HALF_UP);
        } else {
            // No direct debt running their way — check the simplified,
            // whole-group plan for one instead (same fallback getSettleUpLink
            // uses, just with the two people swapped: here we're asking
            // whether otherUser owes currentUser, not the other way round).
            amountTheyOweMe = simplifiedAmountOwedTo(groupId, otherUser, currentUser);
            if (amountTheyOweMe.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException(otherUser.getName() + " doesn't owe you anything");
            }
        }

        sendReminderEmail(currentUser, otherUser, group, amountTheyOweMe);
    }

    private void sendReminderEmail(User from, User to, Group group, BigDecimal amount) {
        String link = frontendUrl + "/groups/" + group.getId();
        String formattedAmount = "₹" + amount.toPlainString();

        String plainText = """
                Hi %s,

                Just a nudge from %s — you owe %s for "%s" on Hisaab.

                Open the group to settle up:

                %s

                — Hisaab
                """.formatted(to.getName(), from.getName(), formattedAmount, group.getName(), link);

        String html = buildReminderHtml(from, to, group, formattedAmount, link);

        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            // multipart=true + the (text, html) overload of setText is what gives
            // this a plain-text fallback alongside the styled version — every
            // client that can't or won't render HTML still gets something
            // readable instead of a blank email.
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
            if (fromAddress != null && !fromAddress.isBlank()) {
                helper.setFrom(fromAddress);
            }
            helper.setTo(to.getEmail());
            helper.setSubject(from.getName() + " sent you a payment reminder on Hisaab");
            helper.setText(plainText, html);
            mailSender.send(mimeMessage);
        } catch (MessagingException | MailException ex) {
            // GlobalExceptionHandler turns IllegalStateException into a 409 whose
            // body carries this message, same as the password-reset email path —
            // so a broken SMTP config shows up in the UI during development
            // instead of being silently swallowed.
            throw new IllegalStateException("Couldn't send the reminder email: " + ex.getMessage(), ex);
        }
    }

    /**
     * A small, self-contained HTML email — table-based layout and inline
     * styles throughout (no external stylesheet, no hosted images), which is
     * what keeps it rendering consistently across Gmail, Outlook and phone
     * mail apps instead of depending on things those clients routinely strip.
     */
    private String buildReminderHtml(User from, User to, Group group, String formattedAmount, String link) {
        return """
                <!DOCTYPE html>
                <html>
                  <body style="margin:0;padding:0;background-color:#f7f6f3;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;">
                    <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background-color:#f7f6f3;padding:32px 16px;">
                      <tr>
                        <td align="center">
                          <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="max-width:480px;background-color:#ffffff;border-radius:16px;border:1px solid #eceae5;">
                            <tr>
                              <td style="padding:28px 32px 20px 32px;">
                                <table role="presentation" cellpadding="0" cellspacing="0">
                                  <tr>
                                    <td style="width:36px;height:36px;background-color:#d9730d;border-radius:10px;text-align:center;vertical-align:middle;">
                                      <span style="color:#ffffff;font-size:18px;font-weight:700;line-height:36px;">₹</span>
                                    </td>
                                    <td style="padding-left:10px;">
                                      <span style="font-size:18px;font-weight:800;color:#5c3a1e;">Hisaab</span>
                                    </td>
                                  </tr>
                                </table>
                              </td>
                            </tr>
                            <tr>
                              <td style="padding:0 32px;">
                                <p style="margin:0 0 4px 0;font-size:15px;color:#1c1b1a;">Hi %s,</p>
                                <p style="margin:0 0 20px 0;font-size:15px;color:#4a453d;line-height:1.6;">
                                  Just a nudge from <strong style="color:#1c1b1a;">%s</strong> on <strong>%s</strong>.
                                </p>
                              </td>
                            </tr>
                            <tr>
                              <td style="padding:0 32px;">
                                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background-color:#fbe7d4;border-radius:14px;">
                                  <tr>
                                    <td style="padding:20px 24px;">
                                      <div style="font-size:11px;font-weight:700;letter-spacing:0.04em;color:#8f460c;text-transform:uppercase;">You owe</div>
                                      <div style="font-size:32px;font-weight:800;color:#8f460c;margin-top:4px;">%s</div>
                                    </td>
                                  </tr>
                                </table>
                              </td>
                            </tr>
                            <tr>
                              <td style="padding:24px 32px 8px 32px;" align="center">
                                <table role="presentation" cellpadding="0" cellspacing="0">
                                  <tr>
                                    <td style="border-radius:12px;background-color:#d9730d;">
                                      <a href="%s" style="display:inline-block;padding:14px 28px;font-size:14px;font-weight:700;color:#ffffff;text-decoration:none;border-radius:12px;">
                                        Open Hisaab
                                      </a>
                                    </td>
                                  </tr>
                                </table>
                              </td>
                            </tr>
                            <tr>
                              <td style="padding:20px 32px 28px 32px;">
                                <p style="margin:0;font-size:12px;color:#9a9284;line-height:1.6;text-align:center;">
                                  There's no payment gateway behind this — settle up with %s directly, then mark it settled in the app.
                                </p>
                              </td>
                            </tr>
                          </table>
                          <p style="margin:20px 0 0 0;font-size:11.5px;color:#b3ada2;">— Hisaab</p>
                        </td>
                      </tr>
                    </table>
                  </body>
                </html>
                """.formatted(to.getName(), from.getName(), group.getName(), formattedAmount, link, from.getName());
    }

    @Transactional
    public void markSettled(Long groupId, Long otherUserId) {
        User currentUser = currentUserService.getCurrentUser();
        groupService.requireMember(groupId, currentUser.getId());
        groupService.requireMember(groupId, otherUserId);

        List<Expense> expenses = expenseRepository.findByGroupIdOrderByExpenseDateDescCreatedAtDesc(groupId);
        boolean anyUpdated = false;

        for (Expense expense : expenses) {
            for (Split split : expense.getSplits()) {
                if (split.isSettled()) continue;

                boolean currentUserOwesOther = split.getUser().getId().equals(currentUser.getId())
                        && expense.getPaidBy().getId().equals(otherUserId);
                boolean otherOwesCurrentUser = split.getUser().getId().equals(otherUserId)
                        && expense.getPaidBy().getId().equals(currentUser.getId());

                if (currentUserOwesOther || otherOwesCurrentUser) {
                    split.setSettled(true);
                    splitRepository.save(split);
                    anyUpdated = true;
                }
            }
        }

        if (anyUpdated) {
            return;
        }

        // No direct expense between exactly these two people — fall back to the
        // simplified, whole-group plan. If it says currentUser owes otherUser,
        // record that as a real payment (a SettlementRecord) instead of
        // refusing outright, so these indirect settlements can be closed out
        // too, not just paid via the link and then stuck forever.
        User otherUser = userRepository.findById(otherUserId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        BigDecimal indirect = simplifiedAmountOwedTo(groupId, currentUser, otherUser);
        if (indirect.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Nothing outstanding to settle with this person");
        }

        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("Group not found"));

        SettlementRecord record = new SettlementRecord();
        record.setGroup(group);
        record.setFromUser(currentUser);
        record.setToUser(otherUser);
        record.setAmount(indirect);
        record.setSettledAt(LocalDateTime.now());
        settlementRecordRepository.save(record);
    }

    /**
     * The "exact" view: every real, direct pairwise debt in the group — one
     * settlement entry per pair of people who actually share an expense (or
     * have a recorded settlement) between just the two of them — never
     * collapsed through a third party the way the simplified plan is. More
     * transactions than the Balances tab's default view, but every single one
     * is between two people who can point to a specific shared expense.
     */
    public GroupBalanceResponse getDirectGroupBalances(Long groupId) {
        User currentUser = currentUserService.getCurrentUser();
        groupService.requireMember(groupId, currentUser.getId());
        groupRepository.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("Group not found"));

        List<Expense> expenses = expenseRepository.findByGroupIdOrderByExpenseDateDescCreatedAtDesc(groupId);

        Map<User, BigDecimal> totals = new LinkedHashMap<>();
        Map<String, BigDecimal> pairNet = new LinkedHashMap<>();
        Map<Long, User> usersById = new LinkedHashMap<>();

        for (Expense expense : expenses) {
            User payer = expense.getPaidBy();
            usersById.putIfAbsent(payer.getId(), payer);

            for (Split split : expense.getSplits()) {
                if (split.isSettled()) continue;
                User debtor = split.getUser();
                if (debtor.getId().equals(payer.getId())) continue;
                usersById.putIfAbsent(debtor.getId(), debtor);

                totals.merge(debtor, split.getAmount().negate(), BigDecimal::add);
                totals.merge(payer, split.getAmount(), BigDecimal::add);
                applyPairDelta(pairNet, debtor, payer, split.getAmount());
            }
        }

        for (SettlementRecord record : settlementRecordRepository.findByGroupId(groupId)) {
            User from = record.getFromUser();
            User to = record.getToUser();
            usersById.putIfAbsent(from.getId(), from);
            usersById.putIfAbsent(to.getId(), to);

            totals.merge(from, record.getAmount(), BigDecimal::add);
            totals.merge(to, record.getAmount().negate(), BigDecimal::add);
            // A payment reduces what `from` owes `to` — the opposite direction
            // of a split, hence the negation here.
            applyPairDelta(pairNet, from, to, record.getAmount().negate());
        }

        List<MemberBalance> balances = totals.entrySet().stream()
                .map(e -> MemberBalance.builder()
                        .userId(e.getKey().getId())
                        .name(e.getKey().getName())
                        .netBalance(e.getValue().setScale(2, RoundingMode.HALF_UP))
                        .build())
                .toList();

        List<Settlement> settlements = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> entry : pairNet.entrySet()) {
            BigDecimal net = entry.getValue().setScale(2, RoundingMode.HALF_UP);
            if (net.compareTo(BigDecimal.ZERO) == 0) continue;

            String[] ids = entry.getKey().split("_");
            Long smallerId = Long.valueOf(ids[0]);
            Long biggerId = Long.valueOf(ids[1]);

            // Positive net means `smaller` owes `bigger`; negative means the reverse.
            Long debtorId = net.compareTo(BigDecimal.ZERO) > 0 ? smallerId : biggerId;
            Long creditorId = net.compareTo(BigDecimal.ZERO) > 0 ? biggerId : smallerId;
            User debtor = usersById.get(debtorId);
            User creditor = usersById.get(creditorId);

            settlements.add(Settlement.builder()
                    .fromUserId(debtor.getId())
                    .fromUserName(debtor.getName())
                    .toUserId(creditor.getId())
                    .toUserName(creditor.getName())
                    .amount(net.abs())
                    .build());
        }

        return GroupBalanceResponse.builder()
                .groupId(groupId)
                .balances(balances)
                .settlements(settlements)
                .build();
    }

    /** Accumulates `amount` owed from `debtor` to `creditor`, keyed by an
     * order-independent pair id, always expressed relative to whichever of
     * the two has the smaller user id — so two entries for the same pair
     * (one from an expense, one from a settlement, in either direction)
     * always land in the same bucket and net against each other correctly. */
    private void applyPairDelta(Map<String, BigDecimal> pairNet, User debtor, User creditor, BigDecimal amount) {
        Long a = debtor.getId();
        Long b = creditor.getId();
        Long smaller = a < b ? a : b;
        Long bigger = a < b ? b : a;
        String key = smaller + "_" + bigger;
        BigDecimal delta = a.equals(smaller) ? amount : amount.negate();
        pairNet.merge(key, delta, BigDecimal::add);
    }

    private BigDecimal netOwedByCurrentUserTo(Long groupId, User currentUser, User otherUser) {
        List<Expense> expenses = expenseRepository.findByGroupIdOrderByExpenseDateDescCreatedAtDesc(groupId);
        BigDecimal iOweThem = BigDecimal.ZERO;
        BigDecimal theyOweMe = BigDecimal.ZERO;

        for (Expense expense : expenses) {
            for (Split split : expense.getSplits()) {
                if (split.isSettled()) continue;
                if (split.getUser().getId().equals(currentUser.getId())
                        && expense.getPaidBy().getId().equals(otherUser.getId())) {
                    iOweThem = iOweThem.add(split.getAmount());
                } else if (split.getUser().getId().equals(otherUser.getId())
                        && expense.getPaidBy().getId().equals(currentUser.getId())) {
                    theyOweMe = theyOweMe.add(split.getAmount());
                }
            }
        }

        for (SettlementRecord record : settlementRecordRepository.findByGroupId(groupId)) {
            if (record.getFromUser().getId().equals(currentUser.getId())
                    && record.getToUser().getId().equals(otherUser.getId())) {
                iOweThem = iOweThem.subtract(record.getAmount());
            } else if (record.getFromUser().getId().equals(otherUser.getId())
                    && record.getToUser().getId().equals(currentUser.getId())) {
                theyOweMe = theyOweMe.subtract(record.getAmount());
            }
        }

        return theyOweMe.subtract(iOweThem); // positive = they owe me; negative = I owe them
    }

    /**
     * Mirrors BalanceService's own net-balance + simplify() algorithm (same
     * net-map construction, same max-creditor/max-debtor greedy pairing),
     * kept as an independent calculation here on purpose — same reasoning as
     * netOwedByCurrentUserTo above, just extended to the whole group instead
     * of one pair, and also folding in recorded SettlementRecords so an
     * indirect settlement, once paid, actually changes this number. Returns
     * the amount the simplified, fewest-transactions plan says currentUser
     * owes otherUser, or ZERO if the plan never pairs these two.
     */
    private BigDecimal simplifiedAmountOwedTo(Long groupId, User currentUser, User otherUser) {
        List<Expense> expenses = expenseRepository.findByGroupIdOrderByExpenseDateDescCreatedAtDesc(groupId);
        Map<User, BigDecimal> net = new LinkedHashMap<>();

        for (Expense expense : expenses) {
            for (Split split : expense.getSplits()) {
                User debtor = split.getUser();
                User creditor = expense.getPaidBy();

                if (debtor.getId().equals(creditor.getId()) || split.isSettled()) {
                    continue;
                }

                net.merge(debtor, split.getAmount().negate(), BigDecimal::add);
                net.merge(creditor, split.getAmount(), BigDecimal::add);
            }
        }

        for (SettlementRecord record : settlementRecordRepository.findByGroupId(groupId)) {
            net.merge(record.getFromUser(), record.getAmount(), BigDecimal::add);
            net.merge(record.getToUser(), record.getAmount().negate(), BigDecimal::add);
        }

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

        while (!creditors.isEmpty() && !debtors.isEmpty()) {
            Map.Entry<User, BigDecimal> creditor = creditors.poll();
            Map.Entry<User, BigDecimal> debtor = debtors.poll();

            BigDecimal creditAmt = creditor.getValue();
            BigDecimal debtAmt = debtor.getValue().abs();
            BigDecimal settleAmt = creditAmt.min(debtAmt);

            if (debtor.getKey().getId().equals(currentUser.getId())
                    && creditor.getKey().getId().equals(otherUser.getId())) {
                return settleAmt;
            }

            BigDecimal creditorRemaining = creditAmt.subtract(settleAmt);
            BigDecimal debtorRemaining = debtAmt.subtract(settleAmt);

            if (creditorRemaining.compareTo(BigDecimal.ZERO) > 0) {
                creditors.add(new AbstractMap.SimpleEntry<>(creditor.getKey(), creditorRemaining));
            }
            if (debtorRemaining.compareTo(BigDecimal.ZERO) > 0) {
                debtors.add(new AbstractMap.SimpleEntry<>(debtor.getKey(), debtorRemaining.negate()));
            }
        }

        return BigDecimal.ZERO; // the simplified plan never pairs exactly these two
    }

    private String buildUpiLink(String vpa, String payeeName, BigDecimal amount) {
        String encodedName = URLEncoder.encode(payeeName, StandardCharsets.UTF_8);
        String encodedNote = URLEncoder.encode("Hisaab settle-up", StandardCharsets.UTF_8);
        return "upi://pay?pa=" + vpa + "&pn=" + encodedName + "&am=" + amount + "&cu=INR&tn=" + encodedNote;
    }
}
