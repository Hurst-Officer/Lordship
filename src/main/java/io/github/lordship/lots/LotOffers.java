package io.github.lordship.lots;

import io.github.lordship.lots.internal.LotOffersRepository;
import io.github.lordship.lots.internal.LotOffersRepository.LotLineRow;
import io.github.lordship.lots.internal.LotOffersRepository.OfferRow;
import io.github.lordship.shared.AgreementType;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * What Glade's Rates and deals screens show about lots: which kinds of agreement each lot offers, at what rates,
 * and whether the lot is taken. Read-only. Changes go through LotService.
 *
 * <p>Taken: a tenancy on the lot has not ended (no end date, or an end date of today or later).
 * Open: the lot offers the kind of agreement, is rentable, and is not taken. An open lot is a vacancy.
 * A lot with no rate of the lot's own uses the rate of the property's terms template for that kind of agreement,
 * the same COALESCE(lot rate, terms_template rate) a charge term's rate is made from.
 *
 * <p>Every amount is text, like "650.00", so every digit reaches Glade.
 */
@Service
public class LotOffers {

    /**
     * One property's standing for one kind of agreement, for the list of every property.
     * hasDeal: the property has a terms template for the kind. offered counts the lots that offer the kind;
     * open and taken are among those. The averages run over every lot that offers the kind, and the low and high
     * asking rates over the open lots alone. An amount is null when no lot has a rate to count.
     */
    public record ParkLine(UUID propertyId, AgreementType agreementType, boolean hasDeal,
                           int offered, int open, int taken,
                           String askingAverage, String targetAverage,
                           String openAskingLow, String openAskingHigh) { }

    /**
     * One kind of agreement a lot offers. id is the lot_permissible_agreement_type row, which the history bars
     * are kept under. targetRate and askingRate are the lot's own rates; null means the lot has none of the lot's
     * own, and the property's standard rate applies.
     */
    public record Offer(UUID id, AgreementType agreementType, String targetRate, String askingRate) { }

    // One lot, with what the lot offers.
    // titleLink is the link the office set for the lot's name (in Properties), or null.
    // isRentable is a Boolean, like LotResponse's: JSON would name a primitive boolean's "is" accessor "rentable".
    public record LotLine(UUID id, UUID propertyId, String lotNumber, String lotAddress, String lotParcel,
                          String titleLink, Boolean isRentable, String notRentableReason, boolean taken,
                          List<Offer> offers) { }

    private final LotOffersRepository repository;

    public LotOffers(LotOffersRepository repository) {
        this.repository = repository;
    }

    // Every live property's standing, one line for each kind of agreement the property has a deal for or a lot offers
    public List<ParkLine> parkLines() {
        return repository.findParkLines().stream()
                .map(row -> new ParkLine(row.propertyId(), AgreementType.valueOf(row.agreementType()), row.hasDeal(),
                        row.offered(), row.open(), row.taken(),
                        text(row.askingAverage()), text(row.targetAverage()),
                        text(row.openAskingLow()), text(row.openAskingHigh())))
                .toList();
    }

    // One property's live lots in display order, each with what the lot offers
    public List<LotLine> lotsOf(UUID propertyId) {
        Map<UUID, List<OfferRow>> offersByLot = repository.findOffersByProperty(propertyId).stream()
                .collect(Collectors.groupingBy(OfferRow::lotId));
        return repository.findLotsByProperty(propertyId).stream()
                .map(row -> line(row, offersByLot.getOrDefault(row.id(), List.of())))
                .toList();
    }

    // One live lot with what the lot offers
    public Optional<LotLine> lot(UUID lotId) {
        return repository.findLot(lotId).map(row -> line(row, repository.findOffersByLot(lotId)));
    }

    // The property of the lot an offer row belongs to, for checking an agent's property scope. Empty for an unknown row.
    public Optional<UUID> propertyOfOffer(UUID offerId) {
        return repository.findPropertyOfOffer(offerId);
    }

    private static LotLine line(LotLineRow row, List<OfferRow> offers) {
        return new LotLine(row.id(), row.propertyId(), row.lotNumber(), row.lotAddress(), row.lotParcel(),
                row.titleLink(), row.isRentable(), row.notRentableReason(), row.taken(),
                offers.stream()
                        .map(offer -> new Offer(offer.id(), AgreementType.valueOf(offer.agreementType()),
                                text(offer.targetRate()), text(offer.askingRate())))
                        .toList());
    }

    // "650.00", or null for no amount
    private static String text(BigDecimal amount) {
        return amount == null ? null : amount.toPlainString();
    }
}
