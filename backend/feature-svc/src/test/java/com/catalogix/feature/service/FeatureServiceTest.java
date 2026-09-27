package com.catalogix.feature.service;

import com.catalogix.feature.model.FeatureFlag; import com.catalogix.feature.repository.FeatureRepository;
import org.junit.jupiter.api.*; import org.mockito.*; import java.util.*;
import static org.junit.jupiter.api.Assertions.*; import static org.mockito.ArgumentMatchers.any; import static org.mockito.Mockito.*;

class FeatureServiceTest {
 @Mock FeatureRepository repo; FeatureService service;
 @BeforeEach void setUp(){MockitoAnnotations.openMocks(this);service=new FeatureService(repo);}
 @Test void allReturnsSortedFeatureMap(){FeatureFlag a=new FeatureFlag("seller_dashboard",true);FeatureFlag b=new FeatureFlag("product_search",false);when(repo.findAll()).thenReturn(List.of(a,b));Map<String,Boolean> out=service.all();assertEquals(List.of("product_search","seller_dashboard"),new ArrayList<>(out.keySet()));assertFalse(out.get("product_search"));}
 @Test void setCreatesOrUpdatesFlag(){when(repo.findByName("new_feature")).thenReturn(Optional.empty());when(repo.save(any())).thenAnswer(i->i.getArgument(0));when(repo.findAll()).thenReturn(List.of(new FeatureFlag("new_feature",true)));assertTrue(service.set("new_feature",true).get("new_feature"));verify(repo).save(any(FeatureFlag.class));}
}
