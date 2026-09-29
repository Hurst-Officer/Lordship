package io.github.lordship.properties;

import io.github.lordship.audit.AuditMapper;
import io.github.lordship.audit.AuditService;
import io.github.lordship.properties.internal.PropertyRow;
import io.github.lordship.properties.internal.PropertyRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.*;
import java.time.LocalDate;
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

    @Transactional
    public Optional<Property> patchProperty(UUID uuid, Map<String, Object> changes) {

        if (changes.containsKey("purchaseDate")) {
            Object pd = changes.get("purchaseDate");
            if (pd instanceof String s && !s.isBlank()) {
                changes.put("purchase_date", LocalDate.parse(s));
            } else {
                changes.put("purchase_date", null);
            }
            changes.remove("purchaseDate");
        }

        if (changes.containsKey("propertyManager")) {
            Object pm = changes.get("propertyManager");
            if (pm instanceof String s && !s.isBlank()) {
                changes.put("property_manager", UUID.fromString(s));
            } else {
                changes.put("property_manager", null);
            }
            changes.remove("propertyManager");
        }

        PropertyRow before = propertyRepository.findById(uuid).orElse(null);
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