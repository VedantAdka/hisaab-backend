package com.vedant.hisaab.service;

import com.vedant.hisaab.dto.UpdateProfileRequest;
import com.vedant.hisaab.dto.UserResponse;
import com.vedant.hisaab.entity.User;
import com.vedant.hisaab.repository.UserRepository;
import com.vedant.hisaab.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final CurrentUserService currentUserService;

    public UserResponse getCurrentUserProfile() {
        return toResponse(currentUserService.getCurrentUser());
    }

    @Transactional
    public UserResponse updateProfile(UpdateProfileRequest request) {
        User user = currentUserService.getCurrentUser();

        if (userRepository.existsByPhoneNumberAndIdNot(request.getPhoneNumber(), user.getId())) {
            throw new IllegalArgumentException("That phone number is already registered to another account");
        }
        
        user.setName(request.getName());
        user.setPhoneNumber(request.getPhoneNumber());
        userRepository.save(user);
        return toResponse(user);
    }

    @Transactional
    public UserResponse updateUpiId(String upiId) {
        User user = currentUserService.getCurrentUser();
        user.setUpiId(upiId);
        user = userRepository.save(user);
        return toResponse(user);
    }

    private UserResponse toResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .phoneNumber(user.getPhoneNumber())
                .upiId(user.getUpiId())
                .build();
    }
}