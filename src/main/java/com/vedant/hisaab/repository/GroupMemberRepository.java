package com.vedant.hisaab.repository;

import com.vedant.hisaab.entity.GroupMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface GroupMemberRepository extends JpaRepository<GroupMember, Long> {
    boolean existsByGroupIdAndUserId(Long groupId, Long userId);

    // NEW — needed to remove a specific member from a group
    Optional<GroupMember> findByGroupIdAndUserId(Long groupId, Long userId);
}
