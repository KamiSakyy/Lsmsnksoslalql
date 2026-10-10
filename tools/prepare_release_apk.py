#!/usr/bin/env python3
"""Keep the Tsuyu release APK small, single-ABI, aligned, and signed."""

from __future__ import annotations

import argparse
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ABI = "arm64-v8a"
MAX_APK_BYTES = 30_000_000


def load_properties(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw_line in path.read_text(encoding="ISO-8859-1").splitlines():
        line = raw_line.strip()
        if not line or line.startswith(("#", "!")):
            continue
        separator = "=" if "=" in line else ":" if ":" in line else None
        if separator is None:
            values[line] = ""
        else:
            key, value = line.split(separator, 1)
            values[key.strip()] = value.strip()
    return values


def run(command: list[str]) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(command, text=True, capture_output=True, check=False)
    if result.returncode != 0:
        if result.stdout:
            print(result.stdout, file=sys.stderr, end="")
        if result.stderr:
            print(result.stderr, file=sys.stderr, end="")
        raise SystemExit(result.returncode)
    return result


def clone_zip_info(source: zipfile.ZipInfo, compression: int) -> zipfile.ZipInfo:
    target = zipfile.ZipInfo(source.filename, source.date_time)
    target.compress_type = compression
    target.create_system = source.create_system
    target.external_attr = source.external_attr
    target.internal_attr = source.internal_attr
    target.comment = source.comment
    return target


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--build-tools", default="36.0.0")
    parser.add_argument("--ndk", default="27.2.12479018")
    parser.add_argument("--keystore", type=Path, required=True)
    parser.add_argument("--signing-properties", type=Path, required=True)
    args = parser.parse_args()

    apk = args.apk.resolve()
    sdk = args.sdk.resolve()
    keystore = args.keystore.resolve()
    properties = load_properties(args.signing_properties)
    for required in ("keyAlias", "storePassword"):
        if not properties.get(required):
            raise SystemExit(f"Missing {required} in signing properties")
    key_password = properties.get("keyPassword") or properties["storePassword"]

    ndk_bin = sdk / "ndk" / args.ndk / "toolchains" / "llvm" / "prebuilt" / "linux-x86_64" / "bin"
    llvm_strip = ndk_bin / "llvm-strip"
    build_tools = sdk / "build-tools" / args.build_tools
    zipalign = build_tools / "zipalign"
    apksigner = build_tools / "apksigner"
    for executable in (llvm_strip, zipalign, apksigner):
        if not executable.is_file():
            raise SystemExit(f"Required Android tool not found: {executable}")

    unsigned = apk.with_name("app-release-stripped-unsigned.apk")
    aligned = apk.with_name("app-release-stripped-aligned.apk")
    signed = apk.with_name("app-release-optimized.apk")
    for stale in (unsigned, aligned, signed):
        stale.unlink(missing_ok=True)

    stripped_count = 0
    before_native = 0
    after_native = 0
    with tempfile.TemporaryDirectory(prefix="tsuyu-strip-") as temp_dir:
        temp_root = Path(temp_dir)
        with zipfile.ZipFile(apk, "r") as original, zipfile.ZipFile(
            unsigned, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9
        ) as optimized:
            for entry in original.infolist():
                name = entry.filename
                parts = name.split("/")

                filename = parts[-1]

                # These desktop/testing libraries ship inside libsignal-client but are
                # not used by the Android client. Exclude them wherever the JAR placed them.
                if (
                    filename == "libsignal_jni_testing.so"
                    or (filename.startswith("libsignal_jni_") and filename.endswith((".so", ".dylib")))
                    or (filename.startswith("signal_jni") and filename.endswith(".dll"))
                ):
                    continue

                # Keep only the native library ABI used by current Android phones.
                if len(parts) >= 2 and parts[0] == "lib" and parts[1] != ABI:
                    continue

                # A repacked APK must not retain the old APK signing metadata.
                upper_name = name.upper()
                if upper_name.startswith("META-INF/") and upper_name.endswith(
                    (".SF", ".RSA", ".DSA", ".EC", "MANIFEST.MF")
                ):
                    continue

                data = original.read(entry)
                compression = entry.compress_type
                if name.startswith(f"lib/{ABI}/") and name.endswith(".so"):
                    native_path = temp_root / Path(*parts[2:])
                    native_path.parent.mkdir(parents=True, exist_ok=True)
                    native_path.write_bytes(data)
                    before_native += len(data)
                    run([str(llvm_strip), "--strip-debug", str(native_path)])
                    data = native_path.read_bytes()
                    after_native += len(data)
                    stripped_count += 1
                    # Android can load page-aligned, uncompressed libraries directly.
                    compression = zipfile.ZIP_STORED

                optimized.writestr(
                    clone_zip_info(entry, compression),
                    data,
                    compress_type=compression,
                    compresslevel=9 if compression == zipfile.ZIP_DEFLATED else None,
                )

    if stripped_count == 0:
        raise SystemExit(f"No {ABI} native libraries found in {apk}")

    aligned_result = run([str(zipalign), "-p", "-f", "4", str(unsigned), str(aligned)])
    if aligned_result.stdout:
        print(aligned_result.stdout, end="")

    signing_type = properties.get("storeType", "PKCS12")
    run(
        [
            str(apksigner),
            "sign",
            "--ks",
            str(keystore),
            "--ks-type",
            signing_type,
            "--ks-key-alias",
            properties["keyAlias"],
            "--ks-pass",
            f"pass:{properties['storePassword']}",
            "--key-pass",
            f"pass:{key_password}",
            "--out",
            str(signed),
            str(aligned),
        ]
    )
    verify = run([str(apksigner), "verify", "--verbose", str(signed)])
    if verify.stdout:
        print(verify.stdout, end="")

    final_size = signed.stat().st_size
    print(f"Retained native ABI: {ABI}; stripped {stripped_count} .so file(s).")
    print(f"Native library bytes: {before_native:,} -> {after_native:,}.")
    print(f"Signed APK size: {final_size:,} bytes (limit {MAX_APK_BYTES:,}).")
    if final_size > MAX_APK_BYTES:
        raise SystemExit("Release APK exceeds the required 30 MB (30,000,000-byte) limit; it will not be published.")

    apk.unlink()
    signed.replace(apk)
    unsigned.unlink(missing_ok=True)
    aligned.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
