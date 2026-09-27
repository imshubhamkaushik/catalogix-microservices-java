package com.catalogix.feature.svc;

import com.catalogix.feature.model.FeatureFlag;
import com.catalogix.feature.repository.FeatureRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.TreeMap;

@Service
public class FeatureSvc {

    private final FeatureRepository repo;

    public FeatureSvc(FeatureRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public Map<String, Boolean> all() {
        return allInternal();
    }

    @Transactional
    public Map<String, Boolean> set(String name, boolean enabled) {
        FeatureFlag flag = repo.findByName(name)
                .orElseGet(() -> new FeatureFlag(name, enabled));
        flag.setEnabled(enabled);
        repo.save(flag);
        return allInternal();
    }

    private Map<String, Boolean> allInternal() {
        Map<String, Boolean> result = new TreeMap<>();
        repo.findAll().forEach(flag -> result.put(flag.getName(), flag.isEnabled()));
        return result;
    }
}
