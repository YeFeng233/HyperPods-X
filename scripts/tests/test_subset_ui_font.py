import importlib.util
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "subset-ui-font.py"
spec = importlib.util.spec_from_file_location("subset_ui_font", SCRIPT)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class SubsetUiFontTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.library = Path(self.directory.name)
        self.icons = self.library / "glasmio-icons/src/main/java/io/github/neoruaa/glasmio/icons"
        self.icons.mkdir(parents=True)
        (self.icons / "SfSymbol.kt").write_text("ChevronBack(0x100189)", encoding="utf-8")
        (self.icons / "SfSymbolCatalog.kt").write_text('"battery" to 0x1006EA', encoding="utf-8")

    def test_regular_text_covers_latin_cyrillic_and_ui_punctuation(self):
        points = module.required_codepoints(self.library)
        for character in "AirPods 0123456789 \u0410\u044f\u0401\u0451\u2014\u2026\u00b7":
            self.assertIn(ord(character), points)

    def test_both_symbol_sources_are_kept_without_selecting_all_private_glyphs(self):
        points = module.required_codepoints(self.library)
        self.assertIn(0x100189, points)
        self.assertIn(0x1006EA, points)
        self.assertNotIn(0x103FFF, points)

    def test_argb_colors_are_not_parsed_as_symbol_codepoints(self):
        (self.icons / "SfSymbol.kt").write_text("Color(0x100189FF) ChevronBack(0x10018A)", encoding="utf-8")
        points = module.required_codepoints(self.library)
        self.assertNotIn(0x100189, points)
        self.assertIn(0x10018A, points)

    def test_missing_catalog_fails_instead_of_silently_removing_icons(self):
        (self.icons / "SfSymbolCatalog.kt").unlink()
        with self.assertRaises(FileNotFoundError):
            module.required_codepoints(self.library)

    def test_empty_catalog_is_rejected(self):
        for source in self.icons.glob("*.kt"):
            source.write_text("", encoding="utf-8")
        with self.assertRaises(ValueError):
            module.required_codepoints(self.library)


if __name__ == "__main__":
    unittest.main()
