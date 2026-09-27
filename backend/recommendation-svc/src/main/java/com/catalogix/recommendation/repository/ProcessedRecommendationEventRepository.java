package com.catalogix.recommendation.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcessedRecommendationEventRepository extends JpaRepository<ProcessedRecommendationEvent, String> {
    @Modifying
    @Query(value = "insert into recommendation_processed_events(event_id) values (:eventId) on conflict (event_id) do nothing", nativeQuery = true)
    int insertIfAbsent(@Param("eventId") String eventId);
}
