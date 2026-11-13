package com.currencyanalyzer.ai.chat;

public class AiResponseInProgressException extends RuntimeException {

    public AiResponseInProgressException() {
        super("A response is already in progress for this conversation");
    }
}
