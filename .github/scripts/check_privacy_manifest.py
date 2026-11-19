#!/usr/bin/env python3
"""#771: fail when UserDefaults is used without a PrivacyInfo.xcprivacy declaration.

Apple requires apps to declare required-reason APIs in a privacy manifest; App
Store review can reject builds that use UserDefaults without one. This checks:
  1. Each shipped target (app and widget) has a PrivacyInfo.xcprivacy file.
  2. Each manifest declares the UserDefaults required-reason category with a reason.
  3. project.yml bundles the manifests into the targets as resources.
  4. If any Swift source under the iOS Sources/ tree references UserDefaults, the
     UserDefaults declaration must be present.
"""
import pathlib
import plistlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
IOS = ROOT / "ios" / "EthosProtocol"
MANIFESTS = [
    IOS / "EthosProtocol" / "PrivacyInfo.xcprivacy",
    IOS / "TTLWidget" / "PrivacyInfo.xcprivacy",
]
USERDEFAULTS = "NSPrivacyAccessedAPICategoryUserDefaults"


def check_manifest(path: pathlib.Path, errors: list[str]) -> None:
    if not path.is_file():
        errors.append(f"missing privacy manifest: {path.relative_to(ROOT)}")
        return
    with path.open("rb") as fh:
        manifest = plistlib.load(fh)
    apis = manifest.get("NSPrivacyAccessedAPITypes", [])
    for api in apis:
        if api.get("NSPrivacyAccessedAPIType") == USERDEFAULTS and api.get("NSPrivacyAccessedAPITypeReasons"):
            return
    errors.append(f"{path.relative_to(ROOT)} does not declare {USERDEFAULTS} with a reason")


def main() -> int:
    errors: list[str] = []
    for manifest in MANIFESTS:
        check_manifest(manifest, errors)

    project = (IOS / "project.yml").read_text()
    for manifest in MANIFESTS:
        rel = manifest.relative_to(IOS).as_posix()
        if rel not in project:
            errors.append(f"project.yml does not include {rel} as a resource")

    uses_user_defaults = [
        p.relative_to(ROOT).as_posix()
        for p in (IOS / "Sources").rglob("*.swift")
        if "UserDefaults" in p.read_text(errors="ignore")
    ]
    if uses_user_defaults and any("does not declare" in e or "missing privacy" in e for e in errors):
        errors.append(f"UserDefaults used in {len(uses_user_defaults)} file(s) without a declaration")

    for e in errors:
        print(f"::error::{e}")
    if errors:
        return 1
    print(f"Privacy manifests OK ({len(uses_user_defaults)} Swift files use UserDefaults).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
