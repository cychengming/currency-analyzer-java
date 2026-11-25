package com.currencyanalyzer;

import com.currencyanalyzer.ai.chat.AiAssistantProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.net.URI;
import java.util.function.Function;

@EnableScheduling
@EnableConfigurationProperties(AiAssistantProperties.class)
@SpringBootApplication
public class CurrencyAnalyzerApplication {

    public static void main(String[] args) {
        configureProxyFromEnvironment(System::getenv);
        SpringApplication.run(CurrencyAnalyzerApplication.class, args);
    }

    public static void configureProxyFromEnvironment(Function<String, String> env) {
        applyProxy("https", env, "HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy");
        applyProxy("http", env, "HTTP_PROXY", "http_proxy", "HTTPS_PROXY", "https_proxy");
        if (System.getProperty("http.nonProxyHosts") == null
            && (System.getProperty("https.proxyHost") != null || System.getProperty("http.proxyHost") != null)) {
            System.setProperty("http.nonProxyHosts", "localhost|127.0.0.1|::1|postgres");
        }
    }

    private static void applyProxy(String scheme, Function<String, String> env, String... envNames) {
        if (System.getProperty(scheme + ".proxyHost") != null) {
            return;
        }
        for (String name : envNames) {
            String raw = env.apply(name);
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                String normalized = raw.trim().contains("://") ? raw.trim() : "http://" + raw.trim();
                URI uri = URI.create(normalized);
                if (uri.getHost() != null && !uri.getHost().isBlank() && uri.getPort() > 0) {
                    System.setProperty(scheme + ".proxyHost", uri.getHost());
                    System.setProperty(scheme + ".proxyPort", String.valueOf(uri.getPort()));
                    return;
                }
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed proxy env values.
            }
        }
    }
}
