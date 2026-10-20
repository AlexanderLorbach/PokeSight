package de.alorbach.pokesight.ocr;

import de.alorbach.pokesight.ruleset.Language;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.tesseract.ResultIterator;
import org.bytedeco.tesseract.TessBaseAPI;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.bytedeco.tesseract.global.tesseract.PSM_AUTO;
import static org.bytedeco.tesseract.global.tesseract.RIL_WORD;
import static org.bytedeco.tesseract.global.tesseract.TessDeleteText;

/**
 * OCR with Tesseract. The language model is bundled under {@code /tessdata} and copied to a
 * temporary directory because Tesseract only loads models from files. Not thread-safe.
 */
public final class TesseractTextRecognizer implements TextRecognizer {

    private static final System.Logger LOG = System.getLogger(TesseractTextRecognizer.class.getName());

    private final TessBaseAPI api = new TessBaseAPI();
    private final Path dataDirectory;
    /** Freeing the native engine twice would crash the JVM. */
    private boolean closed;

    public TesseractTextRecognizer(Language language) throws IOException {
        String model = switch (language) {
            case EN -> "eng";
            case DE -> "deu";
        };
        try {
            dataDirectory = Files.createTempDirectory("pokesight-tessdata");
        } catch (IOException e) {
            api.deallocate();
            throw e;
        }
        try (InputStream in = TesseractTextRecognizer.class.getResourceAsStream("/tessdata/" + model + ".traineddata")) {
            if (in == null) {
                throw new IOException("OCR model " + model + ".traineddata is missing from the application");
            }
            Files.copy(in, dataDirectory.resolve(model + ".traineddata"));
            if (api.Init(dataDirectory.toString(), model) != 0) {
                throw new IOException("Tesseract could not load the OCR model " + model);
            }
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }
        // Automatic layout analysis copes with one name box as well as a whole screen. Sparse-text mode found
        // nothing on rendered name boxes, and a character whitelist made Tesseract drop the spaces between words.
        api.SetPageSegMode(PSM_AUTO);
        api.SetVariable("user_defined_dpi", "300");
        // Tesseract's diagnostics ("Empty page!!" on every blank read) go to this file instead of the console.
        api.SetVariable("debug_file", System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null");
    }

    @Override
    public List<Word> read(BufferedImage image) {
        if (closed) {
            throw new IllegalStateException("The OCR engine is closed");
        }
        BufferedImage gray = toGray(image);
        byte[] pixels = ((DataBufferByte) gray.getRaster().getDataBuffer()).getData();
        api.SetImage(pixels, gray.getWidth(), gray.getHeight(), 1, gray.getWidth());
        try {
            if (api.Recognize(null) != 0) {
                throw new IllegalStateException("Tesseract failed to recognize the image");
            }
            ResultIterator iterator = api.GetIterator();
            if (iterator == null) {
                return List.of();
            }
            List<Word> words = new ArrayList<>();
            try {
                int[] left = new int[1];
                int[] top = new int[1];
                int[] right = new int[1];
                int[] bottom = new int[1];
                do {
                    BytePointer text = iterator.GetUTF8Text(RIL_WORD);
                    if (text == null) {
                        continue;
                    }
                    try {
                        iterator.BoundingBox(RIL_WORD, left, top, right, bottom);
                        String value = text.getString(StandardCharsets.UTF_8).strip();
                        if (!value.isEmpty()) {
                            words.add(new Word(value, left[0], top[0], right[0], bottom[0]));
                        }
                    } finally {
                        TessDeleteText(text);
                    }
                } while (iterator.Next(RIL_WORD));
            } finally {
                iterator.deallocate();
            }
            return words;
        } finally {
            api.Clear();
        }
    }

    private static BufferedImage toGray(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_BYTE_GRAY) {
            return image;
        }
        BufferedImage gray = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = gray.createGraphics();
        graphics.drawImage(image, 0, 0, null);
        graphics.dispose();
        return gray;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        api.End();
        api.deallocate();
        // A temporary copy that cannot be deleted (e.g. locked by a virus scanner) must not break closing a session.
        try (var files = Files.list(dataDirectory)) {
            for (Path file : files.toList()) {
                Files.deleteIfExists(file);
            }
            Files.deleteIfExists(dataDirectory);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot delete the temporary OCR model in " + dataDirectory, e);
        }
    }
}
