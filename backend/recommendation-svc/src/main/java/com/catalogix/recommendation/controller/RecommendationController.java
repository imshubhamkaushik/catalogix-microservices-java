package com.catalogix.recommendation.controller;

import com.catalogix.recommendation.svc.RecommendationSvc;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/recommendations")
public class RecommendationController {
  final RecommendationSvc svc;

  public RecommendationController(RecommendationSvc s) {
    svc = s;
  }

  @GetMapping("/products/{productId}")
  public List<Long> products(@PathVariable Long productId, @RequestParam(defaultValue = "6") int limit) {
    return svc.recommend(productId, limit);
  }
}
