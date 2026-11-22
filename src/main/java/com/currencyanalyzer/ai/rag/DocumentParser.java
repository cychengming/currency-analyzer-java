package com.currencyanalyzer.ai.rag;

import java.util.List;

public interface DocumentParser {

    boolean supports(String filename);

    String inferContentType(String filename);

    ParsedDocument parse(String filename, byte[] rawBytes, String titleOverride);

    record ParsedDocument(String title, List<ParsedChunk> chunks) {
    }

    record ParsedChunk(int index, String heading, String content) {
    }
}
