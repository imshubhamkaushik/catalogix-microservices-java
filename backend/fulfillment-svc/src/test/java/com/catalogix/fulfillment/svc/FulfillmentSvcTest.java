package com.catalogix.fulfillment.svc;

import com.catalogix.fulfillment.model.*;
import com.catalogix.fulfillment.repository.ShipmentRepository;
import org.junit.jupiter.api.*;
import org.mockito.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FulfillmentSvcTest {
    @Mock
    ShipmentRepository repo;
    FulfillmentSvc svc;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        svc = new FulfillmentSvc(repo);
    }

    @Test
    void sellerCanAdvanceOnlyOwnShipment() {
        Shipment s = new Shipment();
        s.setSellerId(77L);
        s.setUserId(10L);
        s.setStatus(ShipmentStatus.CREATED);
        when(repo.findById(1L)).thenReturn(Optional.of(s));
        when(repo.save(s)).thenReturn(s);
        assertEquals(ShipmentStatus.PACKED, svc.update(1L, 77L, "SELLER", ShipmentStatus.PACKED).status());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> svc.update(1L, 88L, "SELLER", ShipmentStatus.SHIPPED));
    }

    @Test
    void invalidTransitionIsRejected() {
        Shipment s = new Shipment();
        s.setSellerId(77L);
        s.setUserId(10L);
        s.setStatus(ShipmentStatus.CREATED);
        when(repo.findById(1L)).thenReturn(Optional.of(s));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> svc.update(1L, 77L, "SELLER", ShipmentStatus.DELIVERED));
    }
}
