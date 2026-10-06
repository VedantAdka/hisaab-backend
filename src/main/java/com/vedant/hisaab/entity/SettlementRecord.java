package com.vedant.hisaab.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A real, recorded payment between two people in a group — distinct from a
 * Split, which represents a share of one specific expense. This exists for
 * "indirect" settlements: cases where the Balances tab's simplified,
 * fewest-transactions plan suggests a payment between two people who never
 * actually shared an expense directly, so there's no Split to flip to
 * "settled". Recording one of these here, and netting it in alongside
 * unsettled splits wherever balances are computed, is what lets that kind of
 * settlement actually close out instead of reappearing forever.
 */
@Entity
@Table(name = "settlement_records")
@Data
public class SettlementRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    private Group group;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_user_id", nullable = false)
    private User fromUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_user_id", nullable = false)
    private User toUser;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "settled_at", nullable = false)
    private LocalDateTime settledAt;
}
