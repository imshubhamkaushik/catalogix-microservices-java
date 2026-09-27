package com.catalogix.recommendation.repository;

import jakarta.persistence.*;

@Entity
@Table(name = "recommendation_processed_events")
public class ProcessedRecommendationEvent {
    @Id
    @Column(name = "event_id", length = 160)
    private String eventId;

    protected ProcessedRecommendationEvent() {
    }

    public ProcessedRecommendationEvent(String eventId) {
        this.eventId = eventId;
    }

    public String getEventId() {
        return eventId;
    }
}
