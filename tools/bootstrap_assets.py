#!/usr/bin/env python3
"""Recreate the verified Galaxunil runtime assets without tracking binaries."""
import argparse
import gzip
import hashlib
import json
import lzma
import pathlib
import shutil
import subprocess
import tempfile
import urllib.parse
import zipfile

PROJECT = pathlib.Path(__file__).resolve().parents[1]
LOCK = json.loads((PROJECT / "tools/runtime-lock.json").read_text())
OUT = PROJECT / "build/galaxunil-assets"

def digest(path):
    with path.open("rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()

def verified(path, expected):
    if not path.is_file() or digest(path) != expected:
        raise RuntimeError(f"Missing or SHA-256 mismatch: {path}. Run python3 tools/bootstrap_assets.py.")
    return path

def download(url, out, token=None):
    args = ["curl", "--silent", "--show-error", "--fail", "--location",
            "--proto", "=https", "--proto-redir", "=https",
            "--max-time", "180", "--max-filesize", "104857600"]
    if token:
        args += ["--oauth2-bearer", token]
    flags = [] if out is None else ["-o", str(out)]
    result = subprocess.run([*args, *flags, url], stdout=subprocess.PIPE if out is None else None)
    if result.returncode:
        raise RuntimeError(f"Download failed from {urllib.parse.urlsplit(url).hostname} (curl {result.returncode})")
    return result.stdout

def make_xz(blob, out):
    # Official layer gzip expands to the raw tar. The recorded preview
    # representation used a single deterministic LZMA2 preset-2 CRC64 stream.
    with gzip.open(blob, "rb") as src, lzma.open(out, "wb", preset=2, check=lzma.CHECK_CRC64) as dst:
        shutil.copyfileobj(src, dst, 1 << 20)

def verify(root):
    expected = {}
    checksum_text = {}
    for target in LOCK["roots"]:
        key = f'assets/debian/debian13-{target["architecture"]}.tar.xz'
        expected[key] = target["xzSha256"]
        checksum_text[key + ".sha256"] = target["xzSha256"] + "\n"
    expected.update({f"jniLibs/{key}": pin for key, pin in LOCK["nativeApk"]["libraries"].items()})
    expected["assets/guest/debian-ca-certificates.crt"] = LOCK["certificates"]["bundleSha256"]
    required = set(expected) | set(checksum_text)
    actual = {str(path.relative_to(root)) for path in root.rglob("*") if path.is_file() or path.is_symlink()}
    if actual != required or any((root / name).is_symlink() for name in actual):
        raise RuntimeError("Generated runtime assets are missing, extra, or symlinked. Run python3 tools/bootstrap_assets.py.")
    for name, pin in expected.items():
        verified(root / name, pin)
    for name, value in checksum_text.items():
        if (root / name).read_text() != value:
            raise RuntimeError(f"Runtime asset checksum file mismatch: {name}")

def prepare(args, tempdir):
    work = tempdir / "output"
    native = LOCK["nativeApk"]
    apk = args.upstream_apk or tempdir / "upstream.apk"
    if not args.upstream_apk:
        download(native["url"], apk)
    verified(apk, native["sha256"])
    with zipfile.ZipFile(apk) as z:
        for name in sorted(native["libraries"]):
            out = work / "jniLibs" / name
            out.parent.mkdir(parents=True, exist_ok=True)
            out.write_bytes(z.read(f"lib/{name}"))
            verified(out, native["libraries"][name])

    cert = LOCK["certificates"]
    deb = args.certificates_deb or tempdir / "ca-certificates.deb"
    if not args.certificates_deb:
        download(cert["url"], deb)
    verified(deb, cert["debSha256"])
    unpack = tempdir / "ca"
    subprocess.run(["dpkg-deb", "-x", str(deb), str(unpack)], check=True)
    dest = work / "assets/guest/debian-ca-certificates.crt"
    dest.parent.mkdir(parents=True, exist_ok=True)
    entries = sorted((unpack / "usr/share/ca-certificates/mozilla").glob("*.crt"))
    dest.write_bytes(b"".join(f.read_bytes() for f in entries))

    token = None
    for target in LOCK["roots"]:
        arch = target["architecture"]
        if args.rootfs_dir:
            blob = args.rootfs_dir / target["file"]
        else:
            if token is None:
                query = urllib.parse.urlencode({
                    "service": "registry.docker.io", "scope": "repository:library/debian:pull",
                })
                token = json.loads(download("https://auth.docker.io/token?" + query, None))["token"]
            blob = tempdir / target["file"]
            download("https://registry-1.docker.io/v2/library/debian/blobs/sha256:" + target["sha256"],
                     blob, token)
        verified(blob, target["sha256"])
        out = work / "assets/debian" / f"debian13-{arch}.tar.xz"
        out.parent.mkdir(parents=True, exist_ok=True)
        make_xz(blob, out)
        pathlib.Path(str(out) + ".sha256").write_text(target["xzSha256"] + "\n")
    verify(work)
    if OUT.exists():
        shutil.rmtree(OUT)
    work.replace(OUT)

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--verify", action="store_true", help="Verify already prepared assets (never downloads).")
    p.add_argument("--upstream-apk", type=pathlib.Path)
    p.add_argument("--rootfs-dir", type=pathlib.Path, help="Directory containing pinned official layer .tar.gz files.")
    p.add_argument("--certificates-deb", type=pathlib.Path)
    args = p.parse_args()
    if args.verify:
        verify(OUT)
    else:
        OUT.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix=".galaxunil-bootstrap-", dir=OUT.parent) as d:
            prepare(args, pathlib.Path(d))
    print("Verified Galaxunil runtime assets")

if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, subprocess.CalledProcessError) as e:
        raise SystemExit(str(e)) from None
