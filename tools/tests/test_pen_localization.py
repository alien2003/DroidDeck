import collections
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "app/src/main/res"
FORMAT = re.compile(r"%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]")


def raw_strings(path):
    return {
        element.attrib["name"]: "".join(element.itertext())
        for element in ET.parse(path).getroot()
        if element.tag == "string" and element.get("translatable") != "false"
    }


class PenLocalizationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        english = raw_strings(RES / "values/strings.xml")
        cls.required = {key: value for key, value in english.items() if key.startswith(("mode_pen", "session_pen_"))}
        language_source = (ROOT / "app/src/main/java/com/droiddeck/launcher/core/AppLanguage.kt").read_text(encoding="utf-8")
        tags = re.findall(r'"([a-zA-Z0-9-]+)"', re.search(r"val supported:.*?listOf\((.*?)\)", language_source, re.S).group(1))
        special = {"id": "in", "es-419": "b+es+419", "pt-BR": "pt"}
        cls.locales = {
            tag: raw_strings(RES / f"values-{special.get(tag, tag.replace('-', '-r'))}/strings.xml")
            for tag in tags if tag != "en"
        }

    def test_the_pen_settings_have_english_strings(self):
        self.assertIn("mode_pen", self.required)
        self.assertIn("session_pen_auto_tablet", self.required)

    def test_every_supported_language_translates_the_pen_settings(self):
        for locale, translated in self.locales.items():
            for key, source in self.required.items():
                with self.subTest(locale=locale, key=key):
                    target = translated.get(key, "")
                    self.assertTrue(target.strip(), "Missing translation would fall back to English")
                    if key.endswith("_note"):
                        self.assertNotEqual(source, target)

    def test_translations_keep_format_arguments_and_escape_apostrophes(self):
        for locale, translated in self.locales.items():
            for key, source in self.required.items():
                with self.subTest(locale=locale, key=key):
                    target = translated.get(key, "")
                    self.assertEqual(collections.Counter(FORMAT.findall(source)), collections.Counter(FORMAT.findall(target)))
                    self.assertNotIn('"', target)
                    self.assertEqual(target.count("'"), target.count("\\'"))


if __name__ == "__main__":
    unittest.main()
