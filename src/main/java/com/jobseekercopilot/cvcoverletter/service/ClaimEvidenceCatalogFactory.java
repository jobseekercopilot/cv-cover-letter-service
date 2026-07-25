package com.jobseekercopilot.cvcoverletter.service;

import com.jobseekercopilot.cvcoverletter.model.ApprovedEvidenceRecord;
import com.jobseekercopilot.cvcoverletter.model.ClaimEvidenceCatalog;
import com.jobseekercopilot.cvcoverletter.model.EvidenceSource;
import com.jobseekercopilot.cvcoverletter.model.NormalizedGenerationInput;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ClaimEvidenceCatalogFactory {
    static final String CATALOG_VERSION = "1.0";

    public ClaimEvidenceCatalog create(NormalizedGenerationInput input) {
        List<ApprovedEvidenceRecord> records = new ArrayList<>();
        add(
                records,
                "REQUEST.GENERATION_INTENT",
                EvidenceSource.REQUEST,
                "/request/generationIntent",
                "Generate an application CV and cover letter for the supplied canonical job."
        );

        for (int index = 0; index < input.profile().skills().size(); index++) {
            add(
                    records,
                    "PROFILE.SKILL." + (index + 1),
                    EvidenceSource.PROFILE,
                    "/profile/skills/" + index,
                    input.profile().skills().get(index)
            );
        }
        for (int index = 0; index < input.profile().targetRoles().size(); index++) {
            add(
                    records,
                    "PROFILE.TARGET_ROLE." + (index + 1),
                    EvidenceSource.PROFILE,
                    "/profile/targetRoles/" + index,
                    input.profile().targetRoles().get(index)
            );
        }
        for (int index = 0; index < input.profile().qualifications().size(); index++) {
            NormalizedGenerationInput.PromptQualification qualification =
                    input.profile().qualifications().get(index);
            String idPrefix = "PROFILE.QUALIFICATION." + (index + 1) + ".";
            String pathPrefix = "/profile/qualifications/" + index + "/";
            add(records, idPrefix + "NAME", EvidenceSource.PROFILE,
                    pathPrefix + "qualificationName", qualification.qualificationName());
            add(records, idPrefix + "ISSUING_BODY", EvidenceSource.PROFILE,
                    pathPrefix + "issuingBody", qualification.issuingBody());
            add(records, idPrefix + "STATUS", EvidenceSource.PROFILE,
                    pathPrefix + "status", value(qualification.status()));
            add(records, idPrefix + "GRADE", EvidenceSource.PROFILE,
                    pathPrefix + "grade", qualification.grade());
            add(records, idPrefix + "DATE_ACHIEVED", EvidenceSource.PROFILE,
                    pathPrefix + "dateAchieved", qualification.dateAchieved());
            add(records, idPrefix + "EXPECTED_COMPLETION", EvidenceSource.PROFILE,
                    pathPrefix + "expectedCompletion", qualification.expectedCompletion());
        }
        for (int index = 0; index < input.profile().employmentHistory().size(); index++) {
            NormalizedGenerationInput.PromptEmployment employment =
                    input.profile().employmentHistory().get(index);
            String idPrefix = "PROFILE.EMPLOYMENT." + (index + 1) + ".";
            String pathPrefix = "/profile/employmentHistory/" + index + "/";
            add(records, idPrefix + "JOB_TITLE", EvidenceSource.PROFILE,
                    pathPrefix + "jobTitle", employment.jobTitle());
            add(records, idPrefix + "EMPLOYER", EvidenceSource.PROFILE,
                    pathPrefix + "employer", employment.employer());
            add(records, idPrefix + "STATUS", EvidenceSource.PROFILE,
                    pathPrefix + "status", value(employment.status()));
            add(records, idPrefix + "START_DATE", EvidenceSource.PROFILE,
                    pathPrefix + "startDate", employment.startDate());
            add(records, idPrefix + "END_DATE", EvidenceSource.PROFILE,
                    pathPrefix + "endDate", employment.endDate());
            add(records, idPrefix + "RESPONSIBILITIES", EvidenceSource.PROFILE,
                    pathPrefix + "responsibilities", employment.responsibilities());
        }

        add(records, "JOB.TITLE", EvidenceSource.JOB, "/job/title", input.job().title());
        add(records, "JOB.COMPANY", EvidenceSource.JOB, "/job/company", input.job().company());
        add(records, "JOB.LOCATION", EvidenceSource.JOB, "/job/location", input.job().location());
        add(records, "JOB.EMPLOYMENT_TYPE", EvidenceSource.JOB,
                "/job/employmentType", input.job().employmentType());
        add(records, "JOB.POSTED_DATE", EvidenceSource.JOB,
                "/job/postedDate", value(input.job().postedDate()));
        add(records, "JOB.DESCRIPTION", EvidenceSource.JOB,
                "/job/description", input.job().description());

        return new ClaimEvidenceCatalog(CATALOG_VERSION, List.copyOf(records));
    }

    private void add(
            List<ApprovedEvidenceRecord> records,
            String evidenceId,
            EvidenceSource source,
            String sourcePath,
            String value
    ) {
        if (StringUtils.hasText(value)) {
            records.add(new ApprovedEvidenceRecord(
                    evidenceId,
                    source,
                    sourcePath,
                    value.trim()
            ));
        }
    }

    private String value(Object value) {
        return value == null ? null : value.toString();
    }
}
