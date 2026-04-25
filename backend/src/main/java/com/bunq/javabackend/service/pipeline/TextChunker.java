package com.bunq.javabackend.service.pipeline;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure utility for splitting long regulation text into overlapping chunks
 * for parallel Bedrock extraction. ~10K input tokens per chunk.
 */
public final class TextChunker {

    private static final int TARGET = 40_000;
    private static final int OVERLAP = 2_000;
    private static final int MIN_CHUNK = 5_000;
    private static final int WINDOW = 2_000;

    private TextChunker() {}

    public static List<String> chunk(String text) {
        if (text == null || text.length() <= TARGET) {
            return List.of(text == null ? "" : text);
        }

        List<String> chunks = new ArrayList<>();
        int start = 0;
        int len = text.length();

        while (start < len) {
            int targetEnd = start + TARGET;
            if (targetEnd >= len) {
                chunks.add(text.substring(start));
                break;
            }

            int windowStart = Math.max(start + 1, targetEnd - WINDOW);
            int windowEnd = Math.min(len, targetEnd + WINDOW);

            int boundary = findBoundary(text, windowStart, windowEnd, targetEnd);
            String chunk = text.substring(start, boundary);

            // If only a tiny remainder is left, fold it into this chunk and stop.
            if (len - boundary < MIN_CHUNK) {
                chunks.add(text.substring(start));
                break;
            }

            chunks.add(chunk);
            start = Math.max(boundary - OVERLAP, start + 1);
        }

        return chunks;
    }

    private static int findBoundary(String text, int windowStart, int windowEnd, int targetEnd) {
        int p = lastIndexInWindow(text, "\n\n", windowStart, windowEnd, targetEnd);
        if (p >= 0) return p + 2;
        p = lastIndexInWindow(text, "\n", windowStart, windowEnd, targetEnd);
        if (p >= 0) return p + 1;
        p = lastIndexInWindow(text, ". ", windowStart, windowEnd, targetEnd);
        if (p >= 0) return p + 2;
        return targetEnd;
    }

    private static int lastIndexInWindow(String text, String needle, int windowStart, int windowEnd, int targetEnd) {
        // Prefer boundary closest to targetEnd; search from windowEnd back.
        int from = Math.min(windowEnd, text.length() - needle.length());
        int idx = text.lastIndexOf(needle, from);
        if (idx >= windowStart && idx <= windowEnd) return idx;
        return -1;
    }
}
