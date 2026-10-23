package io.github.lordship.sitemaps.internal;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Reads the height grid USGS sends back: a TIFF with one band of numbers.
 *
 * <p>Java's own image readers cannot be trusted with 32-bit float TIFFs, so this
 * reads the file directly. It handles what an elevation service sends:
 * <ul>
 *   <li>strips or tiles, either byte order</li>
 *   <li>no compression, LZW, Deflate or PackBits</li>
 *   <li>predictor 1 (none), 2 (whole numbers) and 3 (floating point)</li>
 *   <li>samples that are 32 or 64-bit floats, or 8, 16 or 32-bit whole numbers.
 *       Only the first band is kept</li>
 * </ul>
 * The file's "no data" value (GDAL_NODATA) comes back as NaN.
 */
final class HeightTiff {

    private HeightTiff() { }

    /** One band of numbers, top row first, width * height of them. */
    record Grid(int width, int height, float[] values) {

        float at(int col, int row) {
            return values[row * width + col];
        }
    }

    private static final int IMAGE_WIDTH = 256;
    private static final int IMAGE_LENGTH = 257;
    private static final int BITS_PER_SAMPLE = 258;
    private static final int COMPRESSION = 259;
    private static final int STRIP_OFFSETS = 273;
    private static final int SAMPLES_PER_PIXEL = 277;
    private static final int ROWS_PER_STRIP = 278;
    private static final int STRIP_BYTE_COUNTS = 279;
    private static final int PLANAR_CONFIG = 284;
    private static final int PREDICTOR = 317;
    private static final int TILE_WIDTH = 322;
    private static final int TILE_LENGTH = 323;
    private static final int TILE_OFFSETS = 324;
    private static final int TILE_BYTE_COUNTS = 325;
    private static final int SAMPLE_FORMAT = 339;
    private static final int GDAL_NODATA = 42113;

    static Grid read(byte[] file) {
        if (file == null || file.length < 8) {
            throw new IllegalArgumentException("Not a TIFF: too short");
        }
        ByteOrder order;
        if (file[0] == 'I' && file[1] == 'I') {
            order = ByteOrder.LITTLE_ENDIAN;
        } else if (file[0] == 'M' && file[1] == 'M') {
            order = ByteOrder.BIG_ENDIAN;
        } else {
            throw new IllegalArgumentException("Not a TIFF: " + preview(file));
        }
        ByteBuffer buf = ByteBuffer.wrap(file).order(order);
        if (buf.getShort(2) != 42) {
            throw new IllegalArgumentException("Only classic TIFF files are read, not BigTIFF");
        }

        Tags tags = new Tags(buf, Integer.toUnsignedLong(buf.getInt(4)));
        int width = (int) tags.one(IMAGE_WIDTH, 0);
        int height = (int) tags.one(IMAGE_LENGTH, 0);
        int bits = (int) tags.one(BITS_PER_SAMPLE, 1);
        int compression = (int) tags.one(COMPRESSION, 1);
        int samples = (int) tags.one(SAMPLES_PER_PIXEL, 1);
        int planar = (int) tags.one(PLANAR_CONFIG, 1);
        int predictor = (int) tags.one(PREDICTOR, 1);
        int format = (int) tags.one(SAMPLE_FORMAT, 1);
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("The TIFF has no size");
        }
        if (bits % 8 != 0) {
            throw new IllegalArgumentException("Samples of " + bits + " bits are not read");
        }
        int bytes = bits / 8;
        // With bands stored one after another, the first band is simply the first part of the data
        int perPixel = planar == 2 ? 1 : samples;

        boolean tiled = tags.has(TILE_OFFSETS);
        int blockWidth = tiled ? (int) tags.one(TILE_WIDTH, 0) : width;
        int blockHeight = tiled ? (int) tags.one(TILE_LENGTH, 0) : (int) Math.min(tags.one(ROWS_PER_STRIP, height), height);
        long[] offsets = tags.all(tiled ? TILE_OFFSETS : STRIP_OFFSETS);
        long[] counts = tags.all(tiled ? TILE_BYTE_COUNTS : STRIP_BYTE_COUNTS);
        int across = tiled ? ceilDiv(width, blockWidth) : 1;
        int down = ceilDiv(height, blockHeight);

        float[] values = new float[width * height];
        int rowBytes = blockWidth * perPixel * bytes;
        for (int block = 0; block < Math.min(offsets.length, across * down); block++) {
            int left = (block % across) * blockWidth;
            int top = (block / across) * blockHeight;
            // The last strip can be shorter than the others
            int rows = tiled ? blockHeight : Math.min(blockHeight, height - top);
            byte[] data = decompress(file, offsets[block], counts[block], compression, rowBytes * rows);
            for (int r = 0; r < rows; r++) {
                int start = r * rowBytes;
                if (start + rowBytes > data.length) {
                    break;
                }
                unpredict(data, start, rowBytes, predictor, bytes, perPixel, order);
            }
            ByteOrder valueOrder = predictor == 3 ? ByteOrder.BIG_ENDIAN : order;
            ByteBuffer blockData = ByteBuffer.wrap(data).order(valueOrder);
            for (int r = 0; r < rows && top + r < height; r++) {
                for (int c = 0; c < blockWidth && left + c < width; c++) {
                    int at = r * rowBytes + c * perPixel * bytes;
                    if (at + bytes > data.length) {
                        continue;
                    }
                    values[(top + r) * width + left + c] = sample(blockData, at, bytes, format);
                }
            }
        }

        String noData = tags.text(GDAL_NODATA);
        if (noData != null && !noData.isBlank()) {
            try {
                float missing = Float.parseFloat(noData.strip());
                for (int i = 0; i < values.length; i++) {
                    if (values[i] == missing) {
                        values[i] = Float.NaN;
                    }
                }
            } catch (NumberFormatException ignored) {
                // An unreadable "no data" value is ignored. Elevation's own range check still applies.
            }
        }
        return new Grid(width, height, values);
    }

    private static float sample(ByteBuffer b, int at, int bytes, int format) {
        return switch (format) {
            case 3 -> bytes == 8 ? (float) b.getDouble(at) : b.getFloat(at);
            case 2 -> switch (bytes) {          // signed whole numbers
                case 1 -> b.get(at);
                case 2 -> b.getShort(at);
                default -> b.getInt(at);
            };
            default -> switch (bytes) {         // unsigned whole numbers
                case 1 -> b.get(at) & 0xFF;
                case 2 -> b.getShort(at) & 0xFFFF;
                default -> (float) Integer.toUnsignedLong(b.getInt(at));
            };
        };
    }

    // ---- predictors -------------------------------------------------------------

    // Undoes the predictor on one row, in place.
    //   2: each sample was stored as the difference from the sample before it
    //   3: each row was split into byte planes, most significant first, then differenced byte by byte
    private static void unpredict(byte[] d, int start, int length, int predictor, int bytes, int perPixel,
                                  ByteOrder order) {
        if (predictor == 2) {
            ByteBuffer b = ByteBuffer.wrap(d).order(order);
            int step = bytes * perPixel;
            for (int i = start + step; i + bytes <= start + length; i += bytes) {
                int before = i - step;
                switch (bytes) {
                    case 1 -> d[i] = (byte) (d[i] + d[before]);
                    case 2 -> b.putShort(i, (short) (b.getShort(i) + b.getShort(before)));
                    case 4 -> b.putInt(i, b.getInt(i) + b.getInt(before));
                    default -> b.putLong(i, b.getLong(i) + b.getLong(before));
                }
            }
        } else if (predictor == 3) {
            for (int i = start + perPixel; i < start + length; i++) {
                d[i] = (byte) (d[i] + d[i - perPixel]);
            }
            int count = length / bytes;
            byte[] planes = new byte[length];
            System.arraycopy(d, start, planes, 0, length);
            for (int k = 0; k < count; k++) {
                for (int b = 0; b < bytes; b++) {
                    d[start + k * bytes + b] = planes[b * count + k];
                }
            }
        }
    }

    // ---- decompression ---------------------------------------------------------

    private static byte[] decompress(byte[] file, long offset, long count, int compression, int expected) {
        int from = (int) offset;
        int length = (int) Math.min(count, file.length - offset);
        return switch (compression) {
            case 1 -> java.util.Arrays.copyOfRange(file, from, from + length);
            case 5 -> lzw(file, from, length, expected);
            case 8, 32946 -> inflate(file, from, length, expected);
            case 32773 -> packBits(file, from, length, expected);
            default -> throw new IllegalArgumentException("TIFF compression " + compression + " is not read");
        };
    }

    private static byte[] inflate(byte[] file, int from, int length, int expected) {
        Inflater inflater = new Inflater();
        inflater.setInput(file, from, length);
        byte[] out = new byte[Math.max(expected, 1)];
        int filled = 0;
        try {
            while (!inflater.finished()) {
                if (filled == out.length) {
                    out = java.util.Arrays.copyOf(out, out.length * 2);
                }
                int n = inflater.inflate(out, filled, out.length - filled);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                filled += n;
            }
        } catch (DataFormatException e) {
            throw new IllegalArgumentException("The TIFF's Deflate data is broken", e);
        } finally {
            inflater.end();
        }
        return java.util.Arrays.copyOf(out, filled);
    }

    private static byte[] packBits(byte[] file, int from, int length, int expected) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(expected);
        int i = from;
        int end = from + length;
        while (i < end && out.size() < expected) {
            int n = file[i++];
            if (n >= 0) {
                out.write(file, i, Math.min(n + 1, end - i));
                i += n + 1;
            } else if (n != -128) {
                byte b = file[i++];
                for (int k = 0; k < 1 - n; k++) {
                    out.write(b);
                }
            }
        }
        return out.toByteArray();
    }

    // TIFF's LZW: codes are read most significant bit first, and the code size grows one code early
    private static byte[] lzw(byte[] file, int from, int length, int expected) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(expected);
        byte[][] table = new byte[4096][];
        for (int i = 0; i < 256; i++) {
            table[i] = new byte[] {(byte) i};
        }
        int next = 258;
        int size = 9;
        long bitPos = (long) from * 8;
        long bitEnd = (long) (from + length) * 8;
        byte[] previous = null;
        while (bitPos + size <= bitEnd) {
            int code = 0;
            for (int k = 0; k < size; k++) {
                long p = bitPos + k;
                int bit = (file[(int) (p >>> 3)] >> (7 - (int) (p & 7))) & 1;
                code = (code << 1) | bit;
            }
            bitPos += size;
            if (code == 257) {          // end of information
                break;
            }
            if (code == 256) {          // clear the table
                next = 258;
                size = 9;
                previous = null;
                continue;
            }
            byte[] entry;
            if (code < next && table[code] != null) {
                entry = table[code];
                if (previous != null && next < 4096) {
                    table[next++] = concat(previous, entry[0]);
                }
            } else if (previous != null) {
                entry = concat(previous, previous[0]);
                if (next < 4096) {
                    table[next++] = entry;
                }
            } else {
                throw new IllegalArgumentException("The TIFF's LZW data is broken");
            }
            out.write(entry, 0, entry.length);
            previous = entry;
            if (next + 1 >= (1 << size) && size < 12) {
                size++;
            }
        }
        return out.toByteArray();
    }

    private static byte[] concat(byte[] head, byte tail) {
        byte[] out = java.util.Arrays.copyOf(head, head.length + 1);
        out[head.length] = tail;
        return out;
    }

    // ---- tags ------------------------------------------------------------------------

    // The first image's tags, read from its directory
    private static final class Tags {
        private final ByteBuffer buf;
        private final java.util.Map<Integer, long[]> numbers = new java.util.HashMap<>();
        private final java.util.Map<Integer, String> texts = new java.util.HashMap<>();

        Tags(ByteBuffer buf, long at) {
            this.buf = buf;
            int count = buf.getShort((int) at) & 0xFFFF;
            for (int i = 0; i < count; i++) {
                int entry = (int) at + 2 + i * 12;
                int tag = buf.getShort(entry) & 0xFFFF;
                int type = buf.getShort(entry + 2) & 0xFFFF;
                int n = buf.getInt(entry + 4);
                int size = typeSize(type);
                if (size == 0 || n < 0) {
                    continue;
                }
                int valueAt = size * (long) n <= 4 ? entry + 8 : buf.getInt(entry + 8);
                if (type == 2) {
                    byte[] text = new byte[n];
                    for (int k = 0; k < n; k++) {
                        text[k] = buf.get(valueAt + k);
                    }
                    texts.put(tag, new String(text, StandardCharsets.US_ASCII).replace("\0", ""));
                    continue;
                }
                long[] values = new long[n];
                for (int k = 0; k < n; k++) {
                    int p = valueAt + k * size;
                    values[k] = switch (type) {
                        case 1, 7 -> buf.get(p) & 0xFF;
                        case 6 -> buf.get(p);
                        case 3 -> buf.getShort(p) & 0xFFFF;
                        case 8 -> buf.getShort(p);
                        case 4 -> Integer.toUnsignedLong(buf.getInt(p));
                        case 9 -> buf.getInt(p);
                        default -> 0;   // fractions and floats: no tag read here uses them
                    };
                }
                numbers.put(tag, values);
            }
        }

        boolean has(int tag) {
            return numbers.containsKey(tag);
        }

        long one(int tag, long fallback) {
            long[] v = numbers.get(tag);
            return v == null || v.length == 0 ? fallback : v[0];
        }

        long[] all(int tag) {
            long[] v = numbers.get(tag);
            if (v == null) {
                throw new IllegalArgumentException("The TIFF is missing tag " + tag);
            }
            return v;
        }

        String text(int tag) {
            return texts.get(tag);
        }

        private static int typeSize(int type) {
            return switch (type) {
                case 1, 2, 6, 7 -> 1;
                case 3, 8 -> 2;
                case 4, 9, 11 -> 4;
                case 5, 10, 12 -> 8;
                default -> 0;
            };
        }
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    // The start of a reply that is not a TIFF, for the error message: services answer errors as JSON or HTML
    private static String preview(byte[] file) {
        String text = new String(file, 0, Math.min(file.length, 200), StandardCharsets.UTF_8);
        return text.replaceAll("\\s+", " ").strip();
    }
}
