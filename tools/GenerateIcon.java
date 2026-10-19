import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws the app icon (a Poké Ball whose button is a yellow lens) in all Windows icon sizes.
 *
 * <p>Usage: {@code java tools/GenerateIcon.java <pngDir> <icoFile>}
 * <br>Example: {@code java tools/GenerateIcon.java src/main/resources/ui/icons packaging/PokeSight.ico}
 *
 * <p>Writes {@code pokesight-<size>.png} for the window/taskbar icon and one .ico (PNG-compressed entries,
 * supported since Windows Vista) for the installer and the desktop shortcut.
 */
public class GenerateIcon {

    private static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256};
    private static final Color RED = new Color(0xE3350D);
    private static final Color RED_DARK = new Color(0xB3200A);
    private static final Color WHITE = new Color(0xF2F3F5);
    private static final Color OUTLINE = new Color(0x121419);
    private static final Color LENS = new Color(0xFFCB05);
    private static final Color LENS_DARK = new Color(0xE0A800);

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("Usage: java tools/GenerateIcon.java <pngDir> <icoFile>");
            System.exit(1);
        }
        Path pngDir = Path.of(args[0]);
        Path icoFile = Path.of(args[1]);
        Files.createDirectories(pngDir);
        Files.createDirectories(icoFile.toAbsolutePath().getParent());

        List<byte[]> pngs = new ArrayList<>();
        for (int size : SIZES) {
            BufferedImage icon = draw(size);
            ImageIO.write(icon, "png", pngDir.resolve("pokesight-" + size + ".png").toFile());
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(icon, "png", png);
            pngs.add(png.toByteArray());
        }
        Files.write(icoFile, ico(pngs));
        System.out.println("Icons written to " + pngDir.toAbsolutePath() + " and " + icoFile.toAbsolutePath());
    }

    /** Drawn at 4x and scaled down, which keeps small sizes smooth. */
    private static BufferedImage draw(int size) {
        int big = size * 4;
        BufferedImage image = new BufferedImage(big, big, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        double margin = big * 0.04;
        double diameter = big - 2 * margin;
        double line = Math.max(4, big * 0.075);
        Ellipse2D ball = new Ellipse2D.Double(margin, margin, diameter, diameter);

        g.setClip(ball);
        g.setPaint(new GradientPaint(0, 0, RED, 0, big / 2f, RED_DARK));
        g.fill(new Rectangle2D.Double(0, 0, big, big / 2.0));
        g.setColor(WHITE);
        g.fill(new Rectangle2D.Double(0, big / 2.0, big, big / 2.0));
        g.setColor(OUTLINE);
        g.fill(new Rectangle2D.Double(0, big / 2.0 - line / 2, big, line));
        g.setClip(null);
        g.setStroke(new BasicStroke((float) line));
        g.draw(new Ellipse2D.Double(margin + line / 2, margin + line / 2, diameter - line, diameter - line));

        // The button is a lens: dark ring, yellow glass, small highlight.
        double ring = big * 0.36;
        double lens = big * 0.22;
        double centre = big / 2.0;
        g.setColor(OUTLINE);
        g.fill(new Ellipse2D.Double(centre - ring / 2, centre - ring / 2, ring, ring));
        g.setPaint(new GradientPaint((float) (centre - lens / 2), (float) (centre - lens / 2), LENS,
                (float) (centre + lens / 2), (float) (centre + lens / 2), LENS_DARK));
        g.fill(new Ellipse2D.Double(centre - lens / 2, centre - lens / 2, lens, lens));
        if (size >= 32) {
            double glint = lens * 0.32;
            g.setColor(new Color(255, 255, 255, 200));
            g.fill(new Ellipse2D.Double(centre - lens * 0.28, centre - lens * 0.28, glint, glint));
        }
        g.dispose();

        BufferedImage result = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D small = result.createGraphics();
        small.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        small.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        small.drawImage(image.getScaledInstance(size, size, java.awt.Image.SCALE_AREA_AVERAGING), 0, 0, null);
        small.dispose();
        return result;
    }

    /** ICO container: 6-byte header, one 16-byte directory entry per image, then the PNG data. */
    private static byte[] ico(List<byte[]> pngs) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer header = ByteBuffer.allocate(6 + 16 * pngs.size()).order(ByteOrder.LITTLE_ENDIAN);
        header.putShort((short) 0).putShort((short) 1).putShort((short) pngs.size());
        int offset = 6 + 16 * pngs.size();
        for (int i = 0; i < pngs.size(); i++) {
            int size = SIZES[i];
            header.put((byte) (size >= 256 ? 0 : size)).put((byte) (size >= 256 ? 0 : size)) // 0 means 256
                    .put((byte) 0).put((byte) 0)                // no palette, reserved
                    .putShort((short) 1).putShort((short) 32)   // colour planes, bits per pixel
                    .putInt(pngs.get(i).length).putInt(offset);
            offset += pngs.get(i).length;
        }
        out.write(header.array());
        for (byte[] png : pngs) {
            out.write(png);
        }
        return out.toByteArray();
    }
}
