package com.vedant.hisaab.repository;

import com.vedant.hisaab.entity.Activity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ActivityRepository extends JpaRepository<Activity, Long> {
    List<Activity> findByGroupIdOrderByCreatedAtDesc(Long groupId);
}