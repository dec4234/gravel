package net.dec4234.gravel.mcp;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;

/**
 * Makes the game screenshot ready to send: scales it down and encodes it as PNG or JPEG. Also compares a screenshot
 * with a baseline image, for screenshot tests of rendering mods.
 */
final class Screenshots {
	/** Default largest width. Big images cost more to send and to read, with little more detail. */
	static final int DEFAULT_MAX_WIDTH = 1280;
	/** Default largest difference in one color channel (0-255) that does not count as a changed pixel. */
	static final int DEFAULT_TOLERANCE = 8;
	private static final float JPEG_QUALITY = 0.85f;
	/** Brightness of the unchanged pixels in the diff image, so the changed pixels are easy to see. */
	private static final float DIFF_BACKGROUND = 0.3f;

	/** An encoded image ready for MCP image content. */
	record Image(String base64, String mimeType, int width, int height) {
	}

	/**
	 * The result of a compare. score is the mean difference of all channels of all pixels, as a percent (0 means
	 * the same, 100 means black against white). changedPixels counts the pixels with a channel difference more than
	 * the tolerance. The box (minX, minY to maxX, maxY, in screenshot pixels) holds all changed pixels, or is -1 if
	 * there are none.
	 */
	record Comparison(double score, long changedPixels, long totalPixels, int maxDifference,
					  int minX, int minY, int maxX, int maxY, boolean baselineScaled,
					  int baselineWidth, int baselineHeight, BufferedImage diff) {
		double changedPercent() {
			return totalPixels == 0 ? 0 : changedPixels * 100.0 / totalPixels;
		}
	}

	private Screenshots() {
	}

	/** Decodes a base64 PNG. */
	static BufferedImage decode(String pngBase64) throws IOException {
		BufferedImage image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(pngBase64)));
		if (image == null) {
			throw new IOException("The game sent an image that is not a PNG");
		}
		return image;
	}

	/** Reads a baseline image file (PNG, JPEG or another format that ImageIO knows). */
	static BufferedImage read(Path file) throws IOException {
		if (!Files.isRegularFile(file)) {
			throw new IllegalArgumentException("No baseline image at " + file
					+ ". Take a screenshot with save_to to make one.");
		}
		BufferedImage image = ImageIO.read(file.toFile());
		if (image == null) {
			throw new IllegalArgumentException(file + " is not an image that can be read (use PNG).");
		}
		return image;
	}

	/** Writes the PNG bytes to the file and makes the parent folders. */
	static void save(String pngBase64, Path file) throws IOException {
		Path parent = file.toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Files.write(file, Base64.getDecoder().decode(pngBase64));
	}

	/**
	 * Decodes the PNG from the mod, scales it down to maxWidth or less (0 means no limit), and encodes it in
	 * the given format (png or jpeg, default png).
	 */
	static Image prepare(String pngBase64, int maxWidth, String format) throws IOException {
		String fmt = format(format);
		checkMaxWidth(maxWidth);
		BufferedImage source = decode(pngBase64);
		if (!(maxWidth > 0 && source.getWidth() > maxWidth) && fmt.equals("png")) {
			return new Image(pngBase64, "image/png", source.getWidth(), source.getHeight());
		}
		return encode(source, maxWidth, fmt);
	}

	/** Scales the image down to maxWidth or less (0 means no limit) and encodes it (png or jpeg). */
	static Image encode(BufferedImage source, int maxWidth, String format) throws IOException {
		String fmt = format(format);
		checkMaxWidth(maxWidth);
		int width = source.getWidth();
		int height = source.getHeight();
		if (maxWidth > 0 && width > maxWidth) {
			height = Math.max(1, Math.round(height * (maxWidth / (float) width)));
			width = maxWidth;
		}
		BufferedImage out = resize(source, width, height);
		byte[] bytes = fmt.equals("png") ? encodePng(out) : encodeJpeg(out);
		return new Image(Base64.getEncoder().encodeToString(bytes), "image/" + fmt, width, height);
	}

	/**
	 * Compares the screenshot with the baseline, pixel by pixel, at the full screenshot size. If the sizes are not
	 * the same, the baseline is scaled to the screenshot size first. The diff image shows the screenshot dark and
	 * gray, with the changed pixels in red (brighter red is a bigger difference).
	 */
	static Comparison compare(BufferedImage current, BufferedImage baseline, int tolerance) {
		if (tolerance < 0 || tolerance > 255) {
			throw new IllegalArgumentException("tolerance must be 0-255");
		}
		int width = current.getWidth();
		int height = current.getHeight();
		boolean scaled = baseline.getWidth() != width || baseline.getHeight() != height;
		BufferedImage base = scaled ? resize(baseline, width, height) : baseline;
		BufferedImage diff = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

		long sum = 0;
		long changed = 0;
		int maxDiff = 0;
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxX = -1;
		int maxY = -1;
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int a = current.getRGB(x, y);
				int b = base.getRGB(x, y);
				int dr = Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF));
				int dg = Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF));
				int db = Math.abs((a & 0xFF) - (b & 0xFF));
				sum += dr + dg + db;
				int d = Math.max(dr, Math.max(dg, db));
				maxDiff = Math.max(maxDiff, d);
				if (d > tolerance) {
					changed++;
					minX = Math.min(minX, x);
					minY = Math.min(minY, y);
					maxX = Math.max(maxX, x);
					maxY = Math.max(maxY, y);
					int red = 128 + d / 2;
					diff.setRGB(x, y, red << 16);
				} else {
					int gray = Math.round(luma(a) * DIFF_BACKGROUND);
					diff.setRGB(x, y, (gray << 16) | (gray << 8) | gray);
				}
			}
		}
		long total = (long) width * height;
		double score = total == 0 ? 0 : sum * 100.0 / (total * 3 * 255);
		if (changed == 0) {
			minX = minY = maxX = maxY = -1;
		}
		return new Comparison(score, changed, total, maxDiff, minX, minY, maxX, maxY, scaled,
				baseline.getWidth(), baseline.getHeight(), diff);
	}

	private static int luma(int rgb) {
		return (((rgb >> 16) & 0xFF) * 299 + ((rgb >> 8) & 0xFF) * 587 + (rgb & 0xFF) * 114) / 1000;
	}

	private static String format(String format) {
		String fmt = format == null || format.isBlank() ? "png" : format.trim().toLowerCase(Locale.ROOT);
		if (fmt.equals("jpg")) {
			fmt = "jpeg";
		}
		if (!fmt.equals("png") && !fmt.equals("jpeg")) {
			throw new IllegalArgumentException("format must be png or jpeg, not '" + format + "'");
		}
		return fmt;
	}

	private static void checkMaxWidth(int maxWidth) {
		if (maxWidth < 0) {
			throw new IllegalArgumentException("max_width must be 0 or more");
		}
	}

	/** RGB with no alpha: the game view is opaque, and JPEG cannot hold alpha. */
	private static BufferedImage resize(BufferedImage source, int width, int height) {
		BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.drawImage(source, 0, 0, width, height, null);
		} finally {
			g.dispose();
		}
		return out;
	}

	private static byte[] encodePng(BufferedImage image) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		ImageIO.write(image, "png", bytes);
		return bytes.toByteArray();
	}

	private static byte[] encodeJpeg(BufferedImage image) throws IOException {
		ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
			writer.setOutput(out);
			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(JPEG_QUALITY);
			writer.write(null, new IIOImage(image, null, null), param);
		} finally {
			writer.dispose();
		}
		return bytes.toByteArray();
	}
}
