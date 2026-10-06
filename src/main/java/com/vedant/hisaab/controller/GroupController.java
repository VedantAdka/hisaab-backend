package com.vedant.hisaab.controller;

import com.vedant.hisaab.dto.AddMemberRequest;
import com.vedant.hisaab.dto.CreateGroupRequest;
import com.vedant.hisaab.dto.GroupResponse;
import com.vedant.hisaab.dto.UpdateGroupRequest;
import com.vedant.hisaab.service.GroupService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/groups")
@RequiredArgsConstructor
public class GroupController {

    private final GroupService groupService;

    @PostMapping
    public GroupResponse createGroup(@Valid @RequestBody CreateGroupRequest request) {
        return groupService.createGroup(request);
    }

    @GetMapping
    public List<GroupResponse> myGroups() {
        return groupService.getMyGroups();
    }

    @GetMapping("/{groupId}")
    public GroupResponse getGroup(@PathVariable Long groupId) {
        return groupService.getGroup(groupId);
    }

    @PostMapping("/{groupId}/members")
    public GroupResponse addMember(@PathVariable Long groupId, @Valid @RequestBody AddMemberRequest request) {
        return groupService.addMember(groupId, request);
    }

    /** NEW — creator only; the creator themself can't be removed this way. */
    @DeleteMapping("/{groupId}/members/{userId}")
    public GroupResponse removeMember(@PathVariable Long groupId, @PathVariable Long userId) {
        return groupService.removeMember(groupId, userId);
    }

    /** NEW — creator only; renames the group. */
    @PutMapping("/{groupId}")
    public GroupResponse updateGroup(@PathVariable Long groupId, @Valid @RequestBody UpdateGroupRequest request) {
        return groupService.updateGroup(groupId, request);
    }

    /** creator only; deletes the group with all its expenses and activity. */
    @DeleteMapping("/{groupId}")
    public ResponseEntity<Void> deleteGroup(@PathVariable Long groupId) {
        groupService.deleteGroup(groupId);
        return ResponseEntity.noContent().build();
    }
}
