package com.goawaygo.backend.media;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.apache.commons.imaging.ImageInfo;
import org.apache.commons.imaging.Imaging;
import org.apache.commons.imaging.ImagingException;
import org.apache.commons.imaging.common.ImageMetadata;
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata;
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.jpeg.xmp.JpegXmpRewriter;
import org.apache.commons.imaging.formats.tiff.TiffImageMetadata;
import org.apache.commons.imaging.formats.tiff.constants.TiffDirectoryConstants;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputDirectory;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;

/**
 * Wanderbook photos are kept as the original files (decided for 4.2: a scrapbook, never resized or recompressed).
 * Only hidden location data is removed: the GPS is read to suggest the place tag, then dropped from the file by
 * rewriting its metadata, without decoding or re-encoding the picture. Exact coordinates are never stored.
 */
public final class PhotoFiles {

	/** JPEG, PNG or WebP (what phones and browsers make and show; HEIC is converted to JPEG on the phone). */
	public enum Type {

		JPEG("image/jpeg", "jpg"), PNG("image/png", "png"), WEBP("image/webp", "webp");

		public final String contentType;

		public final String extension;

		Type(String contentType, String extension) {
			this.contentType = contentType;
			this.extension = extension;
		}

	}

	/** A photo ready to store: its file without location data, size and, if the photo had one, where it was taken. */
	public record Prepared(byte[] content, Type type, int width, int height, Double latitude, Double longitude) {
	}

	/** Why a file was refused (shown to the user). */
	public static final class Rejected extends Exception {

		Rejected(String message) {
			super(message, null, false, false);
		}

	}

	private PhotoFiles() {
	}

	public static Prepared prepare(byte[] file) throws Rejected {
		Type type = detect(file);
		int width;
		int height;
		try {
			ImageInfo info = Imaging.getImageInfo(file);
			width = info.getWidth();
			height = info.getHeight();
		}
		catch (IOException | RuntimeException ex) {
			throw new Rejected("This file couldn't be read as a photo.");
		}
		if (width <= 0 || height <= 0) {
			throw new Rejected("This file couldn't be read as a photo.");
		}
		return switch (type) {
			case JPEG -> prepareJpeg(file, width, height);
			case PNG -> new Prepared(withoutPngChunks(file), type, width, height, null, null);
			case WEBP -> new Prepared(withoutWebpChunks(file), type, width, height, null, null);
		};
	}

	static Type detect(byte[] file) throws Rejected {
		if (file.length > 3 && (file[0] & 0xFF) == 0xFF && (file[1] & 0xFF) == 0xD8 && (file[2] & 0xFF) == 0xFF) {
			return Type.JPEG;
		}
		if (file.length > 8 && Arrays.equals(Arrays.copyOf(file, 8),
				new byte[] { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' })) {
			return Type.PNG;
		}
		if (file.length > 12 && ascii(file, 0, 4).equals("RIFF") && ascii(file, 8, 4).equals("WEBP")) {
			return Type.WEBP;
		}
		if (file.length > 12 && ascii(file, 4, 4).equals("ftyp")
				&& Set.of("heic", "heix", "hevc", "mif1", "msf1", "heif").contains(ascii(file, 8, 4))) {
			throw new Rejected("HEIC photos can't be shown in browsers. Please share it as JPEG.");
		}
		throw new Rejected("Only JPEG, PNG and WebP photos can be posted.");
	}

	// ---- JPEG: read the GPS, then remove it (EXIF GPS directory and XMP) without touching the picture ----

	private static Prepared prepareJpeg(byte[] file, int width, int height) throws Rejected {
		Double latitude = null;
		Double longitude = null;
		byte[] content = file;
		try {
			ImageMetadata metadata = Imaging.getMetadata(file);
			TiffImageMetadata exif = (metadata instanceof JpegImageMetadata jpeg) ? jpeg.getExif() : null;
			if (exif != null && exif.findDirectory(TiffDirectoryConstants.DIRECTORY_TYPE_GPS) != null) {
				TiffImageMetadata.GpsInfo gps = exif.getGpsInfo();
				if (gps != null) {
					latitude = gps.getLatitudeAsDegreesNorth();
					longitude = gps.getLongitudeAsDegreesEast();
				}
				TiffOutputSet output = exif.getOutputSet();
				TiffOutputDirectory gpsDirectory = output.getGpsDirectory();
				if (gpsDirectory != null) {
					for (var field : List.copyOf(gpsDirectory.getFields())) {
						gpsDirectory.removeField(field.tag);
					}
				}
				ByteArrayOutputStream rewritten = new ByteArrayOutputStream(file.length);
				new ExifRewriter().updateExifMetadataLossless(file, rewritten, output);
				content = rewritten.toByteArray();
			}
			// XMP can hold the location too (some camera and editing apps write it there).
			ByteArrayOutputStream withoutXmp = new ByteArrayOutputStream(content.length);
			new JpegXmpRewriter().removeXmpXml(content, withoutXmp);
			content = withoutXmp.toByteArray();
		}
		catch (ImagingException ex) {
			throw new Rejected("This photo's location data couldn't be removed, so it can't be posted.");
		}
		catch (IOException | RuntimeException ex) {
			throw new Rejected("This file couldn't be read as a photo.");
		}
		if (latitude != null && (latitude.isNaN() || Math.abs(latitude) > 90 || longitude.isNaN()
				|| Math.abs(longitude) > 180)) {
			latitude = null;
			longitude = null;
		}
		return new Prepared(content, Type.JPEG, width, height, latitude, longitude);
	}

	// ---- PNG: drop the metadata chunks (EXIF and text, where XMP lives) ----

	private static final Set<String> PNG_METADATA = Set.of("eXIf", "iTXt", "tEXt", "zTXt");

	static byte[] withoutPngChunks(byte[] file) throws Rejected {
		ByteArrayOutputStream out = new ByteArrayOutputStream(file.length);
		out.write(file, 0, 8);
		int at = 8;
		while (at + 12 <= file.length) {
			int length = ByteBuffer.wrap(file, at, 4).getInt();
			if (length < 0 || at + 12L + length > file.length) {
				throw new Rejected("This file couldn't be read as a photo.");
			}
			String type = ascii(file, at + 4, 4);
			if (!PNG_METADATA.contains(type)) {
				out.write(file, at, 12 + length);
			}
			at += 12 + length;
			if (type.equals("IEND")) {
				break;
			}
		}
		return out.toByteArray();
	}

	// ---- WebP: drop the EXIF and XMP chunks and clear their flags ----

	static byte[] withoutWebpChunks(byte[] file) throws Rejected {
		ByteArrayOutputStream body = new ByteArrayOutputStream(file.length);
		int at = 12;
		while (at + 8 <= file.length) {
			String type = ascii(file, at, 4);
			int length = ByteBuffer.wrap(file, at + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
			int padded = length + (length & 1);
			if (length < 0 || at + 8L + length > file.length) {
				throw new Rejected("This file couldn't be read as a photo.");
			}
			if (!type.equals("EXIF") && !type.equals("XMP ")) {
				byte[] chunk = Arrays.copyOfRange(file, at, Math.min(file.length, at + 8 + padded));
				if (type.equals("VP8X") && chunk.length > 8) {
					chunk[8] &= (byte) ~(0x08 | 0x04); // EXIF and XMP flags
				}
				body.write(chunk, 0, chunk.length);
			}
			at += 8 + padded;
		}
		byte[] chunks = body.toByteArray();
		ByteBuffer out = ByteBuffer.allocate(12 + chunks.length).order(ByteOrder.LITTLE_ENDIAN);
		out.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(4 + chunks.length);
		out.put("WEBP".getBytes(StandardCharsets.US_ASCII)).put(chunks);
		return out.array();
	}

	private static String ascii(byte[] file, int offset, int length) {
		return new String(file, offset, length, StandardCharsets.ISO_8859_1);
	}

}
