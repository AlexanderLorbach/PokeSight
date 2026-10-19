package de.alorbach.pokesight.ui;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.stage.Window;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Supplier;

/** Shows what the last scan captured, so the selected areas can be checked; can save the images. */
final class CapturePreview {

    private CapturePreview() {
    }

    static void show(Window owner, Supplier<List<BufferedImage>> captures) {
        VBox images = new VBox();
        images.getStyleClass().add("preview-images");
        Runnable refresh = () -> {
            List<BufferedImage> current = captures.get();
            images.getChildren().setAll(current.isEmpty()
                    ? List.of(new Label("Nothing captured yet."))
                    : current.stream().map(image -> new ImageView(toFxImage(image))).toList());
        };
        refresh.run();

        Button reload = new Button("Refresh");
        reload.getStyleClass().addAll("ghost", "small");
        reload.setOnAction(event -> refresh.run());
        Button save = new Button("Save images…");
        save.getStyleClass().addAll("ghost", "small");
        save.setOnAction(event -> save(owner, captures.get()));

        BorderPane root = new BorderPane(new ScrollPane(images));
        root.setTop(TitleBar.of("Capture preview", "What the last scan saw", reload, save));
        Scene scene = new Scene(root, 720, 480);
        Theme.apply(scene);
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.setTitle("PokeSight - capture preview");
        Theme.decorate(stage);
        stage.setScene(scene);
        stage.show();
    }

    private static void save(Window owner, List<BufferedImage> images) {
        if (images.isEmpty()) {
            return;
        }
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Save captured images to");
        File directory = chooser.showDialog(owner);
        if (directory == null) {
            return;
        }
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        try {
            for (int i = 0; i < images.size(); i++) {
                ImageIO.write(images.get(i), "png", new File(directory, "pokesight-" + timestamp + "-" + (i + 1) + ".png"));
            }
        } catch (IOException e) {
            Theme.showError(owner, "Cannot save the images", e.getMessage());
        }
    }

    private static Image toFxImage(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] argb = image.getRGB(0, 0, width, height, null, 0, width);
        WritableImage result = new WritableImage(width, height);
        result.getPixelWriter().setPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), argb, 0, width);
        return result;
    }
}
