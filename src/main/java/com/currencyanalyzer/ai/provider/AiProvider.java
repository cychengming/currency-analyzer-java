package com.currencyanalyzer.ai.provider;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public interface AiProvider {

    void stream(
        List<Message> messages,
        Consumer<String> onText,
        BooleanSupplier isCancelled
    );

    record Message(String role, String content) {
    }
}
