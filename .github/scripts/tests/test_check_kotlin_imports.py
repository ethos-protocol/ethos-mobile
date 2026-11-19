#!/usr/bin/env python3
"""Tests for check_kotlin_imports.py, run against temporary repository trees."""
from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path

SCRIPT_PATH = Path(__file__).resolve().parents[1] / "check_kotlin_imports.py"

_spec = importlib.util.spec_from_file_location("check_kotlin_imports", SCRIPT_PATH)
check_kotlin_imports = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(check_kotlin_imports)


def write(root: Path, rel: str, text: str) -> None:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


class CheckKotlinImportsTests(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.src = "android/app/src/main/java/com/ethosprotocol"

    def tearDown(self):
        self._tmp.cleanup()

    def problems(self):
        return check_kotlin_imports.find_import_problems(self.root)

    def test_import_of_declared_top_level_class_passes(self):
        write(self.root, f"{self.src}/api/TokenProvider.kt",
              "package com.ethosprotocol.api\n\ninterface TokenProvider\n")
        write(self.root, f"{self.src}/ui/Screen.kt",
              "package com.ethosprotocol.ui\n\nimport com.ethosprotocol.api.TokenProvider\n")
        self.assertEqual(self.problems(), [])

    def test_import_with_no_declaration_is_reported_with_location(self):
        write(self.root, f"{self.src}/ui/Screen.kt",
              "package com.ethosprotocol.ui\n\nimport com.ethosprotocol.api.OfflineCache\n")
        problems = self.problems()
        self.assertEqual(len(problems), 1)
        self.assertIn("Screen.kt:3", problems[0])
        self.assertIn("com.ethosprotocol.api.OfflineCache", problems[0])

    def test_declared_in_a_different_package_does_not_satisfy_the_import(self):
        write(self.root, f"{self.src}/models/VaultStatus.kt",
              "package com.ethosprotocol.models\n\ndata class VaultStatus(val id: String)\n")
        write(self.root, f"{self.src}/ui/Screen.kt",
              "package com.ethosprotocol.ui\n\nimport com.ethosprotocol.api.VaultStatus\n")
        self.assertEqual(len(self.problems()), 1)

    def test_functions_and_properties_count_as_declarations(self):
        write(self.root, f"{self.src}/models/Filters.kt",
              "package com.ethosprotocol.models\n\n"
              "fun filterVaults(x: Int) = x\n"
              "val DEFAULT_LIMIT = 20\n")
        write(self.root, f"{self.src}/ui/Screen.kt",
              "package com.ethosprotocol.ui\n\n"
              "import com.ethosprotocol.models.filterVaults\n"
              "import com.ethosprotocol.models.DEFAULT_LIMIT\n")
        self.assertEqual(self.problems(), [])

    def test_nested_symbol_is_accepted_when_parent_is_a_type(self):
        write(self.root, f"{self.src}/api/ApiClient.kt",
              "package com.ethosprotocol.api\n\nclass ApiClient {\n"
              "    data class Result(val ok: Boolean)\n}\n")
        write(self.root, f"{self.src}/ui/Screen.kt",
              "package com.ethosprotocol.ui\n\n"
              "import com.ethosprotocol.api.ApiClient.Result\n")
        self.assertEqual(self.problems(), [])

    def test_aliased_import_is_checked_on_the_original_name(self):
        write(self.root, f"{self.src}/ui/Screen.kt",
              "package com.ethosprotocol.ui\n\n"
              "import com.ethosprotocol.api.Missing as M\n")
        self.assertEqual(len(self.problems()), 1)

    def test_wildcard_imports_are_skipped(self):
        write(self.root, f"{self.src}/ui/Screen.kt",
              "package com.ethosprotocol.ui\n\nimport com.ethosprotocol.api.*\n")
        self.assertEqual(self.problems(), [])

    def test_build_generated_symbols_are_skipped(self):
        write(self.root, f"{self.src}/ui/Screen.kt",
              "package com.ethosprotocol.ui\n\n"
              "import com.ethosprotocol.BuildConfig\n"
              "import com.ethosprotocol.R\n"
              "import com.ethosprotocol.R.string\n")
        self.assertEqual(self.problems(), [])

    def test_non_ethosprotocol_imports_are_ignored(self):
        write(self.root, f"{self.src}/ui/Screen.kt",
              "package com.ethosprotocol.ui\n\nimport androidx.compose.Missing\n")
        self.assertEqual(self.problems(), [])

    def test_build_output_directories_are_not_scanned(self):
        write(self.root, "android/app/build/generated/Stale.kt",
              "package com.ethosprotocol.api\n\nclass Stale\n")
        write(self.root, f"{self.src}/ui/Screen.kt",
              "package com.ethosprotocol.ui\n\nimport com.ethosprotocol.api.Stale\n")
        self.assertEqual(len(self.problems()), 1)

    def test_main_returns_nonzero_when_problems_exist(self):
        write(self.root, f"{self.src}/ui/Screen.kt",
              "package com.ethosprotocol.ui\n\nimport com.ethosprotocol.api.Missing\n")
        self.assertEqual(check_kotlin_imports.main(["--root", str(self.root)]), 1)

    def test_main_returns_zero_when_all_imports_resolve(self):
        write(self.root, f"{self.src}/api/Api.kt",
              "package com.ethosprotocol.api\n\nclass Api\n")
        write(self.root, f"{self.src}/ui/Screen.kt",
              "package com.ethosprotocol.ui\n\nimport com.ethosprotocol.api.Api\n")
        self.assertEqual(check_kotlin_imports.main(["--root", str(self.root)]), 0)


if __name__ == "__main__":
    unittest.main()
