package io.github.lordship.properties;

import io.github.lordship.audit.AuditMapper;
import io.github.lordship.audit.AuditService;
import io.github.lordship.properties.internal.PropertyRow;
import io.github.lordship.properties.internal.PropertyRepository;
import io.github.lordship.shared.InvalidRequest;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Service
public class PropertyService {
    private final PropertyRepository propertyRepository;
    private final AuditService auditService;

    public PropertyService(PropertyRepository propertyRepository, AuditService auditService) {
        this.propertyRepository = propertyRepository;
        this.auditService = auditService;
    }
//
    @Transactional
    public Property createProperty(String propertyName, String propertyStreet, String propertyCity,
                                   String propertyState, String propertyZip) {
        Set<String> usedCodes = propertyRepository.findUsedPropertyCodes();

        String propertyCode = generatePropertyCode(propertyName, usedCodes);

        PropertyRow saved = propertyRepository.save(
                propertyName, propertyStreet, propertyCity, propertyState, propertyZip, propertyCode);
        auditService.recordInsert("property", saved.uuid(), AuditMapper.toMap(saved));
        return saved.toProperty();
    }


    public Optional<Property> findByPropertyId(UUID propertyCode) {
        return propertyRepository.findById(propertyCode).map(PropertyRow::toProperty);
    }

    public List<Property> findAll() {
        return propertyRepository.findAll().stream()
                .map(PropertyRow::toProperty)
                .toList();
    }

    // Columns that can carry a link and a note. Custom field labels can too.
    // business_numbers is not here because each number carries its own link and note.
    private static final Set<String> FIELD_COLUMNS = Set.of(
            "property_code", "property_name", "property_street", "property_city", "property_state",
            "property_zip", "property_county", "purchase_date", "property_zoning", "property_parcels",
            "acres", "payable_to", "remittance_address", "year_built"
    );

    @Transactional
    public Optional<Property> patchProperty(UUID uuid, Map<String, Object> changes) {

        PropertyRow before = propertyRepository.findById(uuid).orElse(null);

        // A custom field that was removed takes its link and note with it
        if (before != null && changes.containsKey("custom_fields")) {
            Object newCustomFields = changes.get("custom_fields");
            changes = new HashMap<>(changes);
            changes.put("field_links", stillInUse(before.fieldLinks(), newCustomFields));
            changes.put("field_notes", stillInUse(before.fieldNotes(), newCustomFields));
        }

        Optional<PropertyRow> result = propertyRepository.patch(uuid, changes);
        result.ifPresent(after -> {
            if (before != null) {
                AuditMapper.Diff diff = AuditMapper.diff(before, after);
                if (!diff.before().isEmpty()) {
                    auditService.recordUpdate("property", uuid, diff.before(), diff.after());
                }
            }
        });
        return result.map(PropertyRow::toProperty);
    }

    // Sets the link that goes with one field. A null or blank url clears it.
    // field is a column name like "property_zoning", or a custom field label.
    @Transactional
    public Optional<Property> setFieldLink(UUID uuid, String field, String url) {
        String link = blankToNull(url);
        if (link != null) {
            String lower = link.toLowerCase(Locale.ROOT);
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                throw InvalidRequest.onField("url", "property.link_not_a_url");
            }
        }
        return setFieldText(uuid, field, link, propertyRepository::setFieldLink);
    }

    // Sets the note that goes with one field. A null or blank note clears it.
    @Transactional
    public Optional<Property> setFieldNote(UUID uuid, String field, String note) {
        return setFieldText(uuid, field, blankToNull(note), propertyRepository::setFieldNote);
    }

    // Writes one link or note, after checking the field exists on this property
    private interface FieldTextWriter {
        Optional<PropertyRow> write(UUID uuid, String field, String text);
    }

    private Optional<Property> setFieldText(UUID uuid, String field, String text, FieldTextWriter writer) {
        PropertyRow before = propertyRepository.findById(uuid).orElse(null);
        if (before == null) {
            return Optional.empty();
        }

        boolean knownField = field != null
                && (FIELD_COLUMNS.contains(field) || before.customFields().containsKey(field));
        if (!knownField) {
            throw InvalidRequest.onField("field", "property.field_unknown", field);
        }

        Optional<PropertyRow> result = writer.write(uuid, field, text);
        result.ifPresent(after -> {
            AuditMapper.Diff diff = AuditMapper.diff(before, after);
            if (!diff.before().isEmpty()) {
                auditService.recordUpdate("property", uuid, diff.before(), diff.after());
            }
        });
        return result.map(PropertyRow::toProperty);
    }

    private static String blankToNull(String text) {
        return (text == null || text.isBlank()) ? null : text.trim();
    }

    // Keeps entries for columns, and for custom fields that still exist.
    private static Map<String, String> stillInUse(Map<String, String> entries, Object newCustomFields) {
        Map<String, String> kept = new LinkedHashMap<>();
        entries.forEach((field, text) -> {
            boolean stillCustom = newCustomFields instanceof Map<?, ?> custom && custom.containsKey(field);
            if (FIELD_COLUMNS.contains(field) || stillCustom) {
                kept.put(field, text);
            }
        });
        return kept;
    }

    @Transactional
    public boolean deleteProperty(UUID uuid) {
        PropertyRow before = propertyRepository.findById(uuid).orElse(null);
        boolean deleted = propertyRepository.softDelete(uuid);
        if (deleted && before != null) {
            auditService.recordDelete("property", uuid, AuditMapper.toMap(before));
        }
        return deleted;
    }

    // code for making better setting the initial property code
    private static final Pattern NON_LETTERS = Pattern.compile("[^A-Z]");

    private static List<String> lettersByWord(String propertyName) {
        if (propertyName == null || propertyName.isBlank()) {
            return List.of();
        }
        String normalized = Normalizer.normalize(propertyName, Normalizer.Form.NFD)
                .toUpperCase(Locale.ROOT);
        return Arrays.stream(normalized.split("\\s+"))
                .map(word -> NON_LETTERS.matcher(word).replaceAll(""))
                .filter(word -> !word.isEmpty())
                .toList();
    }

    private static Stream<String> candidateCodes(List<String> words) {
        char lead = words.getFirst().charAt(0);
        String firstWord = words.getFirst();

        Stream<String> otherWordInitials = words.stream().skip(1)
                .map(word -> "" + lead + word.charAt(0));
        Stream<String> ownLetters = firstWord.chars().skip(1)
                .mapToObj(c -> "" + lead + (char) c);
        Stream<String> alphabet = IntStream.rangeClosed('A', 'Z')
                .mapToObj(c -> "" + lead + (char) c);
        Stream<String> numbered = IntStream.rangeClosed(1, 999)
                .mapToObj(n -> lead + String.valueOf(n));

        return Stream.of(otherWordInitials, ownLetters, alphabet, numbered)
                .flatMap(Function.identity())
                .distinct();
    }

    private String generatePropertyCode(String propertyName, Set<String> usedCodes) {
        List<String> words = lettersByWord(propertyName);
        if (words.isEmpty()) {
            throw new IllegalArgumentException("Property name must contain letters: " + propertyName);
        }
        Set<String> taken = usedCodes.stream()
                .map(code -> code.toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());

        return candidateCodes(words)
                .filter(code -> !taken.contains(code))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Unable to generate a unique property code for: " + propertyName));
    }
}