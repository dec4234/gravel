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
import java.util.Base64;
import java.util.Locale;

/** Makes the game screenshot ready to send: scales it down and encodes it as PNG or JPEG. */
final class Screenshots {
	/** Default largest width. Big images cost more to send and to read, with little more detail. */
	static final int DEFAULT_MAX_WIDTH = 1280;
	private static final float JPEG_QUALITY = 0.85f;

	/** An encoded image ready for MCP image content. */
	record Image(String base64, String mimeType, int width, int height) {
	}

	private Screenshots() {
	}

	/**
	 * Decodes the PNG from the mod, scales it down to maxWidth or less (0 means no limit), and encodes it in
	 * the given format (png or jpeg, default png).
	 */
	static Image prepare(String pngBase64, int maxWidth, String format) throws IOException {
		String fmt = format == null || format.isBlank() ? "png" : format.trim().toLowerCase(Locale.ROOT);
		if (fmt.equals("jpg")) {
			fmt = "jpeg";
		}
		if (!fmt.equals("png") && !fmt.equals("jpeg")) {
			throw new IllegalArgumentException("format must be png or jpeg, not '" + format + "'");
		}
		if (maxWidth < 0) {
			throw new IllegalArgumentException("max_width must be 0 or more");
		}
		byte[] png = Base64.getDecoder().decode(pngBase64);
		BufferedImage source = ImageIO.read(new ByteArrayInputStream(png));
		if (source == null) {
			throw new IOException("The game sent an image that is not a PNG");
		}

		int width = source.getWidth();
		int height = source.getHeight();
		boolean scale = maxWidth > 0 && width > maxWidth;
		if (!scale && fmt.equals("png")) {
			return new Image(pngBase64, "image/png", width, height);
		}
		if (scale) {
			height = Math.max(1, Math.round(height * (maxWidth / (float) width)));
			width = maxWidth;
		}
		// RGB with no alpha: the game view is opaque, and JPEG cannot hold alpha.
		BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.drawImage(source, 0, 0, width, height, null);
		} finally {
			g.dispose();
		}
		byte[] bytes = fmt.equals("png") ? encodePng(out) : encodeJpeg(out);
		return new Image(Base64.getEncoder().encodeToString(bytes), "image/" + fmt, width, height);
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
