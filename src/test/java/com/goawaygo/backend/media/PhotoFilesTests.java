package com.goawaygo.backend.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

import org.apache.commons.imaging.Imaging;
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata;
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.TiffImageMetadata;
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;
import org.junit.jupiter.api.Test;

/** Photos keep their picture exactly; only the location is read and removed. */
class PhotoFilesTests {

	@Test
	void aJpegKeepsItsPictureAndOrientationButLosesItsLocation() throws Exception {
		byte[] original = jpegWithGpsAndOrientation(48.8584, 2.2945, (short) 6);

		PhotoFiles.Prepared prepared = PhotoFiles.prepare(original);

		assertThat(prepared.type()).isEqualTo(PhotoFiles.Type.JPEG);
		assertThat(prepared.width()).isEqualTo(64);
		assertThat(prepared.latitude()).isCloseTo(48.8584, org.assertj.core.data.Offset.offset(0.0001));
		assertThat(prepared.longitude()).isCloseTo(2.2945, org.assertj.core.data.Offset.offset(0.0001));
		TiffImageMetadata exif = ((JpegImageMetadata) Imaging.getMetadata(prepared.content())).getExif();
		assertThat(exif.getGpsInfo()).isNull();
		assertThat(exif.findField(TiffTagConstants.TIFF_TAG_ORIENTATION).getIntValue()).isEqualTo(6);
		// The compressed picture itself is untouched: it decodes to exactly the same pixels.
		assertThat(pixels(prepared.content())).isEqualTo(pixels(original));
	}

	@Test
	void aJpegWithoutLocationIsKeptAsItIs() throws Exception {
		byte[] original = plainJpeg();
		PhotoFiles.Prepared prepared = PhotoFiles.prepare(original);
		assertThat(prepared.latitude()).isNull();
		assertThat(prepared.content()).isEqualTo(original);
	}

	@Test
	void pngMetadataChunksAreDropped() throws Exception {
		BufferedImage image = new BufferedImage(8, 4, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream png = new ByteArrayOutputStream();
		ImageIO.write(image, "png", png);
		byte[] withExif = insertPngChunk(png.toByteArray(), "eXIf", "GPS here".getBytes(StandardCharsets.US_ASCII));

		PhotoFiles.Prepared prepared = PhotoFiles.prepare(withExif);

		assertThat(prepared.type()).isEqualTo(PhotoFiles.Type.PNG);
		assertThat(prepared.width()).isEqualTo(8);
		assertThat(new String(prepared.content(), StandardCharsets.ISO_8859_1)).doesNotContain("eXIf");
		assertThat(prepared.content()).isEqualTo(png.toByteArray());
	}

	@Test
	void heicAndOtherFilesAreRefusedWithAReason() {
		byte[] heic = new byte[32];
		System.arraycopy("ftypheic".getBytes(StandardCharsets.US_ASCII), 0, heic, 4, 8);
		assertThatThrownBy(() -> PhotoFiles.prepare(heic)).hasMessageContaining("HEIC");
		assertThatThrownBy(() -> PhotoFiles.prepare("hello".repeat(10).getBytes(StandardCharsets.US_ASCII)))
			.hasMessageContaining("JPEG, PNG and WebP");
		byte[] brokenJpeg = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 1, 2, 3 };
		assertThatThrownBy(() -> PhotoFiles.prepare(brokenJpeg)).hasMessageContaining("couldn't be read");
	}

	private static byte[] plainJpeg() throws IOException {
		BufferedImage image = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(Color.ORANGE);
		g.fillRect(0, 0, 32, 48);
		g.setColor(Color.BLUE);
		g.fillOval(30, 10, 20, 20);
		g.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "jpg", out);
		return out.toByteArray();
	}

	private static byte[] jpegWithGpsAndOrientation(double latitude, double longitude, short orientation)
			throws Exception {
		TiffOutputSet exif = new TiffOutputSet();
		exif.getOrCreateRootDirectory().add(TiffTagConstants.TIFF_TAG_ORIENTATION, orientation);
		exif.setGpsInDegrees(longitude, latitude);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		new ExifRewriter().updateExifMetadataLossless(plainJpeg(), out, exif);
		return out.toByteArray();
	}

	private static int[] pixels(byte[] jpeg) throws IOException {
		BufferedImage image = ImageIO.read(new ByteArrayInputStream(jpeg));
		return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
	}

	/** Inserts a chunk right after IHDR (signature 8 bytes + IHDR 25 bytes). */
	private static byte[] insertPngChunk(byte[] png, String type, byte[] data) {
		ByteBuffer chunk = ByteBuffer.allocate(12 + data.length);
		byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
		CRC32 crc = new CRC32();
		crc.update(typeBytes);
		crc.update(data);
		chunk.putInt(data.length).put(typeBytes).put(data).putInt((int) crc.getValue());
		ByteBuffer out = ByteBuffer.allocate(png.length + chunk.capacity());
		out.put(png, 0, 33).put(chunk.array()).put(png, 33, png.length - 33);
		return out.array();
	}

}
