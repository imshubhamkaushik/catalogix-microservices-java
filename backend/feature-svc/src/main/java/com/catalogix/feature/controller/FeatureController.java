package com.catalogix.feature.controller;

import com.catalogix.feature.dto.FeatureRequest;
import com.catalogix.feature.svc.FeatureSvc;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/features")
public class FeatureController {
  final FeatureSvc svc;

  public FeatureController(FeatureSvc s) {
    svc = s;
  }

  @GetMapping
  public Map<String, Boolean> all() {
    return svc.all();
  }

  @PutMapping("/{name}")
  public Map<String, Boolean> set(@PathVariable String name, @RequestAttribute String userRole,
      @Valid @RequestBody FeatureRequest req) {
    if (!"ADMIN".equalsIgnoreCase(userRole))
      throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,
          "Admin role required");
    return svc.set(name, req.enabled());
  }
}
