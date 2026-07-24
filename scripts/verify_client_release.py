#!/usr/bin/env python3
"""Fail-closed validation for the producer-owned Java client release."""

from __future__ import annotations

import hashlib
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
RELEASE_PATH = ROOT / "api" / "client-release.json"
POM_PATH = ROOT / "api" / "client" / "pom.xml"
NAMESPACE = {"m": "http://maven.apache.org/POM/4.0.0"}
SHA_PATTERN = re.compile(r"^[0-9a-f]{40}$")
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
SEMVER_PATTERN = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")


def fail(message: str) -> None:
    raise ValueError(message)


def pom_text(root: ET.Element, path: str) -> str:
    element = root.find(path, NAMESPACE)
    if element is None or not element.text:
        fail(f"missing POM value: {path}")
    return element.text.strip()


def verify() -> dict:
    release = json.loads(RELEASE_PATH.read_text(encoding="utf-8"))
    if release.get("schemaVersion") != 1:
        fail("client release schemaVersion must be 1")

    contract = release.get("contract", {})
    package = release.get("javaPackage", {})
    generator = release.get("generator", {})

    contract_path = contract.get("path", "")
    version = contract.get("version", "")
    revision = contract.get("sourceRevision", "")
    digest = contract.get("sha256", "")
    initial = contract.get("initialRelease")
    compatibility_base = contract.get("compatibilityBaseRevision")

    if contract_path != "contracts/openapi.json":
        fail("contract path must be contracts/openapi.json")
    if not SEMVER_PATTERN.fullmatch(version):
        fail("contract version must be strict semantic versioning")
    if not SHA_PATTERN.fullmatch(revision):
        fail("contract sourceRevision must be a full lowercase Git SHA")
    if not SHA256_PATTERN.fullmatch(digest):
        fail("contract sha256 must be a lowercase SHA-256 digest")
    if initial is True and compatibility_base is not None:
        fail("initial release cannot declare a compatibility base")
    if initial is not True and not (
        isinstance(compatibility_base, str)
        and SHA_PATTERN.fullmatch(compatibility_base)
    ):
        fail("non-initial release requires a full compatibility base revision")

    contract_file = ROOT / contract_path
    current_bytes = contract_file.read_bytes()
    if hashlib.sha256(current_bytes).hexdigest() != digest:
        fail("working contract does not match the release SHA-256")
    document = json.loads(current_bytes)
    if document.get("info", {}).get("version") != version:
        fail("OpenAPI info.version does not match the release version")

    source = subprocess.run(
        ["git", "show", f"{revision}:{contract_path}"],
        cwd=ROOT,
        check=False,
        capture_output=True,
    )
    if source.returncode != 0:
        fail("contract source revision/path is not readable from Git history")
    if source.stdout != current_bytes:
        fail("working contract differs from the recorded source revision")

    expected_package_version = f"{version}-rev.{revision[:12]}"
    expected_registry = (
        "https://maven.pkg.github.com/jobseekercopilot/cv-cover-letter-service"
    )
    if package.get("groupId") != "com.jobseekercopilot.clients":
        fail("unexpected Java client groupId")
    if package.get("artifactId") != "cv-cover-letter-service-client":
        fail("unexpected Java client artifactId")
    if package.get("version") != expected_package_version:
        fail("Java client version must derive from contract version and source revision")
    if package.get("registry") != expected_registry:
        fail("unexpected Java client registry")
    if generator != {
        "name": "openapi-generator",
        "version": "7.5.0",
        "library": "resttemplate",
    }:
        fail("generator must match the Infrastructure publication convention")

    pom = ET.parse(POM_PATH).getroot()
    if pom_text(pom, "m:groupId") != package["groupId"]:
        fail("POM groupId does not match release metadata")
    if pom_text(pom, "m:artifactId") != package["artifactId"]:
        fail("POM artifactId does not match release metadata")
    if pom_text(pom, "m:version") != package["version"]:
        fail("POM version does not match release metadata")
    if (
        pom_text(pom, "m:distributionManagement/m:repository/m:url")
        != expected_registry
    ):
        fail("POM publication registry does not match release metadata")

    properties = pom.find("m:properties", NAMESPACE)
    if properties is None:
        fail("POM properties are missing")
    expected_properties = {
        "openapi-generator.version": generator["version"],
        "jobseekercopilot.contract.path": contract_path,
        "jobseekercopilot.contract.version": version,
        "jobseekercopilot.contract.revision": revision,
        "jobseekercopilot.contract.sha256": digest,
    }
    for name, expected in expected_properties.items():
        element = properties.find(f"m:{name}", NAMESPACE)
        if element is None or not element.text or element.text.strip() != expected:
            fail(f"POM property does not match release metadata: {name}")

    tracked_output = subprocess.run(
        ["git", "ls-files", "api/client/target", "api/client-smoke/target"],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()
    if tracked_output:
        fail("generated client output is tracked by Git")

    return release


def main() -> int:
    try:
        release = verify()
    except (
        OSError,
        ValueError,
        ET.ParseError,
        json.JSONDecodeError,
        subprocess.SubprocessError,
    ) as error:
        print(f"Client release policy failed: {error}", file=sys.stderr)
        return 1

    package = release["javaPackage"]
    print(
        "Client release policy passed: "
        f"{package['groupId']}:{package['artifactId']}:{package['version']}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
