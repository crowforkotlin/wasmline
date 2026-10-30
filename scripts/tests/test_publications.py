#!/usr/bin/env python3
"""Regression tests for Maven publication failures found during release preparation."""

from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(PROJECT_ROOT / "scripts/internal/python"))

from wasmline_tools.publications import GROUP, JNI_PLATFORMS, MODULES, PublicationError, verify_publications


class PublicationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.repository = Path(self.temporary.name)
        self.version = "12.3.4"
        for module in (*MODULES, "crow.wasmline.gradle.plugin"):
            self.write_module(module)

    def directory(self, module: str) -> Path:
        return self.repository / "crow/wasmline" / module / self.version

    def write_module(self, module: str, variants: list | None = None) -> None:
        directory = self.directory(module)
        directory.mkdir(parents=True, exist_ok=True)
        directory.joinpath(f"{module}-{self.version}.pom").write_text(
            f'<project xmlns="http://maven.apache.org/POM/4.0.0"><groupId>{GROUP}</groupId>'
            f"<artifactId>{module}</artifactId><version>{self.version}</version></project>",
            encoding="utf-8",
        )
        directory.joinpath(f"{module}-{self.version}.module").write_text(
            json.dumps({"component": {"group": GROUP, "module": module, "version": self.version}, "variants": variants or []}),
            encoding="utf-8",
        )

    def write_artifact(self, module: str, content: bytes = b"published artifact") -> dict:
        name = f"{module}-{self.version}.jar"
        self.directory(module).joinpath(name).write_bytes(content)
        return {"name": name, "url": name, "size": len(content), "sha256": hashlib.sha256(content).hexdigest()}

    def test_verifies_target_redirect_and_artifact_digest(self) -> None:
        target = "wasmline-jvm"
        self.write_module(target)
        artifact = self.write_artifact(target)
        self.write_module(target, [{"files": [artifact]}])
        self.write_module("wasmline", [{"available-at": {
            "group": GROUP, "module": target, "version": self.version,
            "url": f"../../{target}/{self.version}/{target}-{self.version}.module",
        }}])
        self.assertEqual((len(MODULES) + 1, 1), verify_publications(self.repository, self.version))

    def test_rejects_stale_metadata(self) -> None:
        path = self.directory("wasmline") / f"wasmline-{self.version}.module"
        metadata = json.loads(path.read_text())
        metadata["component"]["version"] = "12.3.3"
        path.write_text(json.dumps(metadata))
        with self.assertRaisesRegex(PublicationError, "stale module metadata"):
            verify_publications(self.repository, self.version)

    def test_rejects_android_self_dependency(self) -> None:
        module = "wasmline-android"
        self.write_module(module, [{"dependencies": [{
            "group": GROUP, "module": module, "version": {"requires": self.version},
        }]}])
        self.write_module("wasmline", [{"available-at": {
            "group": GROUP, "module": module, "version": self.version,
            "url": f"../../{module}/{self.version}/{module}-{self.version}.module",
        }}])
        with self.assertRaisesRegex(PublicationError, "self dependency"):
            verify_publications(self.repository, self.version)

    def test_rejects_wrong_wasmline_dependency_version(self) -> None:
        self.write_module("wasmline", [{"dependencies": [{
            "group": GROUP, "module": "wasmline-loader", "version": {"requires": "12.3.3"},
        }]}])
        with self.assertRaisesRegex(PublicationError, "dependency does not use version"):
            verify_publications(self.repository, self.version)

    def test_verifies_test_fixture_dependency_on_main_variant(self) -> None:
        module = "wasmline-kotlin-plugin"
        variants = [{
            "name": "testFixturesApiElements",
            "capabilities": [{"group": GROUP, "name": f"{module}-test-fixtures", "version": self.version}],
            "dependencies": [{"group": GROUP, "module": module, "version": {"requires": self.version}}],
        }]
        self.write_module(module, variants)
        self.assertEqual((len(MODULES), 0), verify_publications(self.repository, self.version))
        variants[0]["dependencies"][0]["version"]["requires"] = "12.3.3"
        self.write_module(module, variants)
        with self.assertRaisesRegex(PublicationError, "dependency does not use version"):
            verify_publications(self.repository, self.version)

    def test_rejects_test_fixture_dependency_on_same_capability(self) -> None:
        module = "wasmline-kotlin-plugin"
        capability = {"group": GROUP, "name": f"{module}-test-fixtures", "version": self.version}
        self.write_module(module, [{
            "name": "testFixturesApiElements",
            "capabilities": [capability],
            "dependencies": [{
                "group": GROUP, "module": module, "version": {"requires": self.version},
                "requestedCapabilities": [capability],
            }],
        }])
        with self.assertRaisesRegex(PublicationError, "self dependency"):
            verify_publications(self.repository, self.version)

    def test_rejects_missing_artifact(self) -> None:
        artifact = self.write_artifact("wasmline")
        self.write_module("wasmline", [{"files": [artifact]}])
        self.directory("wasmline").joinpath(artifact["url"]).unlink()
        with self.assertRaisesRegex(PublicationError, "missing artifact"):
            verify_publications(self.repository, self.version)

    def test_rejects_artifact_digest_mismatch(self) -> None:
        artifact = self.write_artifact("wasmline")
        artifact["sha256"] = "0" * 64
        self.write_module("wasmline", [{"files": [artifact]}])
        with self.assertRaisesRegex(PublicationError, "sha256 mismatch"):
            verify_publications(self.repository, self.version)

    def test_requires_apple_targets_for_formal_publication(self) -> None:
        with self.assertRaisesRegex(PublicationError, "missing Apple Maven variants"):
            verify_publications(self.repository, self.version, require_apple=True)

    def test_rejects_empty_native_classifier_jar(self) -> None:
        module = "wasmline-engine-pulley-jvm"
        self.write_module(module)
        name = f"{module}-{self.version}-linux-x86_64.jar"
        with zipfile.ZipFile(self.directory(module) / name, "w") as archive:
            archive.writestr("README", "No native library")
        platform = next(iter(JNI_PLATFORMS))
        self.write_module(module, [{
            "attributes": {
                "org.gradle.native.operatingSystem": platform[0],
                "org.gradle.native.architecture": platform[1],
            },
            "files": [{"name": name, "url": name}],
        }])
        self.write_module("wasmline-engine-pulley", [{"available-at": {
            "group": GROUP, "module": module, "version": self.version,
            "url": f"../../{module}/{self.version}/{module}-{self.version}.module",
        }}])
        with self.assertRaisesRegex(PublicationError, "missing native archive member"):
            verify_publications(self.repository, self.version)

    def test_verifies_native_archive_in_cinterop_klib(self) -> None:
        module = "wasmline-engine-pulley-linuxx64"
        self.write_module(module)
        main_name = f"{module}-{self.version}.klib"
        interop_name = f"{module}-{self.version}-cinterop-wasmline.klib"
        with zipfile.ZipFile(self.directory(module) / main_name, "w") as archive:
            archive.writestr("default/manifest", "Native Kotlin declarations")
        with zipfile.ZipFile(self.directory(module) / interop_name, "w") as archive:
            archive.writestr("default/targets/linux_x64/included/libwasmline_native.a", b"static archive")
        self.write_module(module, [{
            "attributes": {"org.gradle.category": "library", "org.jetbrains.kotlin.platform.type": "native"},
            "files": [{"url": main_name}, {"url": interop_name}],
        }])
        self.write_module("wasmline-engine-pulley", [{"attributes": {
            "org.gradle.category": "library", "org.jetbrains.kotlin.platform.type": "native",
            "org.jetbrains.kotlin.native.target": "linux_x64",
        }, "available-at": {
            "group": GROUP, "module": module, "version": self.version,
            "url": f"../../{module}/{self.version}/{module}-{self.version}.module",
        }}])
        self.assertEqual((len(MODULES) + 1, 2), verify_publications(self.repository, self.version))
        with zipfile.ZipFile(self.directory(module) / interop_name, "w") as archive:
            archive.writestr("default/manifest", "Archive omitted")
        with self.assertRaisesRegex(PublicationError, "does not embed"):
            verify_publications(self.repository, self.version)


if __name__ == "__main__":
    unittest.main()
