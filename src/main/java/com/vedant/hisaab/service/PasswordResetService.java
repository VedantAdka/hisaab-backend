package com.vedant.hisaab.service;

import com.vedant.hisaab.entity.PasswordResetToken;
import com.vedant.hisaab.entity.User;
import com.vedant.hisaab.repository.PasswordResetTokenRepository;
import com.vedant.hisaab.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private static final Duration TOKEN_VALIDITY = Duration.ofMinutes(30);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JavaMailSender mailSender;

    @Value("${app.frontend-url:http://localhost:5173}")
    private String frontendUrl;

    @Value("${spring.mail.username:}")
    private String fromAddress;

    /**
     * Issues a reset link and emails it.
     *
     * If the email isn't registered we return quietly instead of erroring —
     * otherwise this endpoint becomes a way for anyone to check which email
     * addresses have accounts here. The controller always replies with the
     * same "if that email is registered..." message either way.
     */
    @Transactional
    public void requestReset(String email) {
        Optional<User> maybeUser = userRepository.findByEmail(email.trim());
        if (maybeUser.isEmpty()) {
            return;
        }
        User user = maybeUser.get();

        // One live link per user — issuing a new one invalidates the old.
        tokenRepository.deleteByUserId(user.getId());
        tokenRepository.flush();

        String rawToken = generateRawToken();
        tokenRepository.save(PasswordResetToken.builder()
                .tokenHash(sha256(rawToken))
                .user(user)
                .expiresAt(Instant.now().plus(TOKEN_VALIDITY))
                .build());

        sendResetEmail(user, rawToken);
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        PasswordResetToken token = tokenRepository.findByTokenHash(sha256(rawToken))
                .orElseThrow(() -> new IllegalArgumentException(
                        "This reset link is invalid or has already been used. Please request a new one."));

        if (token.isUsed()) {
            throw new IllegalArgumentException(
                    "This reset link has already been used. Please request a new one.");
        }
        if (token.getExpiresAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException(
                    "This reset link has expired. Please request a new one.");
        }

        User user = token.getUser();
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        token.setUsed(true);
        tokenRepository.save(token);
    }

    private void sendResetEmail(User user, String rawToken) {
        String link = frontendUrl + "/reset-password?token=" + rawToken;

        SimpleMailMessage message = new SimpleMailMessage();
        if (fromAddress != null && !fromAddress.isBlank()) {
            message.setFrom(fromAddress);
        }
        message.setTo(user.getEmail());
        message.setSubject("Reset your Hisaab password");
        message.setText("""
                Hi %s,

                Someone (hopefully you) asked to reset the password on your Hisaab account.

                Open this link to set a new password — it works once and expires in 30 minutes:

                %s

                If you didn't ask for this, you can ignore this email. Your password stays as it is.

                — Hisaab
                """.formatted(user.getName(), link));

        try {
            mailSender.send(message);
        } catch (MailException ex) {
            // GlobalExceptionHandler turns IllegalStateException into a 409 whose
            // body carries this message, so a broken SMTP config shows up in the
            // UI during development instead of being silently swallowed.
            throw new IllegalStateException("Couldn't send the reset email: " + ex.getMessage(), ex);
        }
    }

    private String generateRawToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available on this JVM", ex);
        }
    }
}
