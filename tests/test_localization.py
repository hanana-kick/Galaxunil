import re
import unittest
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

RES = Path(__file__).resolve().parents[1] / "app/src/main/res"
ANDROID = "{http://schemas.android.com/apk/res/android}"

def collect_strings(folder):
    strings = {}
    for path in folder.glob("*.xml"):
        for item in ET.parse(path).getroot().findall("string"):
            name = item.get("name")
            if name in strings:
                raise ValueError(f"{folder.name}: duplicate string {name}")
            strings[name] = item
    return strings

def format_arguments(text):
    return Counter(re.findall(r"%%|%\d+\$(?:\.\d+)?[a-zA-Z]", text or ""))

class LocalizationTest(unittest.TestCase):
    def test_korean_has_every_translatable_visible_string_and_keeps_format_arguments(self):
        en = collect_strings(RES / "values")
        ko = collect_strings(RES / "values-ko")
        expected = {k for k in en if en[k].get("translatable") != "false"}
        self.assertEqual(set(ko), expected)
        for key in sorted(expected):
            with self.subTest(key=key):
                self.assertTrue(ko[key].text and ko[key].text.strip())
                self.assertEqual(format_arguments(en[key].text), format_arguments(ko[key].text))

    def test_native_locale_config_advertises_supported_languages(self):
        config = ET.parse(RES / "xml/locales_config.xml").getroot()
        self.assertEqual({e.attrib[ANDROID + "name"] for e in config.findall("locale")}, {"en", "ko"})
        manifest = ET.parse(RES.parent / "AndroidManifest.xml").getroot()
        self.assertEqual(manifest.find("application").attrib[ANDROID + "localeConfig"], "@xml/locales_config")

    def test_service_manifest_metadata_is_not_localized(self):
        manifest = ET.parse(RES.parent / "AndroidManifest.xml").getroot()
        base = collect_strings(RES / "values")
        translated = collect_strings(RES / "values-ko")
        for prop in manifest.findall(".//service/property"):
            if prop.get(ANDROID + "name") == "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE":
                value = prop.get(ANDROID + "value")
                self.assertTrue(value.startswith("@string/"))
                key = value.removeprefix("@string/")
                self.assertEqual(base[key].get("translatable"), "false")
                self.assertNotIn(key, translated)
