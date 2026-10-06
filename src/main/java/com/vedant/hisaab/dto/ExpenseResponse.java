package com.vedant.hisaab.dto;

import com.vedant.hisaab.entity.Category;
import com.vedant.hisaab.entity.SplitType;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
public class ExpenseResponse {
    private Long id;
    private String description;
    private BigDecimal amount;
    private String currency;
    private Category category;
    private SplitType splitType;
    private Long paidByUserId;
    private String paidByName;
    private Long addedByUserId;   // NEW — who created this expense (may differ from paidByUserId)
    private String addedByName;   // NEW
    private LocalDate expenseDate;
    private String notes;
    private String receiptPhotoUrl;
    private List<SplitResponse> splits;
}
