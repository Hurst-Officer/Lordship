package io.github.lordship.sitemaps.internal;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Reading height grids. Both files hold the same 8 x 6 grid: 100 + (row * 8 + col) / 2,
// with -9999 in the top-left corner.
class HeightTiffTest {

    // Little-endian, Deflate, floating point predictor, one 16 x 16 tile, GDAL_NODATA = -9999
    private static final String TILED_DEFLATE =
            "SUkqAAgAAAASAAABBAABAAAACAAAAAEBBAABAAAABgAAAAIBAwABAAAAIAAAAAMBAwABAAAACAAAAAYBAwABAAAAAQAAAA4BAgASAAAA"
            + "5gAAABUBAwABAAAAAQAAABoBBQABAAAACAEAABsBBQABAAAAEAEAACgBAwABAAAAAQAAADEBAgAMAAAAGAEAAD0BAwABAAAAAwAAAEIB"
            + "BAABAAAAEAAAAEMBBAABAAAAEAAAAEQBBAABAAAAMAEAAEUBBAABAAAAUQAAAFMBAwABAAAAAwAAAIGkAgAGAAAAJAEAAAAAAAB7InNo"
            + "YXBlIjogWzYsIDhdfQAAAAAAAAAAAAAAAAAAAAAAAQAAAAEAAAABAAAAAQAAAHRpZmZmaWxlLnB5AC05OTk5AAAAAAAAAHicO1bDAAb7"
            + "IBSDzFpGMDCE8m2OMOAFTlAapv8CIwRoMhAH0PXfgOpXZCAOoOt/ANUvyUAcQNf/AqpfkIE4gK7/A1Q/J8MoGAWDHwAA2x0OAQ==";

    // Big-endian, LZW, two strips of 4 rows, no "no data" value
    private static final String STRIPS_LZW =
            "TU0AKgAAAAgADwEAAAQAAAABAAAACAEBAAQAAAABAAAABgECAAMAAAABACAAAAEDAAMAAAABAAUAAAEGAAMAAAABAAEAAAEOAAIAAAAS"
            + "AAAAwgERAAQAAAACAAAA5AEVAAMAAAABAAEAAAEWAAQAAAABAAAABAEXAAMAAAACAFAAKwEaAAUAAAABAAAA7AEbAAUAAAABAAAA9AEo"
            + "AAMAAAABAAEAAAExAAIAAAAMAAAA/AFTAAMAAAABAAMAAAAAAAB7InNoYXBlIjogWzYsIDhdfQAAAAAAAAAAAAAAAAAAAAAAAAABEAAA"
            + "AWAAAAABAAAAAQAAAAEAAAABdGlmZmZpbGUucHkAAAAAAAAAAACAMYODwAEJkgCCsqEEJlwtmQtmwtnQtnwtoQtowtpQtpwtqQtqwtrQ"
            + "trwtsQtswttQttwtuQtuwtvQtvwtwQtwwtxQtxwtyQtywtzQtzwiAoAQnQAAAQnTBCE6oQ64Q7IQ7YQ7oQ74Q8IQ8YQ8oQ84Q9IQ9YQ9"
            + "oQ94JAQ=";

    @Test
    void readsATiledDeflateFile_andTurnsNoDataIntoNaN() {
        HeightTiff.Grid grid = HeightTiff.read(Base64.getDecoder().decode(TILED_DEFLATE));

        assertEquals(8, grid.width());
        assertEquals(6, grid.height());
        assertTrue(Float.isNaN(grid.at(0, 0)));
        assertEquals(109.5f, grid.at(3, 2));
        assertEquals(123.5f, grid.at(7, 5));
    }

    @Test
    void readsABigEndianLzwFile() {
        HeightTiff.Grid grid = HeightTiff.read(Base64.getDecoder().decode(STRIPS_LZW));

        assertEquals(-9999f, grid.at(0, 0));
        assertEquals(109.5f, grid.at(3, 2));
        assertEquals(123.5f, grid.at(7, 5));
    }

    @Test
    void anErrorReply_isNotATiff() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> HeightTiff.read("{\"error\":{\"code\":400}}".getBytes(StandardCharsets.UTF_8)));
        assertTrue(e.getMessage().contains("error"));
    }
}
