package com.currencyanalyzer.ai.springai;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

final class DeepSeekApiKeyPresent implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String apiKey = context.getEnvironment().getProperty("app.ai.deepseek.api-key", "");
        return !apiKey.isBlank();
    }
}
