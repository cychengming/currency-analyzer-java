package com.currencyanalyzer.web;

import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthStatusController {

    @GetMapping("/status")
    public Map<String, Object> status(HttpSession session) {
        Object username = session.getAttribute("username");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("logged_in", username != null);
        response.put("username", username);
        return response;
    }
}
