"""EN and IT strings stay in step, and every key the page or server can ask for exists."""
import json
import os
import re
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WEB = os.path.join(ROOT, "web")
REASONS = {"unreachable", "unauthorized", "busy", "stalled", "closed", "bad_response", "error", "not_found"}
STATES = {"online", "connecting", "offline", "standby", "unknown", "noViewer"}


def load(lang):
    with open(os.path.join(WEB, "static", "i18n", f"{lang}.json"), encoding="utf-8") as handle:
        return json.load(handle)


def read(*parts):
    with open(os.path.join(ROOT, *parts), encoding="utf-8") as handle:
        return handle.read()


class I18nTest(unittest.TestCase):
    def setUp(self):
        self.en, self.it = load("en"), load("it")

    def test_same_keys_and_placeholders(self):
        self.assertEqual(sorted(self.en), sorted(self.it))
        for key, text in self.en.items():
            self.assertEqual(sorted(re.findall(r"{\w+}", text)), sorted(re.findall(r"{\w+}", self.it[key])), key)
            self.assertTrue(self.it[key].strip(), key)

    def test_page_keys_exist(self):
        used = set(re.findall(r'data-i18n(?:-alt|-label)?="([^"]+)"', read("web", "index.html")))
        used |= set(re.findall(r'\bt\("([^"]+)"', read("web", "static", "app.js")))
        static = {key for key in used if not key.endswith(".")}  # "state." + x is covered below
        self.assertTrue(static)
        self.assertEqual(static - set(self.en), set())

    def test_every_reason_and_state_has_text(self):
        self.assertEqual({"reason." + r for r in REASONS} - set(self.en), set())
        self.assertEqual({"state." + s for s in STATES} - set(self.en), set())

    def test_server_reasons_are_known(self):
        source = read("camcast_viewer", "upstream.py") + read("camcast_viewer", "hub.py")
        codes = set(re.findall(r'UpstreamError\("(\w+)"\)', source)) | set(re.findall(r'"(\w+)"}\.get', source))
        codes |= set(re.findall(r'\b\d{3}: "(\w+)"', source))
        codes |= set(re.findall(r'^\s*self\._reason = "(\w+)"', source, re.MULTILINE))
        self.assertTrue(codes)
        self.assertEqual(codes - REASONS, set())
