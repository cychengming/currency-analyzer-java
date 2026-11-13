package com.currencyanalyzer.ai.chat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class AiStatusController {

    private final boolean enabled;

    public AiStatusController(@Value("${app.ai.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    @GetMapping("/api/ai/status")
    public Map<String, Boolean> status() {
        return Map.of("enabled", enabled);
    }
}
