package com.vedant.hisaab.repository;

import com.vedant.hisaab.entity.Group;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GroupRepository extends JpaRepository<Group, Long> {

    @Query("select g from Group g join g.members m where m.user.id = :userId")
    List<Group> findAllByMemberUserId(@Param("userId") Long userId);
}