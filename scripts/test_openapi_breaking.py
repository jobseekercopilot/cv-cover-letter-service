#!/usr/bin/env python3
"""Negative tests for the client-publication compatibility gate."""

from __future__ import annotations

import copy
import json
import unittest
from pathlib import Path

from openapi_breaking import breaking_changes


ROOT = Path(__file__).resolve().parent.parent


class OpenApiBreakingTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.contract = json.loads(
            (ROOT / "contracts" / "openapi.json").read_text(encoding="utf-8")
        )

    def findings_after(self, mutate) -> list[str]:
        current = copy.deepcopy(self.contract)
        mutate(current)
        return breaking_changes(self.contract, current)

    def test_identical_contract_is_compatible(self) -> None:
        self.assertEqual(breaking_changes(self.contract, self.contract), [])

    def test_removed_operation_is_rejected(self) -> None:
        findings = self.findings_after(
            lambda current: current["paths"][
                "/api/v1/cv-cover-letter/generate"
            ].pop("post")
        )
        self.assertTrue(any("operation was removed" in finding for finding in findings))

    def test_required_property_addition_is_rejected(self) -> None:
        findings = self.findings_after(
            lambda current: current["components"]["schemas"]["UserProfile"]
            .setdefault("required", [])
            .append("skills")
        )
        self.assertTrue(
            any("optional property became required: skills" in finding for finding in findings)
        )

    def test_enum_narrowing_is_rejected(self) -> None:
        findings = self.findings_after(
            lambda current: current["components"]["schemas"]["Aspirations"][
                "properties"
            ]["targetWeeklyHours"]["enum"].pop()
        )
        self.assertTrue(any("enum values were removed" in finding for finding in findings))


if __name__ == "__main__":
    unittest.main()
