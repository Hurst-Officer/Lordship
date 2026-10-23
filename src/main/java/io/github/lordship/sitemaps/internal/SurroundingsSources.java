package io.github.lordship.sitemaps.internal;

import io.github.lordship.sitemaps.internal.SurroundingsRepository.PullArea;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

/**
 * Asks the outside sources for what is around a park. Nothing is saved here.
 *
 * <ul>
 *   <li>OpenStreetMap, through the Overpass API: roads, water, buildings.
 *       Free. Maps made from it must say "© OpenStreetMap contributors".</li>
 *   <li>Washington's statewide Current Parcels layer: property lines.
 *       Public. Only covers Washington; parks elsewhere get no parcels.</li>
 *   <li>USGS 3DEP: the height of the ground. Public domain, no key.</li>
 * </ul>
 *
 * <p>Every method throws {@link SourceDown} when the source cannot be reached or answers
 * with an error. Overpass and ArcGIS both report some errors inside an ordinary HTTP 200
 * reply, so those are checked too.
 */
@Component
public class SurroundingsSources {

    static final String OVERPASS_URL = "https://overpass-api.de/api/interpreter";
    static final String PARCELS_URL = "https://services.arcgis.com/jsIt88o09Q0r1j8h/arcgis/rest/services/"
            + "Current_Parcels/FeatureServer/0/query";
    static final String HEIGHTS_URL = "https://elevation.nationalmap.gov/arcgis/rest/services/"
            + "3DEPElevation/ImageServer/exportImage";

    // Overpass asks every caller to name itself
    private static final String USER_AGENT = "Lordship map builder (property management)";
    private static final Duration TIMEOUT = Duration.ofSeconds(90);

    /** A source that could not be reached, or that answered with an error. */
    public static class SourceDown extends RuntimeException {
        SourceDown(String detail) {
            super(detail);
        }

        SourceDown(String detail, Throwable cause) {
            super(detail, cause);
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper objectMapper;

    public SurroundingsSources(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Roads, water, streams, shoreline and buildings in the area: Overpass's "elements" list. */
    public JsonNode openStreetMap(PullArea area) {
        String box = "%s,%s,%s,%s".formatted(area.south(), area.west(), area.north(), area.east());
        String query = """
                [out:json][timeout:60];
                (
                  way["highway"](%1$s);
                  way["building"](%1$s);
                  way["natural"="water"](%1$s);
                  relation["natural"="water"](%1$s);
                  way["waterway"](%1$s);
                  way["natural"="coastline"](%1$s);
                );
                out tags geom;
                """.formatted(box);
        HttpRequest request = HttpRequest.newBuilder(URI.create(OVERPASS_URL))
                .timeout(TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("data=" + encode(query)))
                .build();
        JsonNode reply = json(request);
        // A busy Overpass says so in "remark" and sends back part of the answer, or none.
        // Saving that would wipe the good roads saved last time.
        if (reply.hasNonNull("remark")) {
            throw new SourceDown(firstLine(reply.get("remark").asString()));
        }
        return reply.path("elements");
    }

    /** County parcels touching the area, as GeoJSON features. */
    public JsonNode parcels(PullArea area) {
        String url = PARCELS_URL
                + "?geometry=" + encode("%s,%s,%s,%s".formatted(area.west(), area.south(), area.east(), area.north()))
                + "&geometryType=esriGeometryEnvelope&inSR=4326&spatialRel=esriSpatialRelIntersects"
                + "&outFields=" + encode("PARCEL_ID_NR,COUNTY_NM,SITUS_ADDRESS,SITUS_CITY_NM,SITUS_ZIP_NR,LANDUSE_CD")
                + "&outSR=4326&f=geojson";
        JsonNode reply = json(get(url));
        // ArcGIS reports errors inside a normal-looking reply
        if (reply.hasNonNull("error")) {
            throw new SourceDown(firstLine(reply.get("error").toString()));
        }
        if (reply.path("exceededTransferLimit").asBoolean(false)) {
            throw new SourceDown("too many parcels in the area");
        }
        return reply.path("features");
    }

    /** The ground's height in meters, as a grid in Web Mercator with square pixels. */
    public HeightTiff.Grid heights(Elevation.GridRequest grid) {
        double[] b = grid.bbox3857();
        String url = HEIGHTS_URL
                + "?bbox=" + encode(String.format(Locale.ROOT, "%.3f,%.3f,%.3f,%.3f", b[0], b[1], b[2], b[3]))
                + "&bboxSR=3857&imageSR=3857"
                + "&size=" + grid.width() + "," + grid.height()
                + "&format=tiff&pixelType=F32&noDataInterpretation=esriNoDataMatchAny"
                + "&interpolation=RSP_BilinearInterpolation&f=image";
        byte[] body = bytes(get(url));
        try {
            return HeightTiff.read(body);
        } catch (RuntimeException e) {
            // The service answers errors as JSON or HTML, not as an image
            throw new SourceDown("USGS did not send a height grid: " + firstLine(e.getMessage()), e);
        }
    }

    // ---- plumbing ------------------------------------------------------------------

    private static HttpRequest get(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
    }

    private JsonNode json(HttpRequest request) {
        byte[] body = bytes(request);
        try {
            return objectMapper.readTree(body);
        } catch (RuntimeException e) {
            throw new SourceDown("the answer was not JSON", e);
        }
    }

    private byte[] bytes(HttpRequest request) {
        String host = request.uri().getHost();
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new SourceDown(host + " answered " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceDown("stopped while waiting for " + host, e);
        } catch (IOException e) {
            throw new SourceDown(host + " could not be reached (" + firstLine(e.toString()) + ")", e);
        }
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    /** An error's first line. Some errors have no message at all; those give their type's name. */
    static String firstLine(String text) {
        if (text == null || text.isBlank()) {
            return "no details";
        }
        String line = text.strip().lines().findFirst().orElse("");
        return line.length() > 300 ? line.substring(0, 300) : line;
    }
}
