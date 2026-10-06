package com.vedant.hisaab.service;

import com.vedant.hisaab.dto.*;
import com.vedant.hisaab.entity.ActivityType;
import com.vedant.hisaab.entity.Group;
import com.vedant.hisaab.entity.GroupMember;
import com.vedant.hisaab.entity.User;
import com.vedant.hisaab.repository.ActivityRepository;
import com.vedant.hisaab.repository.ExpenseRepository;
import com.vedant.hisaab.repository.GroupMemberRepository;
import com.vedant.hisaab.repository.GroupRepository;
import com.vedant.hisaab.repository.UserRepository;
import com.vedant.hisaab.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class GroupService {

    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final UserRepository userRepository;
    private final CurrentUserService currentUserService;
    private final ActivityService activityService;
    private final ExpenseRepository expenseRepository;     // needed to clear a group's expenses on delete
    private final ActivityRepository activityRepository;   // needed to clear a group's activity on delete

    @Transactional
    public GroupResponse createGroup(CreateGroupRequest request) {
        User creator = currentUserService.getCurrentUser();

        Group group = Group.builder().name(request.getName()).createdBy(creator).build();
        group = groupRepository.save(group);

        Set<Long> addedUserIds = new LinkedHashSet<>();
        addMemberInMemory(group, creator, addedUserIds);

        if (request.getMemberIdentifiers() != null) {
            for (String identifier : request.getMemberIdentifiers()) {
                User user = findUserByIdentifier(identifier);
                addMemberInMemory(group, user, addedUserIds);
            }
        }

        // group is reassigned above (group = groupRepository.save(group)), so it
        // isn't effectively final — snapshot it before the lambda captures it.
        final Group savedGroup = group;
        recordActivitySafely(() -> activityService.record(savedGroup, creator, ActivityType.GROUP_CREATED,
                creator.getName() + " created the group \"" + savedGroup.getName() + "\""));

        return toResponse(group);
    }

    public List<GroupResponse> getMyGroups() {
        User me = currentUserService.getCurrentUser();
        return groupRepository.findAllByMemberUserId(me.getId()).stream().map(this::toResponse).toList();
    }

    public GroupResponse getGroup(Long groupId) {
        requireMember(groupId, currentUserService.getCurrentUser().getId());
        return toResponse(groupRepository.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("Group not found")));
    }

    @Transactional
    public GroupResponse addMember(Long groupId, AddMemberRequest request) {
        User currentUser = currentUserService.getCurrentUser();
        requireMember(groupId, currentUser.getId());

        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("Group not found"));
        User user = findUserByIdentifier(request.getIdentifier());

        if (groupMemberRepository.existsByGroupIdAndUserId(groupId, user.getId())) {
            throw new IllegalStateException("User is already a member of this group");
        }

        GroupMember member = groupMemberRepository.save(GroupMember.builder().group(group).user(user).build());
        group.getMembers().add(member);

        recordActivitySafely(() -> activityService.record(group, currentUser, ActivityType.MEMBER_ADDED,
                currentUser.getName() + " added " + user.getName() + " to the group"));

        return toResponse(group);
    }

    /**
     * NEW — removes a member from a group. Only the creator can do this, and
     * the creator can never remove themself this way (they'd have to delete
     * the whole group instead). Past expenses/splits involving the removed
     * user are untouched — they reference the User directly, not
     * GroupMember — so history and balances stay intact; the person just
     * loses access to the group going forward.
     */
    @Transactional
    public GroupResponse removeMember(Long groupId, Long userId) {
        User currentUser = currentUserService.getCurrentUser();

        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("Group not found"));

        if (!group.getCreatedBy().getId().equals(currentUser.getId())) {
            throw new AccessDeniedException("Only the person who created this group can remove members");
        }
        if (group.getCreatedBy().getId().equals(userId)) {
            throw new IllegalArgumentException("The group's creator can't be removed");
        }

        GroupMember member = groupMemberRepository.findByGroupIdAndUserId(groupId, userId)
                .orElseThrow(() -> new IllegalArgumentException("That user isn't a member of this group"));
        User removedUser = member.getUser();

        group.getMembers().removeIf(m -> m.getUser().getId().equals(userId));
        groupMemberRepository.delete(member);

        recordActivitySafely(() -> activityService.record(group, currentUser, ActivityType.MEMBER_REMOVED,
                currentUser.getName() + " removed " + removedUser.getName() + " from the group"));

        return toResponse(group);
    }

    /**
     * NEW — see the matching note in ExpenseService. Logging to the
     * activity feed must never be able to fail the real mutation it's
     * describing; this is what was turning edit/delete-expense and
     * remove-member into bare "Internal Server Error" responses even though
     * the underlying change had already gone through.
     */
    private void recordActivitySafely(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("Failed to record activity (the actual operation still succeeded): {}", e.getMessage());
        }
    }

    /**
     * NEW — renames a group. Only the person who created it can do this (the
     * "add more members" half of editing a group reuses the existing
     * addMember endpoint above — no change needed there).
     */
    @Transactional
    public GroupResponse updateGroup(Long groupId, UpdateGroupRequest request) {
        User currentUser = currentUserService.getCurrentUser();

        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("Group not found"));

        if (!group.getCreatedBy().getId().equals(currentUser.getId())) {
            throw new AccessDeniedException("Only the person who created this group can edit it");
        }

        group.setName(request.getName().trim());
        group = groupRepository.save(group);

        return toResponse(group);
    }

    /**
     * Deletes a group and everything inside it. Only the person who
     * created the group can do this.
     *
     * Order matters: expenses and activity rows point at the group via a
     * foreign key, and Group only cascades to its GroupMember rows (that's the
     * only @OneToMany it maps). So we clear expenses and activity by hand
     * first, then delete the group — which cascades to group_members on the
     * way out. Deleting the Expense entities (rather than issuing a bulk
     * delete query) is deliberate: it lets Expense's own cascade clean up the
     * splits table too.
     */
    @Transactional
    public void deleteGroup(Long groupId) {
        User currentUser = currentUserService.getCurrentUser();

        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("Group not found"));

        if (!group.getCreatedBy().getId().equals(currentUser.getId())) {
            throw new AccessDeniedException("Only the person who created this group can delete it");
        }

        expenseRepository.deleteAll(
                expenseRepository.findByGroupIdOrderByExpenseDateDescCreatedAtDesc(groupId));
        activityRepository.deleteAll(
                activityRepository.findByGroupIdOrderByCreatedAtDesc(groupId));

        groupRepository.delete(group);
    }

    public void requireMember(Long groupId, Long userId) {
        if (!groupMemberRepository.existsByGroupIdAndUserId(groupId, userId)) {
            throw new AccessDeniedException("You are not a member of this group");
        }
    }

    private void addMemberInMemory(Group group, User user, Set<Long> addedUserIds) {
        if (addedUserIds.add(user.getId())) {
            GroupMember member = groupMemberRepository.save(GroupMember.builder().group(group).user(user).build());
            group.getMembers().add(member);
        }
    }

    private User findUserByIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("Email or phone number is required");
        }
        String value = identifier.trim();
        Optional<User> user = value.contains("@")
                ? userRepository.findByEmail(value)
                : userRepository.findByPhoneNumber(value);
        return user.orElseThrow(() -> new IllegalArgumentException("No registered user with email/phone: " + value));
    }

    private GroupResponse toResponse(Group group) {
        List<MemberResponse> members = group.getMembers().stream()
                .map(m -> MemberResponse.builder()
                        .userId(m.getUser().getId())
                        .name(m.getUser().getName())
                        .email(m.getUser().getEmail())
                        .build())
                .toList();
        return GroupResponse.builder()
                .id(group.getId())
                .name(group.getName())
                .createdByUserId(group.getCreatedBy().getId())
                .members(members)
                .build();
    }
}
