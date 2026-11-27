import importlib.util
from pathlib import Path
import tempfile
import unittest
import yaml

source = Path(__file__).resolve().parents[1] / 'app/src/main/assets/guest/debian-catalog.py'
spec = importlib.util.spec_from_file_location('debian_catalog', source)
catalog = importlib.util.module_from_spec(spec)
spec.loader.exec_module(catalog)

class CatalogTest(unittest.TestCase):
    def read(self, text):
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / 'Components-amd64.yml'
            f.write_text(text)
            return list(catalog.records([f]))

    def test_real_schema(self):
        result = self.read("""---
File: DEP-11
---
Type: desktop-application
ID: org.mozilla.firefox
Package: firefox-esr
Name: {C: Firefox ESR}
Summary: {C: Web Browser}
Launchable:
  desktop-id: [firefox-esr.desktop]
Categories: [Network, WebBrowser]
---
Type: console-application
Package: not-in-store
Name: {C: Hidden}
---
Type: desktop-application
Package: --unauthenticated
Name: {C: Blocked}
""")
        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]['desktop'], ['firefox-esr.desktop'])
        self.assertEqual(result[0]['categories'], ['Network', 'WebBrowser'])

    def test_untrusted_constructor_is_never_loaded(self):
        with self.assertRaises(yaml.YAMLError):
            self.read("!!python/object:__main__.NoExecution {}")

class BootstrapTLSConfigTest(unittest.TestCase):
    def test_bootstrap_apt_uses_existing_bundle_without_relaxing_tls(self):
        config = source.with_name("60-droidnative-appstream").read_text()
        live = [line.strip() for line in config.splitlines()
                if line.strip() and not line.lstrip().startswith("//")]
        self.assertEqual([line for line in live if "cainfo" in line.lower()], [
            'Acquire::https::deb.debian.org::CaInfo "/etc/ssl/certs/ca-certificates.crt";',
        ])
        for option in ("Verify-Peer", "Verify-Host"):
            self.assertEqual([line for line in live if option in line], [
                f'Acquire::https::{option} "true";',
            ])
        for option in ("AllowInsecureRepositories", "AllowWeakRepositories",
                       "AllowDowngradeToInsecureRepositories"):
            self.assertIn(f'Acquire::{option} "false";', live)
        self.assertIn('Acquire::Check-Valid-Until "true";', live)

class CatalogIconsTest(unittest.TestCase):
    def test_only_safe_cached_64px_metadata(self):
        self.assertEqual(catalog.cached_icon({"Icon": {"cached": [
            {"name": "../../outside.png", "width": 64, "height": 64},
            {"name": "thumble.png", "width": 48, "height": 48},
            {"name": "good.png", "width": 64, "height": 64},
        ]}}), "good.png")

    def test_archive_copies_only_matching_regular_64px_png(self):
        import io
        import tarfile
        import struct
        png = b"\x89PNG\r\n\x1a\n\x00\x00\x00\x0dIHDR" + struct.pack(">II",64,64)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            index = root / "icons.tar"
            with tarfile.open(index, "w") as tf:
                for n,b in [("good.png",png),("extra.png",png),("../evil.png",png),
                            ("broken.png",png[:16] + struct.pack(">II",1200,32))]:
                    m=tarfile.TarInfo(n); m.size=len(b); tf.addfile(m,io.BytesIO(b))
                m=tarfile.TarInfo("bad-link.png");m.type=tarfile.SYMTYPE;m.linkname="/etc/passwd";tf.addfile(m)
            target=root/"cache"
            catalog.sync_icons(index,target,{"good.png","../evil.png","bad-link.png","broken.png"})
            self.assertEqual({x.name for x in target.iterdir()},{".stamp","good.png"})
            self.assertEqual((target/"good.png").read_bytes(),png)
            old=(target/"good.png").stat().st_mtime_ns
            catalog.sync_icons(index,target,{"good.png"})
            self.assertEqual((target/"good.png").stat().st_mtime_ns,old)
