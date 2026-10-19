package de.alorbach.pokesight.ocr;

import java.awt.image.BufferedImage;
import java.util.List;

/** Reads the words in an image (Adapter around the OCR engine). */
public interface TextRecognizer extends AutoCloseable {

    /** @param image grayscale, as produced by {@link ImagePreprocessor} */
    List<Word> read(BufferedImage image);

    @Override
    void close();
}
