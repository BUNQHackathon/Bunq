package com.bunq.javabackend.service.ai.bedrock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class BedrockStreamingServiceBufferTest {

    @Mock
    private software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient asyncClient;

    private BedrockStreamingService service;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new BedrockStreamingService(asyncClient, om, 30);
    }

    @Test
    void twoEventsInOneChunk_bothParsed() {
        String event1 = """
                {"type":"content_block_delta","delta":{"type":"text_delta","text":"hello"}}""";
        String event2 = """
                {"type":"content_block_delta","delta":{"type":"text_delta","text":" world"}}""";

        StringBuilder buffer = new StringBuilder(event1 + event2);
        Sinks.Many<BedrockStreamingService.StreamingDelta> sink = Sinks.many().unicast().onBackpressureBuffer();
        List<BedrockStreamingService.StreamingDelta> emitted = new ArrayList<>();
        sink.asFlux().subscribe(emitted::add);
        AtomicBoolean emittedFlag = new AtomicBoolean(false);

        // First parse: consumes event1, advances buffer past it
        int consumed1 = service.tryParseOne(buffer, sink, emittedFlag);
        assertTrue(consumed1 > 0, "First event should be consumed");
        buffer.delete(0, consumed1);

        // Second parse: consumes event2 from the remaining buffer
        int consumed2 = service.tryParseOne(buffer, sink, emittedFlag);
        assertTrue(consumed2 > 0, "Second event should be consumed");
        buffer.delete(0, consumed2);

        assertTrue(buffer.isEmpty(), "Buffer should be fully consumed");
        assertEquals(2, emitted.size(), "Both text deltas must be emitted");
        assertEquals("hello", emitted.get(0).text());
        assertEquals(" world", emitted.get(1).text());
    }

    @Test
    void incompleteJson_returnsZero() {
        StringBuilder buffer = new StringBuilder("{\"type\":\"content_block_delta\",\"delta\":{");
        Sinks.Many<BedrockStreamingService.StreamingDelta> sink = Sinks.many().unicast().onBackpressureBuffer();
        AtomicBoolean emittedFlag = new AtomicBoolean(false);

        int consumed = service.tryParseOne(buffer, sink, emittedFlag);

        assertEquals(0, consumed, "Incomplete JSON must return 0 (wait for more data)");
        assertFalse(buffer.isEmpty(), "Buffer must not be cleared on incomplete input");
    }
}
