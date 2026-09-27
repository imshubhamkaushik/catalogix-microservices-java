package com.catalogix.recommendation.service;

import com.catalogix.recommendation.event.*;
import com.catalogix.recommendation.repository.*;
import org.junit.jupiter.api.*;
import org.mockito.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecommendationServiceTest {
    @Mock RecommendationRepository repo; @Mock ProcessedRecommendationEventRepository processed; RecommendationService service;
    @BeforeEach void setUp(){MockitoAnnotations.openMocks(this);service=new RecommendationService(repo,processed);}
    @Test void duplicateOrderEventDoesNothing(){OrderConfirmedEvent e=new OrderConfirmedEvent(10L,5L,"user@example.com",List.of(new OrderItemEventData(1L,1L,"A",1,null,null)),new java.math.BigDecimal("1.00"),java.time.Instant.now()); when(processed.insertIfAbsent("order.confirmed:10")).thenReturn(0); service.ingest(e); verifyNoInteractions(repo);}
    @Test void eventCreatesBidirectionalRecommendations(){OrderConfirmedEvent e=new OrderConfirmedEvent(10L,5L,"user@example.com",List.of(new OrderItemEventData(1L,1L,"A",1,null,null),new OrderItemEventData(2L,1L,"B",1,null,null)),new java.math.BigDecimal("2.00"),java.time.Instant.now()); when(processed.insertIfAbsent("order.confirmed:10")).thenReturn(1); service.ingest(e); verify(repo,times(2)).save(any());}
}
