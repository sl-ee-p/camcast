import os
import tempfile
import unittest

from camcast_viewer.config import ConfigError, is_allowed, load_upstream, parse_networks, upstream_from

GOOD = {"CAMCAST_URL": "http://192.168.0.15:8080", "CAMCAST_USER": "camcast", "CAMCAST_PASS": "pw:with=chars"}


class ConfigTest(unittest.TestCase):
    def test_reads_file_with_comments_and_quotes(self):
        with tempfile.NamedTemporaryFile("w", delete=False) as handle:
            handle.write('# phone\nCAMCAST_URL=http://192.168.0.15:8080/\nCAMCAST_USER=camcast\nCAMCAST_PASS="a b=c"\n')
        try:
            up = load_upstream(handle.name)
        finally:
            os.unlink(handle.name)
        self.assertEqual((up.host, up.port, up.prefix, up.user, up.password),
                         ("192.168.0.15", 8080, "", "camcast", "a b=c"))

    def test_password_not_in_repr_or_label(self):
        up = upstream_from(GOOD)
        self.assertNotIn("pw:with", repr(up))
        self.assertNotIn("pw:with", up.label())
        self.assertTrue(up.authorization().startswith("Basic "))

    def test_rejects_bad_values(self):
        for bad in ({**GOOD, "CAMCAST_PASS": ""}, {**GOOD, "CAMCAST_URL": "https://x"},
                    {**GOOD, "CAMCAST_URL": "http://u:p@x:1"}, {**GOOD, "CAMCAST_USER": "a:b"}):
            with self.assertRaises(ConfigError):
                upstream_from(bad)

    def test_missing_file_message_has_no_secret(self):
        with self.assertRaises(ConfigError):
            load_upstream("/nonexistent/upstream")

    def test_allowlist(self):
        nets = parse_networks(["192.168.0.0/24", "127.0.0.0/8"])
        self.assertTrue(is_allowed("192.168.0.42", nets))
        self.assertTrue(is_allowed("::ffff:192.168.0.42", nets))
        self.assertFalse(is_allowed("100.94.211.4", nets))
        self.assertTrue(is_allowed("100.94.211.4", parse_networks(["100.64.0.0/10"])))
        self.assertFalse(is_allowed("192.168.1.2", nets))
        self.assertFalse(is_allowed("garbage", nets))
        with self.assertRaises(ConfigError):
            parse_networks(["300.0.0.0/8"])
