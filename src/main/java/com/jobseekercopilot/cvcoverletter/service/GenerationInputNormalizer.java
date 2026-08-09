package com.jobseekercopilot.cvcoverletter.service;

import com.jobseekercopilot.cvcoverletter.dto.AdvertiserType;
import com.jobseekercopilot.cvcoverletter.dto.ContactDetails;
import com.jobseekercopilot.cvcoverletter.dto.ContactInputSnapshot;
import com.jobseekercopilot.cvcoverletter.dto.DraftOutputType;
import com.jobseekercopilot.cvcoverletter.dto.EmploymentInput;
import com.jobseekercopilot.cvcoverletter.dto.EvidenceSnapshotFactInput;
import com.jobseekercopilot.cvcoverletter.dto.EvidenceSnapshotInput;
import com.jobseekercopilot.cvcoverletter.dto.EvidenceSnapshotPurpose;
import com.jobseekercopilot.cvcoverletter.dto.EvidenceSnapshotSelectionInput;
import com.jobseekercopilot.cvcoverletter.dto.EvidenceSnapshotsInput;
import com.jobseekercopilot.cvcoverletter.dto.GenerateRequest;
import com.jobseekercopilot.cvcoverletter.dto.InputSourceOwner;
import com.jobseekercopilot.cvcoverletter.dto.InputWarning;
import com.jobseekercopilot.cvcoverletter.dto.JobInputSnapshot;
import com.jobseekercopilot.cvcoverletter.dto.JobDescriptionCompleteness;
import com.jobseekercopilot.cvcoverletter.dto.ProfileInputSnapshot;
import com.jobseekercopilot.cvcoverletter.dto.QualificationInput;
import com.jobseekercopilot.cvcoverletter.dto.QualificationStatus;
import com.jobseekercopilot.cvcoverletter.dto.RoleStatus;
import com.jobseekercopilot.cvcoverletter.dto.SelectedDraftGenerationRequest;
import com.jobseekercopilot.cvcoverletter.dto.SnapshotProvenance;
import com.jobseekercopilot.cvcoverletter.exception.InvalidGenerationInputException;
import com.jobseekercopilot.cvcoverletter.model.NormalizedGenerationInput;
import com.jobseekercopilot.cvcoverletter.model.NormalizedGenerationInput.PromptEmployment;
import com.jobseekercopilot.cvcoverletter.model.NormalizedGenerationInput.PromptEvidenceFact;
import com.jobseekercopilot.cvcoverletter.model.NormalizedGenerationInput.PromptEvidenceSelection;
import com.jobseekercopilot.cvcoverletter.model.NormalizedGenerationInput.PromptEvidenceSnapshot;
import com.jobseekercopilot.cvcoverletter.model.NormalizedGenerationInput.PromptEvidenceSnapshots;
import com.jobseekercopilot.cvcoverletter.model.NormalizedGenerationInput.PromptJob;
import com.jobseekercopilot.cvcoverletter.model.NormalizedGenerationInput.PromptProfile;
import com.jobseekercopilot.cvcoverletter.model.NormalizedGenerationInput.PromptQualification;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class GenerationInputNormalizer {

    static final String LEGACY_INPUT_SCHEMA_VERSION = "1.0";
    static final String EVIDENCE_INPUT_SCHEMA_VERSION = "2.0";
    static final int MAX_NORMALIZED_PROMPT_CHARACTERS = 40_000;

    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);
    private static final Pattern ACTIVE_MARKUP =
            Pattern.compile("(?is)<(script|style)\\b[^>]*>.*?(?:</\\1\\s*>|\\z)");
    private static final Pattern HTML_TAG = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern HTML_ENTITY =
            Pattern.compile("&#(?<decimal>[0-9]{1,7});|&#x(?<hex>[0-9a-fA-F]{1,6});");
    private static final Pattern CONTROL =
            Pattern.compile("[\\p{Cc}&&[^\\r\\n\\t]]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern OPAQUE_REFERENCE =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}");
    private static final Pattern SHA256 = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern FACT_TYPE =
            Pattern.compile("[A-Z][A-Z0-9_]{0,79}");

    private final Clock clock;

    public GenerationInputNormalizer() {
        this(Clock.systemUTC());
    }

    GenerationInputNormalizer(Clock clock) {
        this.clock = clock;
    }

    public NormalizedGenerationInput normalize(String ownerId, GenerateRequest request) {
        if (ownerId == null || ownerId.isBlank()) {
            throw invalid("owner", "trusted owner is required");
        }
        if (request == null
                || (!LEGACY_INPUT_SCHEMA_VERSION.equals(
                                request.getInputSchemaVersion())
                        && !EVIDENCE_INPUT_SCHEMA_VERSION.equals(
                                request.getInputSchemaVersion()))) {
            throw invalid("inputSchemaVersion", "unsupported input schema version");
        }

        List<InputWarning> warnings = new ArrayList<>();
        ProfileInputSnapshot profile = require(request.getProfile(), "profile");
        JobInputSnapshot job = require(request.getJob(), "job");
        SnapshotProvenance profileProvenance = normalizeProvenance(
                profile.getProvenance(), InputSourceOwner.USER_PROFILE_SERVICE, "profile.provenance");
        SnapshotProvenance jobProvenance = normalizeProvenance(
                job.getProvenance(), InputSourceOwner.JOB_SERVICE, "job.provenance");
        ContactInputSnapshot contactInput = profile.getContact();
        SnapshotProvenance contactProvenance = contactInput == null
                ? null
                : normalizeProvenance(
                        contactInput.getProvenance(),
                        InputSourceOwner.AUTHENTICATION_SERVICE,
                        "profile.contact.provenance");

        ContactDetails contact = normalizeContact(profile, warnings);
        PromptProfile promptProfile = normalizeProfile(profile, warnings);
        PromptJob promptJob = normalizeJob(job, warnings);
        PromptEvidenceSnapshots evidenceSnapshots =
                EVIDENCE_INPUT_SCHEMA_VERSION.equals(
                                request.getInputSchemaVersion())
                        ? normalizeEvidenceSnapshots(
                                profile,
                                profileProvenance,
                                request.getEvidenceSnapshots(),
                                warnings)
                        : null;
        enforceTotalLimit(
                promptProfile,
                promptJob,
                evidenceSnapshots);

        return new NormalizedGenerationInput(
                ownerId.trim(),
                request.getInputSchemaVersion(),
                profileProvenance,
                contactProvenance,
                jobProvenance,
                contact,
                promptProfile,
                promptJob,
                evidenceSnapshots,
                List.copyOf(warnings));
    }

    public NormalizedGenerationInput normalizeSelected(
            String ownerId,
            DraftOutputType outputType,
            SelectedDraftGenerationRequest request) {
        if (ownerId == null || ownerId.isBlank()) {
            throw invalid("owner", "trusted owner is required");
        }
        if (outputType == null) {
            throw invalid("outputType", "selected output is required");
        }
        if (request == null
                || !EVIDENCE_INPUT_SCHEMA_VERSION.equals(
                        request.getInputSchemaVersion())) {
            throw invalid(
                    "inputSchemaVersion",
                    "selected generation requires schema 2.0");
        }

        List<InputWarning> warnings = new ArrayList<>();
        ProfileInputSnapshot profile = require(request.getProfile(), "profile");
        JobInputSnapshot job = require(request.getJob(), "job");
        SnapshotProvenance profileProvenance = normalizeProvenance(
                profile.getProvenance(),
                InputSourceOwner.USER_PROFILE_SERVICE,
                "profile.provenance");
        SnapshotProvenance jobProvenance = normalizeProvenance(
                job.getProvenance(),
                InputSourceOwner.JOB_SERVICE,
                "job.provenance");
        ContactInputSnapshot contactInput = profile.getContact();
        SnapshotProvenance contactProvenance = contactInput == null
                ? null
                : normalizeProvenance(
                        contactInput.getProvenance(),
                        InputSourceOwner.AUTHENTICATION_SERVICE,
                        "profile.contact.provenance");

        if (!safe(profile.getQualifications()).isEmpty()
                || !safe(profile.getEmploymentHistory()).isEmpty()) {
            throw invalid(
                    "profile",
                    "schema 2.0 does not accept browser-positioned qualifications or employment history");
        }
        EvidenceSnapshotPurpose purpose = outputType == DraftOutputType.CV
                ? EvidenceSnapshotPurpose.CV
                : EvidenceSnapshotPurpose.COVER_LETTER;
        PromptEvidenceSnapshot selected = normalizeEvidenceSnapshot(
                require(request.getEvidenceSnapshot(), "evidenceSnapshot"),
                purpose,
                "evidenceSnapshot",
                warnings);
        String expectedRevision = profileProvenance.getResourceId();
        String expectedVersion = "sha256:" + selected.profileContentDigest();
        if (!selected.profileRevisionId().toString().equals(expectedRevision)
                || !expectedVersion.equals(profileProvenance.getVersion())) {
            throw invalid(
                    "evidenceSnapshot",
                    "snapshot must bind to the exact supplied profile revision and digest");
        }
        PromptEvidenceSnapshots evidenceSnapshots = outputType == DraftOutputType.CV
                ? new PromptEvidenceSnapshots(selected, null)
                : new PromptEvidenceSnapshots(null, selected);
        ContactDetails contact = normalizeContact(profile, warnings);
        PromptProfile promptProfile = normalizeProfile(profile, warnings);
        PromptJob promptJob = normalizeJob(job, warnings);
        enforceTotalLimit(promptProfile, promptJob, evidenceSnapshots);

        return new NormalizedGenerationInput(
                ownerId.trim(),
                request.getInputSchemaVersion(),
                profileProvenance,
                contactProvenance,
                jobProvenance,
                contact,
                promptProfile,
                promptJob,
                evidenceSnapshots,
                List.copyOf(warnings));
    }

    private PromptEvidenceSnapshots normalizeEvidenceSnapshots(
            ProfileInputSnapshot profile,
            SnapshotProvenance profileProvenance,
            EvidenceSnapshotsInput source,
            List<InputWarning> warnings) {
        require(source, "evidenceSnapshots");
        if (!safe(profile.getQualifications()).isEmpty()
                || !safe(profile.getEmploymentHistory()).isEmpty()) {
            throw invalid(
                    "profile",
                    "schema 2.0 does not accept browser-positioned qualifications or employment history");
        }
        PromptEvidenceSnapshot cv = normalizeEvidenceSnapshot(
                require(source.getCv(), "evidenceSnapshots.cv"),
                EvidenceSnapshotPurpose.CV,
                "evidenceSnapshots.cv",
                warnings);
        PromptEvidenceSnapshot coverLetter = normalizeEvidenceSnapshot(
                require(
                        source.getCoverLetter(),
                        "evidenceSnapshots.coverLetter"),
                EvidenceSnapshotPurpose.COVER_LETTER,
                "evidenceSnapshots.coverLetter",
                warnings);
        if (cv.snapshotId().equals(coverLetter.snapshotId())) {
            throw invalid(
                    "evidenceSnapshots",
                    "CV and cover letter require distinct purpose-bound snapshots");
        }
        String expectedRevision = profileProvenance.getResourceId();
        String expectedVersion =
                "sha256:" + cv.profileContentDigest();
        if (!cv.profileRevisionId().toString().equals(expectedRevision)
                || !coverLetter.profileRevisionId()
                        .equals(cv.profileRevisionId())
                || !coverLetter.profileContentDigest()
                        .equals(cv.profileContentDigest())
                || !expectedVersion.equals(
                        profileProvenance.getVersion())) {
            throw invalid(
                    "evidenceSnapshots",
                    "snapshots must bind to the exact supplied profile revision and digest");
        }
        return new PromptEvidenceSnapshots(cv, coverLetter);
    }

    private PromptEvidenceSnapshot normalizeEvidenceSnapshot(
            EvidenceSnapshotInput source,
            EvidenceSnapshotPurpose expectedPurpose,
            String path,
            List<InputWarning> warnings) {
        require(source.getSnapshotId(), path + ".snapshotId");
        if (source.getPurpose() != expectedPurpose) {
            throw invalid(
                    path + ".purpose",
                    "must be " + expectedPurpose);
        }
        require(source.getProfileRevisionId(),
                path + ".profileRevisionId");
        String profileDigest = digest(
                source.getProfileContentDigest(),
                path + ".profileContentDigest");
        String snapshotDigest =
                digest(source.getSnapshotDigest(),
                        path + ".snapshotDigest");
        Instant createdAt = require(
                source.getCreatedAt(), path + ".createdAt");
        if (createdAt.isAfter(clock.instant().plus(MAX_CLOCK_SKEW))) {
            throw invalid(path + ".createdAt", "must not be in the future");
        }
        List<com.jobseekercopilot.cvcoverletter.dto.EvidenceCategory>
                sectionOrder = source.getSectionOrder() == null
                ? List.of()
                : List.copyOf(source.getSectionOrder());
        if (sectionOrder.isEmpty()
                || sectionOrder.size() > 9
                || sectionOrder.stream().anyMatch(java.util.Objects::isNull)
                || new LinkedHashSet<>(sectionOrder).size()
                        != sectionOrder.size()) {
            throw invalid(
                    path + ".sectionOrder",
                    "must be a non-empty unique ordered category list");
        }
        List<EvidenceSnapshotSelectionInput> sourceSelections =
                source.getSelections() == null
                        ? List.of()
                        : source.getSelections();
        if (sourceSelections.isEmpty()
                || sourceSelections.size() > 50) {
            throw invalid(
                    path + ".selections",
                    "must contain 1 to 50 selected revisions");
        }
        Set<java.util.UUID> entryIds = new LinkedHashSet<>();
        Set<java.util.UUID> factIds = new LinkedHashSet<>();
        List<PromptEvidenceSelection> selections = new ArrayList<>();
        for (int selectionIndex = 0;
                selectionIndex < sourceSelections.size();
                selectionIndex++) {
            EvidenceSnapshotSelectionInput selection =
                    require(
                            sourceSelections.get(selectionIndex),
                            path + ".selections[" + selectionIndex + "]");
            String selectionPath =
                    path + ".selections[" + selectionIndex + "]";
            if (selection.getEntryId() == null
                    || !entryIds.add(selection.getEntryId())) {
                throw invalid(
                        selectionPath + ".entryId",
                        "must be present and unique");
            }
            require(selection.getRevisionId(),
                    selectionPath + ".revisionId");
            if (selection.getRevisionNumber() == null
                    || selection.getRevisionNumber() < 1) {
                throw invalid(
                        selectionPath + ".revisionNumber",
                        "must be positive");
            }
            if (selection.getCategory() == null
                    || !sectionOrder.contains(
                            selection.getCategory())) {
                throw invalid(
                        selectionPath + ".category",
                        "must appear in the claimant section order");
            }
            String contentDigest = digest(
                    selection.getContentDigest(),
                    selectionPath + ".contentDigest");
            List<EvidenceSnapshotFactInput> sourceFacts =
                    selection.getFacts() == null
                            ? List.of()
                            : selection.getFacts();
            if (sourceFacts.isEmpty() || sourceFacts.size() > 50) {
                throw invalid(
                        selectionPath + ".facts",
                        "must contain 1 to 50 confirmed facts");
            }
            List<PromptEvidenceFact> facts = new ArrayList<>();
            for (int factIndex = 0;
                    factIndex < sourceFacts.size();
                    factIndex++) {
                EvidenceSnapshotFactInput fact = require(
                        sourceFacts.get(factIndex),
                        selectionPath + ".facts[" + factIndex + "]");
                String factPath =
                        selectionPath + ".facts[" + factIndex + "]";
                if (fact.getFactId() == null
                        || !factIds.add(fact.getFactId())) {
                    throw invalid(
                            factPath + ".factId",
                            "must be present and unique within its snapshot");
                }
                String factType = fact.getFactType() == null
                        ? ""
                        : fact.getFactType().trim();
                if (!FACT_TYPE.matcher(factType).matches()) {
                    throw invalid(
                            factPath + ".factType",
                            "has an invalid stable type");
                }
                String factValue = normalizeText(
                        fact.getFactValue(),
                        factPath + ".factValue",
                        warnings,
                        true);
                Boolean numericClaim = require(
                        fact.getNumericClaim(),
                        factPath + ".numericClaim");
                facts.add(new PromptEvidenceFact(
                        fact.getFactId(),
                        factType,
                        factValue,
                        numericClaim));
            }
            selections.add(new PromptEvidenceSelection(
                    selection.getEntryId(),
                    selection.getRevisionId(),
                    selection.getRevisionNumber(),
                    selection.getCategory(),
                    contentDigest,
                    List.copyOf(facts)));
        }
        return new PromptEvidenceSnapshot(
                source.getSnapshotId(),
                expectedPurpose,
                source.getProfileRevisionId(),
                profileDigest,
                sectionOrder,
                List.copyOf(selections),
                snapshotDigest,
                createdAt);
    }

    private ContactDetails normalizeContact(
            ProfileInputSnapshot profile, List<InputWarning> warnings) {
        ContactInputSnapshot contact = profile.getContact();
        String fullName = contact == null
                ? null
                : normalizeText(contact.getFullName(), "profile.contact.fullName", warnings, false);
        String email = contact == null
                ? null
                : normalizeText(contact.getEmail(), "profile.contact.email", warnings, false);
        String location = normalizeText(profile.getLocation(), "profile.location", warnings, false);
        warnMissing(fullName, "CONTACT_NAME_MISSING", "profile.contact.fullName", warnings);
        warnMissing(email, "CONTACT_EMAIL_MISSING", "profile.contact.email", warnings);
        return new ContactDetails(fullName, email, location);
    }

    private PromptProfile normalizeProfile(
            ProfileInputSnapshot profile, List<InputWarning> warnings) {
        List<String> skills = normalizeDistinctText(
                profile.getSkills(), "profile.skills", "PROFILE_SKILL_DUPLICATE_REMOVED", warnings);
        List<String> targetRoles = normalizeDistinctText(
                profile.getTargetRoles(),
                "profile.targetRoles",
                "PROFILE_TARGET_ROLE_DUPLICATE_REMOVED",
                warnings);
        List<PromptQualification> qualifications =
                normalizeQualifications(profile.getQualifications(), warnings);
        List<PromptEmployment> employment =
                normalizeEmployment(profile.getEmploymentHistory(), warnings);

        warnEmpty(skills, "PROFILE_SKILLS_MISSING", "profile.skills", warnings);
        warnEmpty(targetRoles, "PROFILE_TARGET_ROLES_MISSING", "profile.targetRoles", warnings);
        warnEmpty(
                qualifications,
                "PROFILE_QUALIFICATIONS_MISSING",
                "profile.qualifications",
                warnings);
        warnEmpty(
                employment,
                "PROFILE_EMPLOYMENT_HISTORY_MISSING",
                "profile.employmentHistory",
                warnings);
        return new PromptProfile(skills, targetRoles, qualifications, employment);
    }

    private PromptJob normalizeJob(JobInputSnapshot job, List<InputWarning> warnings) {
        String title = normalizeText(job.getTitle(), "job.title", warnings, true);
        String company = normalizeText(job.getCompany(), "job.company", warnings, true);
        String advertiserName = normalizeText(
                job.getAdvertiserName(),
                "job.advertiserName",
                warnings,
                false);
        AdvertiserType advertiserType = job.getAdvertiserType() == null
                ? AdvertiserType.UNKNOWN
                : job.getAdvertiserType();
        String hiringOrganisationName = normalizeText(
                job.getHiringOrganisationName(),
                "job.hiringOrganisationName",
                warnings,
                false);
        String applicationContactName = normalizeText(
                job.getApplicationContactName(),
                "job.applicationContactName",
                warnings,
                false);
        String location = normalizeText(job.getLocation(), "job.location", warnings, false);
        String employmentType =
                normalizeText(job.getEmploymentType(), "job.employmentType", warnings, false);
        String description =
                normalizeText(job.getDescription(), "job.description", warnings, true);
        JobDescriptionCompleteness completeness =
                job.getDescriptionCompleteness();
        if (completeness == null) {
            if (looksLikeDescriptionPreview(description)) {
                throw invalid(
                        "job.description",
                        "must contain a full or explicitly user-confirmed advert");
            }
            completeness = JobDescriptionCompleteness.FULL;
        }
        if (completeness != JobDescriptionCompleteness.FULL
                && completeness
                        != JobDescriptionCompleteness.USER_CONFIRMED) {
            throw invalid(
                    "job.description",
                    "must contain a full or explicitly user-confirmed advert");
        }
        if (job.getPostedDate() != null && job.getPostedDate().isAfter(LocalDate.now(clock))) {
            warnings.add(warning(
                    "JOB_POSTED_DATE_IN_FUTURE",
                    "job.postedDate",
                    "The source job date is in the future; the source value was retained."));
        }
        return new PromptJob(
                title,
                company,
                advertiserName,
                advertiserType,
                hiringOrganisationName,
                applicationContactName,
                location,
                employmentType,
                job.getPostedDate(),
                description,
                completeness.name());
    }

    private boolean looksLikeDescriptionPreview(String description) {
        String normalized = description.trim();
        return normalized.length() < 600
                || normalized.endsWith("...")
                || normalized.endsWith("…")
                || normalized.matches("(?is).*\\bTHE\\s+(?:ROL|ROLE)\\s*$");
    }

    private List<PromptQualification> normalizeQualifications(
            List<QualificationInput> source, List<InputWarning> warnings) {
        if (source == null) {
            return List.of();
        }
        Map<String, PromptQualification> byIdentity = new LinkedHashMap<>();
        for (int index = 0; index < source.size(); index++) {
            QualificationInput item = source.get(index);
            String path = "profile.qualifications[" + index + "]";
            if (item == null) {
                warnings.add(warning(
                        "PROFILE_QUALIFICATION_EMPTY_REMOVED",
                        path,
                        "An empty qualification entry was removed."));
                continue;
            }
            String name = normalizeText(
                    item.getQualificationName(), path + ".qualificationName", warnings, true);
            String issuer =
                    normalizeText(item.getIssuingBody(), path + ".issuingBody", warnings, false);
            String grade = normalizeText(item.getGrade(), path + ".grade", warnings, false);
            String achieved = normalizeDate(
                    item.getDateAchieved(), false, path + ".dateAchieved");
            String expected = normalizeDate(
                    item.getExpectedCompletion(), false, path + ".expectedCompletion");
            QualificationStatus status = item.getStatus();

            if (status == QualificationStatus.COMPLETED && expected != null) {
                warnings.add(warning(
                        "PROFILE_QUALIFICATION_DATE_CONFLICT",
                        path,
                        "Completed qualification ignored expectedCompletion."));
                expected = null;
            } else if (status == QualificationStatus.IN_PROGRESS && achieved != null) {
                warnings.add(warning(
                        "PROFILE_QUALIFICATION_DATE_CONFLICT",
                        path,
                        "In-progress qualification ignored dateAchieved."));
                achieved = null;
            } else if (status == null) {
                warnings.add(warning(
                        "PROFILE_QUALIFICATION_STATUS_MISSING",
                        path + ".status",
                        "Qualification status is missing; no status was inferred."));
            }

            PromptQualification normalized =
                    new PromptQualification(name, issuer, status, grade, achieved, expected);
            String identity = key(name, issuer);
            PromptQualification existing = byIdentity.putIfAbsent(identity, normalized);
            if (existing != null) {
                warnings.add(warning(
                        existing.equals(normalized)
                                ? "PROFILE_QUALIFICATION_DUPLICATE_REMOVED"
                                : "PROFILE_QUALIFICATION_CONFLICT",
                        path,
                        existing.equals(normalized)
                                ? "A duplicate qualification was removed."
                                : "Conflicting qualification data was ignored; the first entry was retained."));
            }
        }
        return List.copyOf(byIdentity.values());
    }

    private List<PromptEmployment> normalizeEmployment(
            List<EmploymentInput> source, List<InputWarning> warnings) {
        if (source == null) {
            return List.of();
        }
        Map<String, PromptEmployment> byIdentity = new LinkedHashMap<>();
        for (int index = 0; index < source.size(); index++) {
            EmploymentInput item = source.get(index);
            String path = "profile.employmentHistory[" + index + "]";
            if (item == null) {
                warnings.add(warning(
                        "PROFILE_EMPLOYMENT_EMPTY_REMOVED",
                        path,
                        "An empty employment entry was removed."));
                continue;
            }
            String title =
                    normalizeText(item.getJobTitle(), path + ".jobTitle", warnings, true);
            String employer =
                    normalizeText(item.getEmployer(), path + ".employer", warnings, true);
            String start = normalizeDate(item.getStartDate(), false, path + ".startDate");
            String end = normalizeDate(item.getEndDate(), true, path + ".endDate");
            String responsibilities = normalizeText(
                    item.getResponsibilities(), path + ".responsibilities", warnings, false);
            RoleStatus status = item.getStatus();

            if (status == RoleStatus.CURRENT && end != null && !"present".equals(end)) {
                warnings.add(warning(
                        "PROFILE_EMPLOYMENT_DATE_CONFLICT",
                        path + ".endDate",
                        "Current employment ignored a supplied end date."));
                end = null;
            } else if (status == RoleStatus.PREVIOUS_ROLE && end == null) {
                warnings.add(warning(
                        "PROFILE_EMPLOYMENT_END_DATE_MISSING",
                        path + ".endDate",
                        "Previous employment has no end date; no date was inferred."));
            } else if (status == null) {
                warnings.add(warning(
                        "PROFILE_EMPLOYMENT_STATUS_MISSING",
                        path + ".status",
                        "Employment status is missing; no status was inferred."));
            }
            if (responsibilities == null) {
                warnings.add(warning(
                        "PROFILE_EMPLOYMENT_RESPONSIBILITIES_MISSING",
                        path + ".responsibilities",
                        "Employment responsibilities are missing; none were invented."));
            }
            if (end != null
                    && !"present".equals(end)
                    && sortableDate(end).isBefore(sortableDate(start))) {
                throw invalid(path + ".endDate", "must not be earlier than startDate");
            }

            PromptEmployment normalized =
                    new PromptEmployment(title, employer, status, start, end, responsibilities);
            String identity = key(title, employer, start);
            PromptEmployment existing = byIdentity.putIfAbsent(identity, normalized);
            if (existing != null) {
                warnings.add(warning(
                        existing.equals(normalized)
                                ? "PROFILE_EMPLOYMENT_DUPLICATE_REMOVED"
                                : "PROFILE_EMPLOYMENT_CONFLICT",
                        path,
                        existing.equals(normalized)
                                ? "A duplicate employment entry was removed."
                                : "Conflicting employment data was ignored; the first entry was retained."));
            }
        }
        return List.copyOf(byIdentity.values());
    }

    private SnapshotProvenance normalizeProvenance(
            SnapshotProvenance source, InputSourceOwner expectedOwner, String path) {
        require(source, path);
        if (source.getOwner() != expectedOwner) {
            throw invalid(path + ".owner", "must be " + expectedOwner);
        }
        String resourceId = opaque(source.getResourceId(), path + ".resourceId");
        String version = opaque(source.getVersion(), path + ".version");
        Instant capturedAt = require(source.getCapturedAt(), path + ".capturedAt");
        if (capturedAt.isAfter(clock.instant().plus(MAX_CLOCK_SKEW))) {
            throw invalid(path + ".capturedAt", "must not be in the future");
        }
        return new SnapshotProvenance(expectedOwner, resourceId, version, capturedAt);
    }

    private List<String> normalizeDistinctText(
            List<String> source,
            String path,
            String duplicateCode,
            List<InputWarning> warnings) {
        if (source == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        for (int index = 0; index < source.size(); index++) {
            String itemPath = path + "[" + index + "]";
            String value = normalizeText(source.get(index), itemPath, warnings, false);
            if (value == null) {
                warnings.add(warning(
                        "PROFILE_EMPTY_VALUE_REMOVED",
                        itemPath,
                        "An empty profile value was removed."));
                continue;
            }
            if (keys.add(value.toLowerCase(Locale.ROOT))) {
                result.add(value);
            } else {
                warnings.add(warning(
                        duplicateCode,
                        itemPath,
                        "A duplicate profile value was removed."));
            }
        }
        return List.copyOf(result);
    }

    private String normalizeText(
            String source,
            String path,
            List<InputWarning> warnings,
            boolean required) {
        if (source == null) {
            if (required) {
                throw invalid(path, "is required");
            }
            return null;
        }
        String decoded = decodeEntities(source);
        String withoutActiveMarkup = ACTIVE_MARKUP.matcher(decoded).replaceAll(" ");
        String withoutTags = HTML_TAG.matcher(withoutActiveMarkup).replaceAll(" ");
        String withoutControls = CONTROL.matcher(withoutTags).replaceAll(" ");
        String normalized = WHITESPACE.matcher(withoutControls).replaceAll(" ").trim();
        if (!source.trim().equals(normalized)) {
            warnings.add(warning(
                    "INPUT_TEXT_NORMALIZED",
                    path,
                    "Markup, control characters or repeated whitespace were removed."));
        }
        if (required && normalized.isBlank()) {
            throw invalid(path, "must contain plain text");
        }
        return normalized.isBlank() ? null : normalized;
    }

    private String decodeEntities(String source) {
        String common = source
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
        Matcher matcher = HTML_ENTITY.matcher(common);
        StringBuffer decoded = new StringBuffer();
        while (matcher.find()) {
            try {
                int codePoint = matcher.group("decimal") != null
                        ? Integer.parseInt(matcher.group("decimal"))
                        : Integer.parseInt(matcher.group("hex"), 16);
                String replacement = Character.isValidCodePoint(codePoint)
                        ? Character.toString(codePoint)
                        : " ";
                matcher.appendReplacement(decoded, Matcher.quoteReplacement(replacement));
            } catch (NumberFormatException exception) {
                matcher.appendReplacement(decoded, " ");
            }
        }
        matcher.appendTail(decoded);
        return decoded.toString();
    }

    private String normalizeDate(String source, boolean allowPresent, String path) {
        if (source == null || source.isBlank()) {
            return null;
        }
        String value = source.trim().toLowerCase(Locale.ROOT);
        if (allowPresent && "present".equals(value)) {
            return value;
        }
        try {
            if (value.matches("[0-9]{4}")) {
                return Year.parse(value).toString();
            }
            if (value.matches("[0-9]{4}-[0-9]{2}")) {
                return YearMonth.parse(value).toString();
            }
            if (value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                return LocalDate.parse(value).toString();
            }
        } catch (DateTimeException ignored) {
            // Consistent error below.
        }
        throw invalid(path, "must use YYYY, YYYY-MM or YYYY-MM-DD"
                + (allowPresent ? ", or present" : ""));
    }

    private YearMonth sortableDate(String value) {
        try {
            if (value.length() == 4) {
                return YearMonth.of(Year.parse(value).getValue(), 1);
            }
            if (value.length() == 7) {
                return YearMonth.parse(value);
            }
            return YearMonth.from(LocalDate.parse(value));
        } catch (DateTimeParseException exception) {
            throw invalid("date", "could not be compared");
        }
    }

    private void enforceTotalLimit(
            PromptProfile profile,
            PromptJob job,
            PromptEvidenceSnapshots evidenceSnapshots) {
        int characters = streamText(profile.skills(), Function.identity())
                + streamText(profile.targetRoles(), Function.identity())
                + streamText(profile.qualifications(), this::qualificationText)
                + streamText(profile.employmentHistory(), this::employmentText)
                + textLength(job.title())
                + textLength(job.company())
                + textLength(job.location())
                + textLength(job.employmentType())
                + textLength(job.description());
        if (evidenceSnapshots != null) {
            characters += evidenceCharacters(evidenceSnapshots.cv());
            characters += evidenceCharacters(
                    evidenceSnapshots.coverLetter());
        }
        if (characters > MAX_NORMALIZED_PROMPT_CHARACTERS) {
            throw invalid(
                    "input",
                    "approved prompt input exceeds "
                            + MAX_NORMALIZED_PROMPT_CHARACTERS
                            + " characters");
        }
    }

    private int evidenceCharacters(PromptEvidenceSnapshot snapshot) {
        if (snapshot == null) {
            return 0;
        }
        return snapshot.selections().stream()
                .flatMap(selection -> selection.facts().stream())
                .map(PromptEvidenceFact::factValue)
                .mapToInt(this::textLength)
                .sum();
    }

    private <T> int streamText(List<T> values, Function<T, String> mapper) {
        return values.stream().map(mapper).mapToInt(this::textLength).sum();
    }

    private String qualificationText(PromptQualification item) {
        return String.join(
                " ",
                nonNull(item.qualificationName()),
                nonNull(item.issuingBody()),
                nonNull(item.grade()),
                nonNull(item.dateAchieved()),
                nonNull(item.expectedCompletion()));
    }

    private String employmentText(PromptEmployment item) {
        return String.join(
                " ",
                nonNull(item.jobTitle()),
                nonNull(item.employer()),
                nonNull(item.startDate()),
                nonNull(item.endDate()),
                nonNull(item.responsibilities()));
    }

    private String opaque(String value, String path) {
        String normalized = value == null ? "" : value.trim();
        if (!OPAQUE_REFERENCE.matcher(normalized).matches()) {
            throw invalid(path, "contains unsupported characters");
        }
        return normalized;
    }

    private String digest(String value, String path) {
        String normalized = value == null ? "" : value.trim();
        if (!SHA256.matcher(normalized).matches()) {
            throw invalid(path, "must be a lowercase SHA-256 digest");
        }
        return normalized;
    }

    private <T> List<T> safe(List<T> value) {
        return value == null ? List.of() : value;
    }

    private String key(String... values) {
        return String.join(
                "|",
                java.util.Arrays.stream(values)
                        .map(this::nonNull)
                        .map(value -> value.toLowerCase(Locale.ROOT))
                        .toList());
    }

    private void warnMissing(
            String value,
            String code,
            String field,
            List<InputWarning> warnings) {
        if (value == null) {
            warnings.add(warning(code, field, "Optional input is missing; no value was inferred."));
        }
    }

    private void warnEmpty(
            List<?> values,
            String code,
            String field,
            List<InputWarning> warnings) {
        if (values.isEmpty()) {
            warnings.add(warning(code, field, "Input is empty; no history or evidence was invented."));
        }
    }

    private InputWarning warning(String code, String field, String message) {
        return new InputWarning(code, field, message);
    }

    private <T> T require(T value, String path) {
        if (value == null) {
            throw invalid(path, "is required");
        }
        return value;
    }

    private int textLength(String value) {
        return value == null ? 0 : value.length();
    }

    private String nonNull(String value) {
        return value == null ? "" : value;
    }

    private InvalidGenerationInputException invalid(String field, String message) {
        return new InvalidGenerationInputException(field + ": " + message);
    }
}
