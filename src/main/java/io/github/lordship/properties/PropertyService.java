package io.github.lordship.properties;

import io.github.lordship.audit.AuditMapper;
import io.github.lordship.audit.AuditService;
import io.github.lordship.properties.internal.PropertyRow;
import io.github.lordship.properties.internal.PropertyRepository;
import io.github.lordship.propertyassignments.PropertyAssignmentService;
import io.github.lordship.shared.InvalidRequest;
import io.github.lordship.shared.PropertyScope;
import io.github.lordship.shared.RuleConflict;
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
    private final PropertyAssignmentService propertyAssignmentService;

    public PropertyService(PropertyRepository propertyRepository, AuditService auditService,
                           PropertyAssignmentService propertyAssignmentService) {
        this.propertyRepository = propertyRepository;
        this.auditService = auditService;
        this.propertyAssignmentService = propertyAssignmentService;
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


    // A new property made by an agent. An agent who only sees their assigned properties
    // is assigned to the new one, in the same transaction, so they can open what they just made.
    @Transactional
    public Property createProperty(String propertyName, String propertyStreet, String propertyCity,
                                   String propertyState, String propertyZip,
                                   UUID creatorId, PropertyScope creatorScope) {
        Property created = createProperty(propertyName, propertyStreet, propertyCity, propertyState, propertyZip);
        if (!creatorScope.isAll()) {
            propertyAssignmentService.assign(creatorId, created.uuid(), creatorId);
        }
        return created;
    }

    public Optional<Property> findByPropertyId(UUID propertyCode) {
        return propertyRepository.findById(propertyCode).map(PropertyRow::toProperty);
    }

    public List<Property> findAll() {
        return propertyRepository.findAll().stream()
                .map(PropertyRow::toProperty)
                .toList();
    }

    // Every live property this scope may see
    public List<Property> findAll(PropertyScope scope) {
        return findAll().stream().filter(property -> scope.allows(property.uuid())).toList();
    }

    // Columns that can carry a link and a note. Custom field labels can too.
    // business_numbers is not here because each number carries its own link and note.
    private static final Set<String> FIELD_COLUMNS = Set.of(
            "property_name", "property_street", "property_city", "property_state",
            "property_zip", "property_county", "purchase_date", "property_zoning", "property_parcels",
            "acres", "payable_to", "remittance_address", "year_built"
    );

    @Transactional
    public Optional<Property> patchProperty(UUID uuid, Map<String, Object> changes) {

        // locked, so a coworker's save waits for this one and both are logged right
        PropertyRow before = propertyRepository.findByIdForUpdate(uuid).orElse(null);

        // A code belongs to one property for good, even after that property is deleted
        if (before != null && changes.get("property_code") instanceof String code
                && !code.equalsIgnoreCase(before.propertyCode())
                && propertyRepository.isCodeUsedByAnother(code.trim(), uuid)) {
            throw RuleConflict.of("property.code_in_use", code.trim());
        }

        // A blank link clears it. Anything else must be a web address.
        if (changes.containsKey("title_link")) {
            Object raw = changes.get("title_link");
            if (raw != null && !(raw instanceof String)) {
                throw InvalidRequest.onField("title_link", "property.link_not_a_url");
            }
            changes = new HashMap<>(changes);
            changes.put("title_link", checkedLink("title_link", (String) raw));
        }

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
        return setFieldText(uuid, field, checkedLink("url", url), propertyRepository::setFieldLink);
    }

    // Returns the trimmed link, or null when it is blank.
    // Throws when it is not an http or https address. inputName is the field named in the error.
    private static String checkedLink(String inputName, String url) {
        String link = blankToNull(url);
        if (link != null) {
            String lower = link.toLowerCase(Locale.ROOT);
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                throw InvalidRequest.onField(inputName, "property.link_not_a_url");
            }
        }
        return link;
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
        PropertyRow before = propertyRepository.findByIdForUpdate(uuid).orElse(null);
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

    // ---- custom fields and business numbers, one at a time ---------------------------------
    // Each change is applied to the newest copy of the list, under the row lock.
    // So when two people change different entries at once, both changes are kept.
    // An entry a coworker already changed or removed is refused (409), so nothing is overwritten.

    // Adds one custom field. Refused when the property already has one with that label.
    @Transactional
    public Optional<Property> addCustomField(UUID uuid, String label, Object value) {
        String name = customLabel(label);
        PropertyRow before = propertyRepository.findByIdForUpdate(uuid).orElse(null);
        if (before == null) {
            return Optional.empty();
        }
        if (before.customFields().containsKey(name)) {
            throw RuleConflict.of("property.custom_field_exists", name);
        }
        Map<String, Object> fields = new LinkedHashMap<>(before.customFields());
        fields.put(name, value);
        return patchProperty(uuid, new HashMap<>(Map.of("custom_fields", fields)));
    }

    // Changes the value of one custom field. Refused when a coworker removed it meanwhile.
    @Transactional
    public Optional<Property> changeCustomField(UUID uuid, String label, Object value) {
        String name = customLabel(label);
        PropertyRow before = propertyRepository.findByIdForUpdate(uuid).orElse(null);
        if (before == null) {
            return Optional.empty();
        }
        if (!before.customFields().containsKey(name)) {
            throw RuleConflict.of("property.custom_field_gone", name);
        }
        Map<String, Object> fields = new LinkedHashMap<>(before.customFields());
        fields.put(name, value);
        return patchProperty(uuid, new HashMap<>(Map.of("custom_fields", fields)));
    }

    // Removes one custom field, with its link and note. One that is already gone is fine.
    @Transactional
    public Optional<Property> removeCustomField(UUID uuid, String label) {
        String name = customLabel(label);
        PropertyRow before = propertyRepository.findByIdForUpdate(uuid).orElse(null);
        if (before == null) {
            return Optional.empty();
        }
        if (!before.customFields().containsKey(name)) {
            return Optional.of(before.toProperty());
        }
        Map<String, Object> fields = new LinkedHashMap<>(before.customFields());
        fields.remove(name);
        // patchProperty drops the link and note of a field that is gone
        return patchProperty(uuid, new HashMap<>(Map.of("custom_fields", fields)));
    }

    // A trimmed label. It cannot be blank, or a built-in field's name (they would share a link and note).
    private static String customLabel(String label) {
        String name = blankToNull(label);
        if (name == null) {
            throw InvalidRequest.onField("label", "property.custom_field_label_blank");
        }
        if (FIELD_COLUMNS.contains(name)) {
            throw InvalidRequest.onField("label", "property.custom_field_label_reserved", name);
        }
        return name;
    }

    // Adds one business number. Refused when the property already has the same number.
    @Transactional
    public Optional<Property> addBusinessNumber(UUID uuid, String state, String type, String number) {
        BusinessNumber added = new BusinessNumber(cleanState(state), cleanText(type), cleanText(number), null, null);
        if (added.number().isEmpty()) {
            throw InvalidRequest.onField("number", "property.number_blank");
        }
        PropertyRow before = propertyRepository.findByIdForUpdate(uuid).orElse(null);
        if (before == null) {
            return Optional.empty();
        }
        if (indexOf(before.businessNumbers(), added.state(), added.type(), added.number()) >= 0) {
            throw RuleConflict.of("property.number_exists", added.state(), added.type(), added.number());
        }
        List<BusinessNumber> numbers = new ArrayList<>(before.businessNumbers());
        numbers.add(added);
        return patchProperty(uuid, new HashMap<>(Map.of("business_numbers", numbers)));
    }

    /**
     * Changes one business number. state, type and number say which one, as the worker last saw it.
     * changes holds the new values, by name: state, type, number, link, note. A blank link or note clears it.
     * Refused when no number matches anymore, because a coworker changed or removed it (409).
     */
    @Transactional
    public Optional<Property> changeBusinessNumber(UUID uuid, String state, String type, String number,
                                                   Map<String, Object> changes) {
        PropertyRow before = propertyRepository.findByIdForUpdate(uuid).orElse(null);
        if (before == null) {
            return Optional.empty();
        }
        List<BusinessNumber> numbers = new ArrayList<>(before.businessNumbers());
        int at = indexOf(numbers, cleanState(state), cleanText(type), cleanText(number));
        if (at < 0) {
            throw RuleConflict.of("property.number_changed");
        }

        BusinessNumber old = numbers.get(at);
        BusinessNumber changed = new BusinessNumber(
                changes.containsKey("state") ? cleanState(textOf(changes.get("state"))) : old.state(),
                changes.containsKey("type") ? cleanText(textOf(changes.get("type"))) : old.type(),
                changes.containsKey("number") ? cleanText(textOf(changes.get("number"))) : old.number(),
                changes.containsKey("link") ? checkedLink("link", textOf(changes.get("link"))) : old.link(),
                changes.containsKey("note") ? blankToNull(textOf(changes.get("note"))) : old.note());
        if (changed.number().isEmpty()) {
            throw InvalidRequest.onField("number", "property.number_blank");
        }
        int same = indexOf(numbers, changed.state(), changed.type(), changed.number());
        if (same >= 0 && same != at) {
            throw RuleConflict.of("property.number_exists", changed.state(), changed.type(), changed.number());
        }

        numbers.set(at, changed);
        return patchProperty(uuid, new HashMap<>(Map.of("business_numbers", numbers)));
    }

    // Removes one business number. One that is already gone is fine.
    @Transactional
    public Optional<Property> removeBusinessNumber(UUID uuid, String state, String type, String number) {
        PropertyRow before = propertyRepository.findByIdForUpdate(uuid).orElse(null);
        if (before == null) {
            return Optional.empty();
        }
        List<BusinessNumber> numbers = new ArrayList<>(before.businessNumbers());
        int at = indexOf(numbers, cleanState(state), cleanText(type), cleanText(number));
        if (at < 0) {
            return Optional.of(before.toProperty());
        }
        numbers.remove(at);
        return patchProperty(uuid, new HashMap<>(Map.of("business_numbers", numbers)));
    }

    // Where the number with this state, type and number is in the list, or -1
    private static int indexOf(List<BusinessNumber> numbers, String state, String type, String number) {
        for (int i = 0; i < numbers.size(); i++) {
            BusinessNumber n = numbers.get(i);
            if (cleanState(n.state()).equals(state) && cleanText(n.type()).equals(type)
                    && cleanText(n.number()).equals(number)) {
                return i;
            }
        }
        return -1;
    }

    // "wa " -> "WA". Null is "".
    private static String cleanState(String state) {
        return cleanText(state).toUpperCase(Locale.ROOT);
    }

    // Trimmed. Null is "".
    private static String cleanText(String text) {
        return text == null ? "" : text.trim();
    }

    private static String textOf(Object value) {
        return value == null ? null : value.toString();
    }

    // Refused while any of its lots has an active tenancy (409).
    // The lots are left alone and hidden with the property.
    @Transactional
    public boolean deleteProperty(UUID uuid) {
        PropertyRow before = propertyRepository.findById(uuid).orElse(null);
        if (before == null) {
            return false;
        }
        int tenancies = propertyRepository.countActiveTenancies(uuid);
        if (tenancies > 0) {
            throw RuleConflict.of("property.has_tenancies", tenancies);
        }
        boolean deleted = propertyRepository.softDelete(uuid);
        if (deleted && before != null) {
            auditService.recordDelete("property", uuid, AuditMapper.toMap(before));
        }
        return deleted;
    }

    // Deleted properties, the most recently deleted first
    public List<Property> findDeleted() {
        return propertyRepository.findDeleted().stream()
                .map(PropertyRow::toProperty)
                .toList();
    }

    // Deleted properties this scope may see
    public List<Property> findDeleted(PropertyScope scope) {
        return findDeleted().stream().filter(property -> scope.allows(property.uuid())).toList();
    }

    // Brings a deleted property back. Its lots come back with it,
    // except lots that were deleted on their own before.
    // A property that is not deleted is returned as it is. Empty when there is no such property.
    // Refused when a live property took its code in the meantime.
    @Transactional
    public Optional<Property> restoreProperty(UUID uuid) {
        PropertyRow before = propertyRepository.findAnyById(uuid).orElse(null);
        if (before == null) {
            return Optional.empty();
        }
        if (before.deletedAt() == null) {
            return Optional.of(before.toProperty());
        }

        String code = before.propertyCode();
        if (code != null && propertyRepository.isCodeInUse(code)) {
            throw RuleConflict.of("property.code_taken", code);
        }

        Optional<PropertyRow> result = propertyRepository.restore(uuid);
        result.ifPresent(after -> {
            AuditMapper.Diff diff = AuditMapper.diff(before, after);
            auditService.recordUpdate("property", uuid, diff.before(), diff.after());
        });
        // a coworker restored it a moment earlier
        if (result.isEmpty()) {
            return findByPropertyId(uuid);
        }
        return result.map(PropertyRow::toProperty);
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
            throw InvalidRequest.onField("property_name", "property.name_needs_letters");
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