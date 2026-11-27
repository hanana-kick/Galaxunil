#!/usr/bin/env python3
"""Extract the unchanged DroidNative libraries only after verifying the official APK."""
import hashlib
import pathlib
import sys
import zipfile

APKSHA = "37586ce708176e9a950346a0834e6d29ba373ba81c3b369b35e8394b3c414d72"
NATIVE = {"libdroidnative.so", "libproot.so", "libproot-loader.so", "libproot-loader32.so", "libvirgl-server.so"}
ABIS = {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}


def main():
    apk, output = (pathlib.Path(x) for x in sys.argv[1:])
    with apk.open("rb") as f:
        if hashlib.file_digest(f, "sha256").hexdigest() != APKSHA:
            raise RuntimeError("DroidNative beta-0.1 official Play Debug APK checksum mismatch")
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        raise RuntimeError("Choose a new or empty output directory")
    with zipfile.ZipFile(apk) as z:
        for abi in sorted(ABIS):
            directory = output / abi
            directory.mkdir()
            components = NATIVE if abi in {"arm64-v8a", "x86_64"} else NATIVE - {"libproot-loader32.so"}
            for filename in sorted(components):
                data = z.read(f"lib/{abi}/{filename}")
                if not data.startswith(b"\x7fELF"):
                    raise RuntimeError(f"Not an ELF library: {abi}/{filename}")
                (directory / filename).write_bytes(data)


if __name__ == "__main__":
    main()
