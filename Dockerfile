# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw mvnw
COPY pom.xml pom.xml
RUN chmod +x mvnw \
  && ./mvnw -B -ntp dependency:resolve

COPY src src
RUN ./mvnw -B -ntp -DskipTests package

FROM eclipse-temurin:25-jdk-noble AS model-fetch
ARG PREBAKE_EMBEDDING_MODEL=true
WORKDIR /models/embeddings
COPY <<'EOF' /tmp/Fetch.java
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

public class Fetch {
    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        for (int i = 0; i < args.length; i += 2) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(args[i])).GET().build();
            HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(Path.of(args[i + 1])));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException("HTTP " + response.statusCode() + " for " + args[i]);
            }
        }
    }
}
EOF
RUN if [ "$PREBAKE_EMBEDDING_MODEL" = "true" ]; then \
      java /tmp/Fetch.java \
        "https://huggingface.co/Xenova/paraphrase-multilingual-MiniLM-L12-v2/resolve/main/onnx/model_quantized.onnx" "model_quantized.onnx" \
        "https://huggingface.co/Xenova/paraphrase-multilingual-MiniLM-L12-v2/resolve/main/tokenizer.json" "tokenizer.json"; \
    fi

FROM eclipse-temurin:25-jre-noble
WORKDIR /app
RUN useradd --system --uid 10001 --create-home currency \
  && mkdir -p /app/models/embeddings
COPY --from=build /workspace/target/currency-analyzer-java-*.jar /app/app.jar
COPY --from=model-fetch /models/embeddings /app/models/embeddings
RUN chown -R currency:currency /app
USER currency
ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=55.0", "-XX:+ExitOnOutOfMemoryError", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/app.jar"]
