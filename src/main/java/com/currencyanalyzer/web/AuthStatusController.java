package com.currencyanalyzer.web;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthStatusController {

    private final boolean registrationEnabled;

    public AuthStatusController(@Value("${app.auth.registration-enabled:true}") boolean registrationEnabled) {
        this.registrationEnabled = registrationEnabled;
    }

    @GetMapping("/status")
    public Map<String, Object> status(HttpSession session) {
        Object username = session.getAttribute("username");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("logged_in", username != null);
        response.put("username", username);
        response.put("registration_enabled", registrationEnabled);
        return response;
    }
}
