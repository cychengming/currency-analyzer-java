package com.currencyanalyzer.pubsub;

import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.auth.http.HttpTransportFactory;
import com.google.auth.oauth2.GoogleCredentials;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;

public final class GoogleHttpSupport {

    private static final List<String> PUBSUB_SCOPES = List.of("https://www.googleapis.com/auth/pubsub");

    private GoogleHttpSupport() {
    }

    public static HttpTransportFactory createTransportFactory() {
        return createTransportFactory(System::getenv);
    }

    static HttpTransportFactory createTransportFactory(Function<String, String> env) {
        Proxy proxy = resolveProxy(env);
        if (proxy == null) {
            return null;
        }
        return () -> new NetHttpTransport.Builder().setProxy(proxy).build();
    }

    public static RestClient buildRestClient(RestClient.Builder builder) {
        return buildRestClient(builder, System::getenv);
    }

    static RestClient buildRestClient(RestClient.Builder builder, Function<String, String> env) {
        HttpClient.Builder httpClientBuilder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL);
        InetSocketAddress proxyAddress = resolveProxyAddress(env);
        if (proxyAddress != null) {
            httpClientBuilder.proxy(ProxySelector.of(proxyAddress));
        }
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClientBuilder.build());
        requestFactory.setReadTimeout(Duration.ofSeconds(15));
        return builder.requestFactory(requestFactory).build();
    }

    static GoogleCredentials loadPubSubCredentials(String credentialsPath) throws IOException {
        HttpTransportFactory transportFactory = createTransportFactory();
        GoogleCredentials base;
        if (credentialsPath != null && !credentialsPath.isBlank()) {
            Path path = Path.of(credentialsPath.trim());
            try (InputStream input = Files.newInputStream(path)) {
                base = transportFactory == null
                    ? GoogleCredentials.fromStream(input)
                    : GoogleCredentials.fromStream(input, transportFactory);
            }
        } else {
            base = transportFactory == null
                ? GoogleCredentials.getApplicationDefault()
                : GoogleCredentials.getApplicationDefault(transportFactory);
        }
        return base.createScoped(PUBSUB_SCOPES);
    }

    static Proxy resolveProxy(Function<String, String> env) {
        InetSocketAddress address = resolveProxyAddress(env);
        return address == null ? null : new Proxy(Proxy.Type.HTTP, address);
    }

    static InetSocketAddress resolveProxyAddress(Function<String, String> env) {
        String proxyUrl = firstNonBlank(
            env.apply("HTTPS_PROXY"),
            env.apply("https_proxy"),
            env.apply("HTTP_PROXY"),
            env.apply("http_proxy")
        );
        if (proxyUrl == null) {
            return null;
        }
        String normalized = proxyUrl.contains("://") ? proxyUrl : "http://" + proxyUrl;
        try {
            URI uri = URI.create(normalized);
            String host = uri.getHost();
            int port = uri.getPort();
            if (host == null || host.isBlank() || port <= 0 || port > 65535) {
                return null;
            }
            return new InetSocketAddress(host, port);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
