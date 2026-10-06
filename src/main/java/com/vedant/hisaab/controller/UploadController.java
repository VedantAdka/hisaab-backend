package com.vedant.hisaab.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * NEW — lets the app attach a bill/receipt photo to an expense, either
 * snapped right then or picked from the gallery (the frontend's
 * AddExpenseModal already had those two buttons in the UI — they just
 * weren't wired to anything). Requires being logged in (same JWT filter as
 * every other /api/** endpoint), but isn't scoped to a specific group —
 * any authenticated user can upload a photo, the same as any authenticated
 * user can create an expense.
 *
 * Stores the file on local disk under uploads/receipts/ with a random
 * filename (never trusts the original filename) and returns an absolute
 * URL built from the current request, so it works whether the app is
 * running on localhost or somewhere deployed — the returned URL goes
 * straight into CreateExpenseRequest.receiptPhotoUrl.
 */
@RestController
@RequestMapping("/api/uploads")
public class UploadController {

    private static final Set<String> ALLOWED_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif");
    private static final long MAX_BYTES = 5L * 1024 * 1024; // 5MB — matches application.properties
    private static final Path STORAGE_DIR = Paths.get("uploads", "receipts");

    @PostMapping("/receipts")
    public ResponseEntity<Map<String, String>> uploadReceipt(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("No file was uploaded");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("Image must be under 5MB");
        }

        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!ALLOWED_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("Only JPEG, PNG, WEBP or HEIC images are allowed");
        }

        Files.createDirectories(STORAGE_DIR);

        String extension = switch (contentType) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/heic", "image/heif" -> ".heic";
            default -> ".jpg";
        };
        String filename = UUID.randomUUID() + extension;
        file.transferTo(STORAGE_DIR.resolve(filename));

        String url = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/uploads/receipts/" + filename)
                .toUriString();

        return ResponseEntity.ok(Map.of("url", url));
    }
}
