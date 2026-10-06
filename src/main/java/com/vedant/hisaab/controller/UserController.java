package com.vedant.hisaab.controller;

import com.vedant.hisaab.dto.UpdateProfileRequest;
import com.vedant.hisaab.dto.UpdateUpiIdRequest;
import com.vedant.hisaab.dto.UserResponse;
import com.vedant.hisaab.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users/me")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping
    public UserResponse getMe() {
        return userService.getCurrentUserProfile();
    }

    @PutMapping("/upi-id")
    public UserResponse updateUpiId(@Valid @RequestBody UpdateUpiIdRequest request) {
        return userService.updateUpiId(request.getUpiId());
    }

    @PutMapping
    public UserResponse updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
        return userService.updateProfile(request);
    }
}