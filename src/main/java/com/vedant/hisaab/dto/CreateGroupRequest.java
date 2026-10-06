package com.vedant.hisaab.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class CreateGroupRequest {
    @NotBlank
    private String name;

    private List<String> memberIdentifiers; // each entry: an email OR a phone number
}