package io.github.lordship.instruments;

import io.github.lordship.audit.ActingAgent;
import io.github.lordship.audit.AuditContext;
import io.github.lordship.audit.AuditMapper;
import io.github.lordship.audit.AuditService;
import io.github.lordship.documentfiles.DocumentFile;
import io.github.lordship.documentfiles.DocumentFileService;
import io.github.lordship.documentfiles.StoredFile;
import io.github.lordship.instruments.internal.MoveInFileRepository;
import io.github.lordship.instruments.internal.MoveInFileRepository.MoveInFileLine;
import io.github.lordship.instruments.internal.MoveInFileRepository.MoveInFileRow;
import io.github.lordship.shared.InstrumentType;
import io.github.lordship.shared.InvalidRequest;
import io.github.lordship.shared.RuleConflict;
import io.github.lordship.tenancy.MoveInCheckItem;
import io.github.lordship.tenancy.MoveInChecks;
import io.github.lordship.tenancy.MoveInLine;
import io.github.lordship.tenancy.MoveIns;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The signed scan of a move-in's lease (Glade's Move-In, Signing stage): the park or the office uploads the signed
 * packet as one PDF, Lordship keeps the bytes (DocumentFileService: a local folder today, S3 later) under a storage
 * key that holds no name, names the file by the office's rule ("CD Lot 14 Lease Lopez.pdf", PaperName), and runs the
 * quick page check (PageCheck) at once. The page check's verdict goes to the office's list as the reading of
 * PAGES_IN_ORDER (MoveInChecks.recordReading), which decides that item.
 *
 * <p>The newest live scan of the lease is the one that counts; an earlier scan stays on file. Every read of a scan
 * goes through Lordship and is recorded (DocumentFileService.readFor). Every write is in the audit log.
 */
@Service
public class SignedScans {

    /** The kind of upload a signed scan is (move_in_file.kind). */
    public static final String KIND = "SIGNED_LEASE";

    // The page check is written to and read from move_in_file.page_check here, not by Spring's ObjectMapper bean
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final MoveIns moveIns;
    private final MoveInChecks moveInChecks;
    private final InstrumentService instrumentService;
    private final DocumentFileService documentFileService;
    private final MoveInFileRepository repository;
    private final AuditService auditService;
    private final AuditContext auditContext;

    public SignedScans(MoveIns moveIns, MoveInChecks moveInChecks, InstrumentService instrumentService,
                       DocumentFileService documentFileService, MoveInFileRepository repository,
                       AuditService auditService, AuditContext auditContext) {
        this.moveIns = moveIns;
        this.moveInChecks = moveInChecks;
        this.instrumentService = instrumentService;
        this.documentFileService = documentFileService;
        this.repository = repository;
        this.auditService = auditService;
        this.auditContext = auditContext;
    }

    /**
     * One signed scan as Glade shows the scan.
     *
     * @param lease     the lease the scan is a signed copy of
     * @param file      the stored file (document_file) the scan's bytes are
     * @param fileName  the name the scan is downloaded under ("CD Lot 14 Lease Lopez.pdf")
     * @param pages     how many pages the scan has
     * @param pageCheck what the page check read; null when the check could not run
     */
    public record SignedScan(UUID id, UUID tenancyId, UUID lease, UUID file, String fileName, long byteSize, Integer pages,
                             PageCheck.Reading pageCheck, OffsetDateTime createdAt, UUID createdBy,
                             String createdByName) { }

    /**
     * Takes the signed scan of a move-in's lease: stores the PDF, checks the pages, and records the page check on
     * the office's list. All in one transaction.
     *
     * <p>Empty for an unknown or deleted tenancy. 409 move_in.not_moving_in, move_in.finished, move_in.no_lease_to_sign
     * (the move-in has no generated lease to sign yet). 400 move_in.scan_not_pdf for an upload that is not a PDF
     * that opens, move_in.scan_empty for an upload with no pages.
     */
    @Transactional
    public Optional<SignedScan> upload(UUID tenancyId, byte[] content) {
        Optional<MoveInLine> found = moveIns.line(tenancyId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        MoveInLine line = found.get();
        if (line.deletedAt() != null) {
            return Optional.empty();
        }
        if (line.moveInDoneAt() != null) {
            throw RuleConflict.of("move_in.finished");
        }
        Instrument lease = line.leaseId() == null ? null : instrumentService.findById(line.leaseId()).orElse(null);
        if (lease == null || lease.serial() == null || lease.status() == InstrumentStatus.DRAFT
                || lease.status() == InstrumentStatus.ABANDONED) {
            throw RuleConflict.of("move_in.no_lease_to_sign");
        }

        int pages = pagesOf(content);
        PageCheck.Reading reading;
        try {
            reading = PageCheck.read(content, lease.serial(), generatedPages(lease));
        } catch (IOException e) {
            throw InvalidRequest.onField("file", "move_in.scan_not_pdf");
        }

        UUID agent = ActingAgent.resolve(auditContext);
        String fileName = PaperName.of(line.propertyCode(), line.lotNumber(), InstrumentType.LEASE,
                PaperName.Copy.SIGNED, line.tenants());
        // a storage key never holds a name (a renamed tenant would leave a name behind in storage)
        DocumentFile file = documentFileService.store("files/" + UUID.randomUUID() + ".pdf", fileName,
                "application/pdf", content, agent);

        MoveInFileRow saved = repository.insert(tenancyId, lease.uuid(), file.uuid(), KIND, pages,
                JSON.writeValueAsString(reading), agent);
        auditService.recordInsert("move_in_file", saved.uuid(), AuditMapper.toMap(saved));

        moveInChecks.recordReading(tenancyId, MoveInCheckItem.PAGES_IN_ORDER, reading.state(),
                JSON.writeValueAsString(reading));

        return repository.findById(saved.uuid()).map(SignedScans::scanOf);
    }

    /** A move-in's live signed scans, the newest first. */
    public List<SignedScan> of(UUID tenancyId) {
        return repository.findByTenancy(tenancyId, KIND).stream().map(SignedScans::scanOf).toList();
    }

    /** The newest live signed scan of one lease of a move-in. */
    public Optional<SignedScan> latest(UUID tenancyId, UUID leaseId) {
        return of(tenancyId).stream().filter(scan -> leaseId.equals(scan.lease())).findFirst();
    }

    /**
     * A scan's bytes, handed to the agent asking: the read is recorded (who and when). Empty for an unknown scan,
     * a deleted one, and one of another tenancy.
     */
    @Transactional
    public Optional<StoredFile> read(UUID tenancyId, UUID scanId) {
        return repository.findById(scanId)
                .filter(scan -> scan.tenancyId().equals(tenancyId) && scan.deletedAt() == null)
                .flatMap(scan -> documentFileService.readFor(scan.file(), ActingAgent.resolve(auditContext)));
    }

    /** The tenancy an upload belongs to (for the history bars' scope), deleted or not. */
    public Optional<UUID> tenancyOfFile(UUID fileId) {
        return repository.findById(fileId).map(MoveInFileLine::tenancyId);
    }

    // ---- internals -----------------------------------------------------------------------------

    // The pages of an uploaded PDF. 400 for an upload that is not a PDF that opens, or has no pages.
    private static int pagesOf(byte[] content) {
        if (content == null || content.length == 0) {
            throw InvalidRequest.onField("file", "move_in.scan_empty");
        }
        try (PDDocument document = Loader.loadPDF(content)) {
            int pages = document.getNumberOfPages();
            if (pages == 0) {
                throw InvalidRequest.onField("file", "move_in.scan_empty");
            }
            return pages;
        } catch (IOException e) {
            throw InvalidRequest.onField("file", "move_in.scan_not_pdf");
        }
    }

    // How many pages the generated lease has, or 0 when the generated file cannot be read
    private int generatedPages(Instrument lease) {
        return instrumentService.readGeneratedFile(lease.uuid())
                .map(stored -> {
                    try (PDDocument document = Loader.loadPDF(stored.content())) {
                        return document.getNumberOfPages();
                    } catch (IOException e) {
                        return 0;
                    }
                })
                .orElse(0);
    }

    private static SignedScan scanOf(MoveInFileLine line) {
        PageCheck.Reading reading = null;
        if (line.pageCheck() != null) {
            reading = JSON.readValue(line.pageCheck(), PageCheck.Reading.class);
        }
        return new SignedScan(line.uuid(), line.tenancyId(), line.instrument(), line.file(), line.fileName(), line.byteSize(),
                line.pages(), reading, line.createdAt(), line.createdBy(), line.createdByName());
    }
}
