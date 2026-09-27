package com.catalogix.search.service;

import com.catalogix.search.event.ProductEvent;
import com.catalogix.search.model.ProductDocument;
import com.catalogix.search.repository.ProductDocumentRepository;
import org.junit.jupiter.api.*;
import org.mockito.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SearchServiceTest {
    @Mock ProductDocumentRepository repo; SearchService service;
    @BeforeEach void setUp(){MockitoAnnotations.openMocks(this);service=new SearchService(repo);}
    @Test void productEventCreatesDocument(){
        ProductEvent e=new ProductEvent(9L,"Mouse","Wireless",new BigDecimal("499.00"),"Accessories",21L,"/m.png","PUBLISHED",Instant.now(),false);
        when(repo.findById(9L)).thenReturn(Optional.empty()); when(repo.save(any())).thenAnswer(i->i.getArgument(0));
        service.apply(e);
        verify(repo).save(argThat(p->{ProductDocument d=(ProductDocument)p; return d.getId().equals(9L)&&d.getOwnerId().equals(21L)&&d.getModerationStatus().equals("PUBLISHED");}));
    }
    @Test void deletedEventRemovesDocument(){
        ProductEvent e=new ProductEvent(9L,null,null,null,null,null,null,null,Instant.now(),true);
        service.apply(e); verify(repo).deleteById(9L); verify(repo,never()).save(any());
    }
}
