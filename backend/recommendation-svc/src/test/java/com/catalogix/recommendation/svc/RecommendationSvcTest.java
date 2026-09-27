package com.catalogix.recommendation.svc;

import com.catalogix.recommendation.event.*;
import com.catalogix.recommendation.repository.*;
import org.junit.jupiter.api.*;
import org.mockito.*;
import java.util.*;
import static org.mockito.Mockito.*;

class RecommendationSvcTest {
    @Mock
    RecommendationRepository repo;
    @Mock
    ProcessedRecommendationEventRepository processed;
    RecommendationSvc svc;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        svc = new RecommendationSvc(repo, processed);
    }

    @Test
    void duplicateOrderEventDoesNothing() {
        OrderConfirmedEvent e = new OrderConfirmedEvent(10L, 5L, "user@example.com",
                List.of(new OrderItemEventData(1L, 1L, "A", 1, null, null)), new java.math.BigDecimal("1.00"),
                java.time.Instant.now());
        when(processed.insertIfAbsent("order.confirmed:10")).thenReturn(0);
        svc.ingest(e);
        verifyNoInteractions(repo);
    }

    @Test
    void eventCreatesBidirectionalRecommendations() {
        OrderConfirmedEvent e = new OrderConfirmedEvent(10L, 5L, "user@example.com",
                List.of(new OrderItemEventData(1L, 1L, "A", 1, null, null),
                        new OrderItemEventData(2L, 1L, "B", 1, null, null)),
                new java.math.BigDecimal("2.00"), java.time.Instant.now());
        when(processed.insertIfAbsent("order.confirmed:10")).thenReturn(1);
        svc.ingest(e);
        verify(repo, times(2)).save(any());
    }
}
