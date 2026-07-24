#!/usr/bin/env python3
"""Conservative OpenAPI breaking-change checks for published client contracts."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


HTTP_METHODS = {"get", "put", "post", "delete", "options", "head", "patch", "trace"}


def _major_version(document: dict) -> int:
    version = document.get("info", {}).get("version", "")
    try:
        return int(version.split(".", maxsplit=1)[0])
    except (AttributeError, ValueError) as error:
        raise ValueError(f"invalid OpenAPI info.version: {version!r}") from error


def major_version_allows_breaking(base: dict, current: dict) -> bool:
    return _major_version(current) > _major_version(base)


def _enum_values(schema: dict) -> set:
    values = schema.get("enum", [])
    return set(values) if isinstance(values, list) else set()


def _check_schema(base: dict, current: dict, context: str, findings: list[str]) -> None:
    base_enum = _enum_values(base)
    current_enum = _enum_values(current)
    if base_enum and not base_enum.issubset(current_enum):
        findings.append(f"{context}: enum values were removed")

    base_properties = base.get("properties", {})
    current_properties = current.get("properties", {})
    if isinstance(base_properties, dict) and isinstance(current_properties, dict):
        for name, base_property in base_properties.items():
            if name not in current_properties:
                findings.append(f"{context}: property was removed: {name}")
                continue
            if isinstance(base_property, dict) and isinstance(current_properties[name], dict):
                _check_schema(
                    base_property,
                    current_properties[name],
                    f"{context}.{name}",
                    findings,
                )

    base_required = set(base.get("required", []))
    current_required = set(current.get("required", []))
    for name in sorted(current_required - base_required):
        findings.append(f"{context}: optional property became required: {name}")


def _parameters(operation: dict) -> dict[tuple[str, str], dict]:
    return {
        (parameter.get("in", ""), parameter.get("name", "")): parameter
        for parameter in operation.get("parameters", [])
        if isinstance(parameter, dict)
    }


def _check_operation(
    base: dict,
    current: dict,
    context: str,
    findings: list[str],
) -> None:
    if base.get("operationId") != current.get("operationId"):
        findings.append(f"{context}: operationId changed")

    base_parameters = _parameters(base)
    current_parameters = _parameters(current)
    for key, base_parameter in base_parameters.items():
        if key not in current_parameters:
            findings.append(f"{context}: parameter was removed: {key[0]} {key[1]}")
            continue
        current_parameter = current_parameters[key]
        if not base_parameter.get("required", False) and current_parameter.get(
            "required", False
        ):
            findings.append(f"{context}: parameter became required: {key[0]} {key[1]}")
        _check_schema(
            base_parameter.get("schema", {}),
            current_parameter.get("schema", {}),
            f"{context} parameter {key[0]} {key[1]}",
            findings,
        )

    base_request = base.get("requestBody", {})
    current_request = current.get("requestBody", {})
    if base_request and not current_request:
        findings.append(f"{context}: request body was removed")
    elif (
        not base_request.get("required", False)
        and current_request.get("required", False)
    ):
        findings.append(f"{context}: request body became required")

    base_responses = base.get("responses", {})
    current_responses = current.get("responses", {})
    for status in base_responses:
        if status not in current_responses:
            findings.append(f"{context}: response was removed: {status}")


def breaking_changes(base: dict, current: dict) -> list[str]:
    findings: list[str] = []
    base_paths = base.get("paths", {})
    current_paths = current.get("paths", {})
    for path, base_path in base_paths.items():
        if path not in current_paths:
            findings.append(f"path was removed: {path}")
            continue
        current_path = current_paths[path]
        for method, base_operation in base_path.items():
            if method not in HTTP_METHODS:
                continue
            if method not in current_path:
                findings.append(f"operation was removed: {method.upper()} {path}")
                continue
            _check_operation(
                base_operation,
                current_path[method],
                f"{method.upper()} {path}",
                findings,
            )

    base_schemas = base.get("components", {}).get("schemas", {})
    current_schemas = current.get("components", {}).get("schemas", {})
    for name, base_schema in base_schemas.items():
        if name not in current_schemas:
            findings.append(f"component schema was removed: {name}")
            continue
        _check_schema(base_schema, current_schemas[name], name, findings)

    return findings


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Reject common breaking changes between two OpenAPI documents."
    )
    parser.add_argument("base", type=Path)
    parser.add_argument("current", type=Path)
    args = parser.parse_args()

    with args.base.open(encoding="utf-8") as handle:
        base = json.load(handle)
    with args.current.open(encoding="utf-8") as handle:
        current = json.load(handle)

    findings = breaking_changes(base, current)
    if findings:
        try:
            major_release = major_version_allows_breaking(base, current)
        except ValueError as error:
            print(f"OpenAPI compatibility check failed: {error}", file=sys.stderr)
            return 1
        if major_release:
            print(
                "OpenAPI compatibility check accepted reviewed breaking changes "
                "under a new major version"
            )
            return 0
        print("OpenAPI compatibility check failed:", file=sys.stderr)
        for finding in findings:
            print(f"- {finding}", file=sys.stderr)
        return 1

    print("OpenAPI compatibility check passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
