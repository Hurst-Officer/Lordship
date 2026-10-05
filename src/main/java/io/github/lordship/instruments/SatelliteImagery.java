package io.github.lordship.instruments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Satellite photos for the lot map, stitched from Esri World Imagery tiles.
 *
 * <p>Tiles are the standard web map squares: 256 pixels, addressed by zoom,
 * column and row. This picks the zoom that matches the detail the page asked
 * for, fetches every tile that covers the box, and pastes them into one
 * picture.
 *
 * <p>If anything goes wrong (the service is down, a tile is missing at every
 * zoom tried, the park is too big) there is no photo and the map prints on
 * white. The lease is still a lease without the photo.
 */
@Component
class SatelliteImagery implements LotMapDrawing.Imagery {

    private static final Logger log = LoggerFactory.getLogger(SatelliteImagery.class);

    private static final int TILE_PIXELS = 256;

    /** Meters per pixel at zoom 0 on the equator, for 256-pixel tiles. */
    private static final double METERS_PER_PIXEL_AT_ZOOM_0 = 156543.03392;

    /** More tiles than this and the zoom drops a step. About 40 MB of picture. */
    private static final int MOST_TILES = 160;

    /** How many tiles are fetched at once. */
    private static final int AT_ONCE = 8;

    /**
     * How many zooms below the ideal one are tried. Rural areas sometimes
     * have no tiles at the sharpest zoom.
     */
    private static final int FALLBACK_ZOOMS = 2;

    private final boolean enabled;
    private final String tiles;
    private final String key;
    private final int maxZoom;
    private final String credit;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    SatelliteImagery(@Value("${lordship.imagery.enabled:false}") boolean enabled,
                     @Value("${lordship.imagery.tiles:}") String tiles,
                     @Value("${lordship.imagery.key:}") String key,
                     @Value("${lordship.imagery.max-zoom:19}") int maxZoom,
                     @Value("${lordship.imagery.credit:}") String credit) {
        this.enabled = enabled && tiles != null && !tiles.isBlank();
        this.tiles = tiles;
        this.key = key == null ? "" : key.trim();
        this.maxZoom = maxZoom;
        this.credit = credit;
    }

    @Override
    public Optional<LotMapDrawing.Photo> photo(double west, double south, double east, double north,
                                               double metersPerPixel) {
        if (!enabled) {
            return Optional.empty();
        }

        int zoom = zoomFor((south + north) / 2, metersPerPixel);
        while (zoom > 1 && tileCount(west, south, east, north, zoom) > MOST_TILES) {
            zoom--;
        }

        for (int tried = 0; tried <= FALLBACK_ZOOMS && zoom - tried >= 1; tried++) {
            try {
                return Optional.of(stitch(west, south, east, north, zoom - tried));
            } catch (IOException e) {
                log.warn("Satellite photo at zoom {} failed: {}", zoom - tried, e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        log.warn("No satellite photo for the lot map; it will print without one");
        return Optional.empty();
    }

    /** The least zoom whose pixels are at least as fine as asked, up to maxZoom. */
    private int zoomFor(double lat, double metersPerPixel) {
        double atZoom0 = METERS_PER_PIXEL_AT_ZOOM_0 * Math.cos(Math.toRadians(lat));
        int zoom = (int) Math.ceil(Math.log(atZoom0 / metersPerPixel) / Math.log(2));
        return Math.max(1, Math.min(zoom, maxZoom));
    }

    private static int tileCount(double west, double south, double east, double north, int zoom) {
        int columns = (int) Math.floor(column(east, zoom)) - (int) Math.floor(column(west, zoom)) + 1;
        int rows = (int) Math.floor(row(south, zoom)) - (int) Math.floor(row(north, zoom)) + 1;
        return columns * rows;
    }

    /**
     * Fetches every tile over the box and pastes them together. The photo's
     * edges are the outer edges of the tiles, which reach a little past the
     * box on every side.
     *
     * @throws IOException when any tile cannot be had
     */
    private LotMapDrawing.Photo stitch(double west, double south, double east, double north, int zoom)
            throws IOException, InterruptedException {

        int firstColumn = (int) Math.floor(column(west, zoom));
        int lastColumn = (int) Math.floor(column(east, zoom));
        int firstRow = (int) Math.floor(row(north, zoom));
        int lastRow = (int) Math.floor(row(south, zoom));

        BufferedImage photo = new BufferedImage(
                (lastColumn - firstColumn + 1) * TILE_PIXELS,
                (lastRow - firstRow + 1) * TILE_PIXELS,
                BufferedImage.TYPE_INT_RGB);

        List<int[]> places = new ArrayList<>();
        List<Future<BufferedImage>> fetched = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(AT_ONCE)) {
            for (int row = firstRow; row <= lastRow; row++) {
                for (int column = firstColumn; column <= lastColumn; column++) {
                    URI uri = tileUri(zoom, column, row);
                    places.add(new int[] {column - firstColumn, row - firstRow});
                    fetched.add(pool.submit(() -> fetch(uri)));
                }
            }

            Graphics2D g = photo.createGraphics();
            try {
                for (int i = 0; i < fetched.size(); i++) {
                    BufferedImage tile = fetched.get(i).get();
                    g.drawImage(tile, places.get(i)[0] * TILE_PIXELS, places.get(i)[1] * TILE_PIXELS, null);
                }
            } finally {
                g.dispose();
            }
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw new IOException(e.getCause());
        }

        return new LotMapDrawing.Photo(
                photo,
                longitude(firstColumn, zoom),
                latitude(lastRow + 1, zoom),
                longitude(lastColumn + 1, zoom),
                latitude(firstRow, zoom),
                credit);
    }

    private BufferedImage fetch(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "Lordship")
                .GET()
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("tile " + uri.getPath() + " answered " + response.statusCode());
        }
        BufferedImage tile = ImageIO.read(new ByteArrayInputStream(response.body()));
        if (tile == null) {
            throw new IOException("tile " + uri.getPath() + " is not a picture");
        }
        return tile;
    }

    /**
     * The tile's address. blankTile=false asks the service for a 404 instead
     * of a "no data" picture where it has no imagery, so the next zoom down
     * gets tried rather than printing the placeholder on a lease.
     */
    private URI tileUri(int zoom, int column, int row) {
        String url = tiles
                .replace("{z}", Integer.toString(zoom))
                .replace("{x}", Integer.toString(column))
                .replace("{y}", Integer.toString(row));
        url += (url.contains("?") ? "&" : "?") + "blankTile=false";
        if (!key.isEmpty()) {
            url += "&token=" + key;
        }
        return URI.create(url);
    }

    // ---- web map tile math ---------------------------------------------------

    /** Which tile column a longitude falls in, with the fraction across it. */
    private static double column(double lng, int zoom) {
        return (lng + 180) / 360 * (1 << zoom);
    }

    /** Which tile row a latitude falls in, with the fraction down it. */
    private static double row(double lat, int zoom) {
        double radians = Math.toRadians(lat);
        return (1 - Math.log(Math.tan(radians) + 1 / Math.cos(radians)) / Math.PI) / 2 * (1 << zoom);
    }

    /** The longitude of a tile column's left edge. */
    private static double longitude(int column, int zoom) {
        return (double) column / (1 << zoom) * 360 - 180;
    }

    /** The latitude of a tile row's top edge. */
    private static double latitude(int row, int zoom) {
        double n = Math.PI - 2 * Math.PI * row / (1 << zoom);
        return Math.toDegrees(Math.atan(Math.sinh(n)));
    }
}
