package com.vedant.hisaab.service;

import com.vedant.hisaab.entity.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;

/**
 * Small, hand-written object builders used only by the test classes in this
 * package. Kept separate from the real entities so every test reads as plain
 * arithmetic ("Alice pays 100, split with Bob") instead of being buried in
 * JPA boilerplate.
 */
final class TestFixtures {

    private TestFixtures() {
    }

    static User user(Long id, String name) {
        return user(id, name, "upi-" + id + "@okhdfcbank");
    }

    static User user(Long id, String name, String upiId) {
        User user = new User();
        user.setId(id);
        user.setName(name);
        user.setEmail(name.toLowerCase() + "@example.com");
        user.setUpiId(upiId);
        return user;
    }

    static Group group(Long id, String name) {
        Group group = new Group();
        group.setId(id);
        group.setName(name);
        return group;
    }

    static Split split(User user, BigDecimal amount, boolean settled) {
        Split split = new Split();
        split.setUser(user);
        split.setAmount(amount);
        split.setSettled(settled);
        return split;
    }

    static Expense expense(User paidBy, Split... splits) {
        Expense expense = new Expense();
        expense.setPaidBy(paidBy);
        expense.setSplits(Arrays.asList(splits));
        return expense;
    }

    static SettlementRecord settlementRecord(Group group, User from, User to, BigDecimal amount) {
        SettlementRecord record = new SettlementRecord();
        record.setGroup(group);
        record.setFromUser(from);
        record.setToUser(to);
        record.setAmount(amount);
        record.setSettledAt(LocalDateTime.now());
        return record;
    }
}
