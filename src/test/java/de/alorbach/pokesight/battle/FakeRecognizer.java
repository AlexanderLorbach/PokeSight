package de.alorbach.pokesight.battle;

import de.alorbach.pokesight.ocr.TextRecognizer;
import de.alorbach.pokesight.ocr.Word;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Returns scripted words instead of running OCR. Lines are given as text; each word gets its own box. */
final class FakeRecognizer implements TextRecognizer {

    private static final int LINE_HEIGHT = 40;
    private static final int CHAR_WIDTH = 20;

    private List<Word> words = List.of();
    private final Deque<List<Word>> nextReads = new ArrayDeque<>();
    int reads;

    /** Each string is one text line, top to bottom. Returned by every read until changed. */
    void showLines(String... lines) {
        words = toWords(lines);
    }

    /** Returned by the next read only (e.g. for the second capture region), before falling back to {@link #showLines}. */
    void showOnceLines(String... lines) {
        nextReads.add(toWords(lines));
    }

    private static List<Word> toWords(String... lines) {
        List<Word> result = new ArrayList<>();
        for (int row = 0; row < lines.length; row++) {
            int left = 0;
            for (String token : lines[row].split(" ")) {
                if (!token.isEmpty()) {
                    int top = row * LINE_HEIGHT * 2;
                    result.add(new Word(token, left, top, left + token.length() * CHAR_WIDTH, top + LINE_HEIGHT));
                }
                left += (token.length() + 1) * CHAR_WIDTH;
            }
        }
        return result;
    }

    @Override
    public List<Word> read(BufferedImage image) {
        reads++;
        return nextReads.isEmpty() ? words : nextReads.poll();
    }

    @Override
    public void close() {
    }
}
