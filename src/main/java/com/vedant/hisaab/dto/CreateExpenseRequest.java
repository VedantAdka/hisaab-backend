package com.vedant.hisaab.dto;

import com.vedant.hisaab.entity.Category;
import com.vedant.hisaab.entity.SplitType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
public class CreateExpenseRequest {
    @NotBlank
    private String description;

    @NotNull @Positive
    private BigDecimal amount;

    private String currency = "INR";

    @NotNull
    private Category category;

    @NotNull
    private SplitType splitType;

    private Long paidByUserId; // null = current user paid

    private LocalDate expenseDate; // null = today

    private String notes;

    private String receiptPhotoUrl;

    @NotEmpty
    private List<@Valid ParticipantShare> participants;
}