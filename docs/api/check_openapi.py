"""Fails if docs/api/openapi.yaml and the controllers disagree about which endpoints exist.

    python docs/api/check_openapi.py

The spec is written by hand, so this is what keeps it honest: every public mapping in a controller
(`@GetMapping`, `@PostMapping`, ... under the class's `@RequestMapping`) must be a path and method in the spec, and
the spec must name nothing the controllers don't have. `Internal*Controller`s are skipped: `/internal/**` is the
service-to-service API, never routed by the gateway, and documented in docs/api/internal.md instead.

It compares paths and methods, not request and response shapes. Standard library only (so the YAML is read by its
layout, which this file's own two-space indentation keeps predictable). CI runs it.
"""
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SPEC = REPO / "docs" / "api" / "openapi.yaml"
CONTROLLERS = REPO / "microservices"

MAPPING = re.compile(r'@(Get|Post|Put|Patch|Delete)Mapping(?:\(\s*(?:value\s*=\s*)?"([^"]*)"[^)]*\))?')
CLASS_MAPPING = re.compile(r'@RequestMapping\(\s*(?:value\s*=\s*)?"([^"]*)"')


def controller_endpoints():
    endpoints = {}
    for file in sorted(CONTROLLERS.glob("*/src/main/java/**/controller/*Controller.java")):
        if file.name.startswith("Internal"):
            continue
        source = file.read_text(encoding="utf-8")
        # The class-level mapping is the one before the class declaration; a method never carries @RequestMapping here.
        head = source[:source.index("class ")]
        prefix = CLASS_MAPPING.search(head)
        base = prefix.group(1) if prefix else ""
        for verb, path in MAPPING.findall(source):
            endpoints[(verb.upper(), base + (path or ""))] = file.name
    return endpoints


def spec_endpoints():
    endpoints = set()
    path = None
    in_paths = False
    for line in SPEC.read_text(encoding="utf-8").splitlines():
        if re.match(r"^\S", line):
            in_paths = line.startswith("paths:")
            continue
        if not in_paths:
            continue
        found = re.match(r"^  (/\S*):\s*$", line)
        if found:
            path = found.group(1)
            continue
        found = re.match(r"^    (get|post|put|patch|delete):\s*$", line)
        if found and path:
            endpoints.add((found.group(1).upper(), path))
    return endpoints


def main():
    code = controller_endpoints()
    spec = spec_endpoints()
    missing = sorted(set(code) - spec)
    stale = sorted(spec - set(code))
    for verb, path in missing:
        print(f"not in openapi.yaml:   {verb:<6} {path}   ({code[(verb, path)]})")
    for verb, path in stale:
        print(f"not in any controller: {verb:<6} {path}")
    if missing or stale:
        print(f"\n{len(missing)} endpoint(s) undocumented, {len(stale)} documented but gone. Update docs/api/openapi.yaml "
              f"(and the page in docs/api/) in the same change as the controller.")
        return 1
    print(f"openapi.yaml matches the controllers: {len(spec)} endpoints.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
