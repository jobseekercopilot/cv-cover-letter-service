package com.jobseekercopilot.cvcoverletter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.jobseekercopilot.cvcoverletter.dto.ClaimDisposition;
import com.jobseekercopilot.cvcoverletter.dto.GeneratedClaim;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ValidatedClaimLedgerFactoryTest {

    private final ValidatedClaimLedgerFactory factory =
            new ValidatedClaimLedgerFactory();

    @Test
    void createsAStableLedgerForTheSameOperationAndClaims() {
        UUID operationId =
                UUID.fromString("10000000-0000-4000-8000-000000000001");

        var first = factory.create(operationId, List.of(claim("Focused review")));
        var replay = factory.create(operationId, List.of(claim("Focused review")));

        assertEquals(first.ledgerId(), replay.ledgerId());
        assertEquals(first.ledgerSha256(), replay.ledgerSha256());
        assertEquals("2.10.0", first.policyVersion());
        assertEquals("3.2.0", first.parserVersion());
        assertEquals(64, first.ledgerSha256().length());
    }

    @Test
    void changesTheLedgerIdentityWhenAnExactClaimFieldChanges() {
        UUID operationId =
                UUID.fromString("10000000-0000-4000-8000-000000000001");

        var original =
                factory.create(operationId, List.of(claim("Focused review")));
        var changed =
                factory.create(operationId, List.of(claim("Different review")));

        assertNotEquals(original.ledgerId(), changed.ledgerId());
        assertNotEquals(original.ledgerSha256(), changed.ledgerSha256());
    }

    private GeneratedClaim claim(String reviewText) {
        GeneratedClaim claim = new GeneratedClaim();
        claim.setClaimId("CLAIM-001");
        claim.setDisposition(ClaimDisposition.SUPPORTED);
        claim.setEvidenceIds(List.of(
                "50000000-0000-4000-8000-000000000001"));
        claim.setContentPaths(List.of("/cv/profile/summary"));
        claim.setReviewText(reviewText);
        return claim;
    }
}
