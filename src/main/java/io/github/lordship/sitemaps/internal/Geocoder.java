package io.github.lordship.sitemaps.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns typed text into a point on the map, for the builder's search box.
 *
 * <p>Tries, in order:
 * <ol>
 *   <li>a pasted "latitude, longitude", the way Google Maps copies it</li>
 *   <li>the US Census geocoder, for street addresses. Free, no key</li>
 *   <li>OpenStreetMap's Nominatim, for town names and landmarks</li>
 * </ol>
 * A service that fails or times out is skipped. Nothing found is an ordinary answer.
 */
@Component
public class Geocoder {

    private static final Logger log = LoggerFactory.getLogger(Geocoder.class);

    private static final Pattern LAT_LNG = Pattern.compile("^\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*$");
    private static final String CENSUS_URL = "https://geocoding.geo.census.gov/geocoder/locations/onelineaddress";
    private static final String NOMINATIM_URL = "https://nominatim.openstreetmap.org/search";
    // Nominatim asks every caller to name itself
    private static final String USER_AGENT = "Lordship map builder (property management)";
    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    /** A point found for the text. label says what was matched, such as the full address. */
    public record Place(double longitude, double latitude, String label) { }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final ObjectMapper objectMapper;

    public Geocoder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Optional<Place> find(String text) {
        String query = text == null ? "" : text.strip();
        if (query.isEmpty()) {
            return Optional.empty();
        }

        Optional<Place> typed = parseLatLng(query);
        if (typed.isPresent()) {
            return typed;
        }

        Optional<Place> address = census(query);
        if (address.isPresent()) {
            return address;
        }
        return nominatim(query);
    }

    // "47.1258, -122.5381" is latitude first, the way it is copied from Google Maps
    static Optional<Place> parseLatLng(String text) {
        Matcher m = LAT_LNG.matcher(text);
        if (!m.matches()) {
            return Optional.empty();
        }
        double lat = Double.parseDouble(m.group(1));
        double lng = Double.parseDouble(m.group(2));
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            return Optional.empty();
        }
        return Optional.of(new Place(lng, lat, "coordinates"));
    }

    private Optional<Place> census(String query) {
        JsonNode body = getJson(CENSUS_URL + "?address=" + encode(query)
                + "&benchmark=Public_AR_Current&format=json");
        if (body == null) {
            return Optional.empty();
        }
        JsonNode matches = body.path("result").path("addressMatches");
        if (!matches.isArray() || matches.isEmpty()) {
            return Optional.empty();
        }
        JsonNode first = matches.get(0);
        JsonNode point = first.path("coordinates");
        if (!point.has("x") || !point.has("y")) {
            return Optional.empty();
        }
        return Optional.of(new Place(point.get("x").asDouble(), point.get("y").asDouble(),
                first.path("matchedAddress").asString(query)));
    }

    private Optional<Place> nominatim(String query) {
        JsonNode body = getJson(NOMINATIM_URL + "?q=" + encode(query) + "&format=json&limit=1&countrycodes=us");
        if (body == null || !body.isArray() || body.isEmpty()) {
            return Optional.empty();
        }
        JsonNode first = body.get(0);
        try {
            return Optional.of(new Place(Double.parseDouble(first.path("lon").asString()),
                    Double.parseDouble(first.path("lat").asString()),
                    first.path("display_name").asString(query)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    // The body as JSON, or null when the service could not be asked or said no
    private JsonNode getJson(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            return objectMapper.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.info("Address lookup at {} failed: {}", url.substring(0, url.indexOf('?')), e.toString());
            return null;
        }
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }
}
