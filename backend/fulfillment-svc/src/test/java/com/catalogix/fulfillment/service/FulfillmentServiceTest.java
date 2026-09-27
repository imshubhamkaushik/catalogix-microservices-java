package com.catalogix.fulfillment.service;

import com.catalogix.fulfillment.model.*;
import com.catalogix.fulfillment.repository.ShipmentRepository;
import org.junit.jupiter.api.*;
import org.mockito.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FulfillmentServiceTest {
    @Mock ShipmentRepository repo; FulfillmentService service;
    @BeforeEach void setUp(){ MockitoAnnotations.openMocks(this); service=new FulfillmentService(repo); }

    @Test void sellerCanAdvanceOnlyOwnShipment(){
        Shipment s=new Shipment(); s.setSellerId(77L); s.setUserId(10L); s.setStatus(ShipmentStatus.CREATED);
        when(repo.findById(1L)).thenReturn(Optional.of(s)); when(repo.save(s)).thenReturn(s);
        assertEquals(ShipmentStatus.PACKED,service.update(1L,77L,"SELLER",ShipmentStatus.PACKED).status());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.update(1L,88L,"SELLER",ShipmentStatus.SHIPPED));
    }

    @Test void invalidTransitionIsRejected(){
        Shipment s=new Shipment(); s.setSellerId(77L); s.setUserId(10L); s.setStatus(ShipmentStatus.CREATED);
        when(repo.findById(1L)).thenReturn(Optional.of(s));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.update(1L,77L,"SELLER",ShipmentStatus.DELIVERED));
    }
}
