package io.github.lordship.shared;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ChargeKindTest {

    @Test
    void fromDatabase_shouldReadAPostgresArray_inDeclarationOrder() {
        // The column keeps whatever order was written; a deal always reads in one order
        assertEquals(List.of(ChargeKind.RENT, ChargeKind.WATER, ChargeKind.LATE_FEE),
                ChargeKind.fromDatabase("{LATE_FEE,RENT,WATER}"));
        assertEquals(List.of(), ChargeKind.fromDatabase("{}"));
        assertEquals("{RENT,PET_FEE}", ChargeKind.toDatabase(List.of(ChargeKind.RENT, ChargeKind.PET_FEE)));
    }

    @Test
    void fromRequest_shouldReadNames_onceEach_inDeclarationOrder() {
        assertEquals(List.of(ChargeKind.RENT, ChargeKind.WATER),
                ChargeKind.fromRequest(List.of("water", "RENT", "rent"), "rent_kinds"));
    }

    @Test
    void fromRequest_shouldRefuseAnUnknownKind_andAValueThatIsNotAList() {
        InvalidRequest unknown = assertThrows(InvalidRequest.class,
                () -> ChargeKind.fromRequest(List.of("RENT", "GAS"), "rent_kinds"));
        assertEquals("value.not_a_choice", unknown.problem().code());
        assertEquals("rent_kinds", unknown.problem().field());

        InvalidRequest notAList = assertThrows(InvalidRequest.class,
                () -> ChargeKind.fromRequest("RENT", "rent_kinds"));
        assertEquals("charge_kind.not_a_list", notAList.problem().code());
    }

    @Test
    void leaseWords_shouldCallRentOnALotLeaseLotRent() {
        assertEquals("lot rent", ChargeKind.RENT.leaseWords(AgreementType.LAND));
        assertEquals("rent", ChargeKind.RENT.leaseWords(AgreementType.RESIDENTIAL));
        assertEquals("returned check fees", ChargeKind.NSF_FEE.leaseWords(AgreementType.LAND));
    }
}
