package com.vedant.hisaab.controller;

import com.vedant.hisaab.dto.CreateExpenseRequest;
import com.vedant.hisaab.dto.ExpenseResponse;
import com.vedant.hisaab.service.ExpenseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/groups/{groupId}/expenses")
@RequiredArgsConstructor
public class ExpenseController {

    private final ExpenseService expenseService;

    @PostMapping
    public ExpenseResponse createExpense(@PathVariable Long groupId, @Valid @RequestBody CreateExpenseRequest request) {
        return expenseService.createExpense(groupId, request);
    }

    @GetMapping
    public List<ExpenseResponse> listExpenses(@PathVariable Long groupId) {
        return expenseService.getExpensesForGroup(groupId);
    }

    /**
     * NEW — same request shape as create. Editing is restricted to whoever
     * paid for the expense OR whoever originally added it (see
     * ExpenseService.requireEditRights).
     */
    @PutMapping("/{expenseId}")
    public ExpenseResponse updateExpense(@PathVariable Long groupId, @PathVariable Long expenseId,
                                          @Valid @RequestBody CreateExpenseRequest request) {
        return expenseService.updateExpense(groupId, expenseId, request);
    }

    /** NEW — same dual authorization as update. */
    @DeleteMapping("/{expenseId}")
    public ResponseEntity<Void> deleteExpense(@PathVariable Long groupId, @PathVariable Long expenseId) {
        expenseService.deleteExpense(groupId, expenseId);
        return ResponseEntity.noContent().build();
    }
}
