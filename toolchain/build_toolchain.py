#!/usr/bin/env python3
"""
Assemble the Warp on-device toolchain for arm64 Android.

Run this on a PC. It produces a single bundle that Warp unpacks on the phone
into its own data directory and then executes.

    py toolchain/build_toolchain.py --sdk "C:/path/to/Android/Sdk"

What it does, in order:

  1. Downloads every source pinned in sources.json (cached in toolchain/.cache)
  2. Verifies each download against its SHA-256
  3. Unpacks .zip and .deb archives
  4. Lays the pieces out in the directory shape Warp expects on the phone
  5. Deletes the parts of the JDK we never use, to cut the size
  6. Checks that every native library the JVM needs is actually present
  7. Writes MANIFEST.json and zips the result

Nothing here is committed to git. The repo stays small; the bundle is uploaded
to GitHub Releases and fetched at build time.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tarfile
import time
import urllib.request
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
CACHE = HERE / ".cache"
SOURCES = HERE / "sources.json"


# ── small helpers ────────────────────────────────────────────────────────────

def say(msg: str) -> None:
    print(f"  {msg}", flush=True)


def step(msg: str) -> None:
    print(f"\n=== {msg} ===", flush=True)


def sha256_of(path: Path) -> str:
    """Read the file in chunks so a 200 MB JDK doesn't land in RAM at once."""
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def download(url: str, dest: Path) -> Path:
    """Download unless we already have it. Downloads land in .cache and are reused."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists() and dest.stat().st_size > 0:
        say(f"cached  {dest.name}")
        return dest
    say(f"fetching {url}")
    tmp = dest.with_suffix(dest.suffix + ".part")
    req = urllib.request.Request(url, headers={"User-Agent": "warp-toolchain-builder"})
    with urllib.request.urlopen(req) as r, tmp.open("wb") as out:
        shutil.copyfileobj(r, out)
    tmp.replace(dest)
    say(f"got     {dest.name}  ({dest.stat().st_size / 1048576:.1f} MB)")
    return dest


def check_hash(path: Path, expected: str | None, label: str) -> None:
    """
    Verify a download. A null hash in sources.json means 'not pinned yet' —
    we print the real value so it can be pasted in, but we do not fail.
    Release builds must have every hash pinned (see --strict).
    """
    actual = sha256_of(path)
    if expected is None:
        say(f"NOT PINNED  {label}")
        say(f"    sha256: {actual}   <-- paste into sources.json")
        UNPINNED.append((label, actual))
        return
    if actual.lower() != expected.lower():
        raise SystemExit(
            f"\nSHA-256 MISMATCH for {label}\n"
            f"  expected {expected}\n  actual   {actual}\n"
            f"The download does not match what we pinned. Refusing to continue."
        )
    say(f"verified {label}")


UNPINNED: list[tuple[str, str]] = []


# ── .deb extraction (no `ar` binary needed) ──────────────────────────────────

def extract_deb(deb: Path, outdir: Path) -> None:
    """
    A .deb is an `ar` archive holding three members; we only want data.tar.*.
    Windows has no `ar`, and the format is simple, so we parse it directly:
    an 8-byte magic, then per member a 60-byte header (name, then size at
    offset 48) followed by the data, padded to an even length.
    """
    outdir.mkdir(parents=True, exist_ok=True)
    with deb.open("rb") as f:
        if f.read(8) != b"!<arch>\n":
            raise SystemExit(f"{deb.name} is not a .deb (ar) archive")
        while True:
            hdr = f.read(60)
            if len(hdr) < 60:
                break
            name = hdr[0:16].decode().strip().rstrip("/")
            size = int(hdr[48:58].decode().strip())
            data = f.read(size)
            if size % 2:
                f.read(1)  # members are padded to an even offset
            if name.startswith("data.tar"):
                mode = {"xz": "r:xz", "gz": "r:gz", "bz2": "r:bz2", "zst": "r:*"}.get(
                    name.rsplit(".", 1)[-1], "r:*"
                )
                with tarfile.open(fileobj=io.BytesIO(data), mode=mode) as t:
                    t.extractall(outdir)
                return
    raise SystemExit(f"no data.tar member found inside {deb.name}")


# ── minimal ELF reader, to sanity-check the result ───────────────────────────

def elf_info(path: Path):
    """
    Return (needed_libs, runpaths) for a 64-bit ELF, or None if not one.

    We walk the program headers to find PT_DYNAMIC, then read the dynamic
    table. DT_NEEDED (1) entries name required shared libraries; DT_RUNPATH
    (29) is the search path baked into the binary.
    """
    data = path.read_bytes()
    if len(data) < 64 or data[:4] != b"\x7fELF" or data[4] != 2:
        return None
    e_phoff = struct.unpack_from("<Q", data, 0x20)[0]
    e_phentsize = struct.unpack_from("<H", data, 0x36)[0]
    e_phnum = struct.unpack_from("<H", data, 0x38)[0]

    loads, dyn = [], None
    for i in range(e_phnum):
        o = e_phoff + i * e_phentsize
        if o + 56 > len(data):
            return None
        p_type = struct.unpack_from("<I", data, o)[0]
        p_off, p_vaddr = struct.unpack_from("<QQ", data, o + 8)
        p_filesz = struct.unpack_from("<Q", data, o + 32)[0]
        if p_type == 1:      # PT_LOAD
            loads.append((p_vaddr, p_off, p_filesz))
        elif p_type == 2:    # PT_DYNAMIC
            dyn = (p_off, p_filesz)
    if dyn is None:
        return [], []        # statically linked: nothing to resolve

    def vaddr_to_off(v):
        for va, off, sz in loads:
            if va <= v < va + sz:
                return off + (v - va)
        return None

    entries, strtab_v, strsz = [], None, 0
    for o in range(dyn[0], dyn[0] + dyn[1], 16):
        if o + 16 > len(data):
            break
        tag, val = struct.unpack_from("<qQ", data, o)
        if tag == 0:         # DT_NULL ends the table
            break
        entries.append((tag, val))
        if tag == 5:
            strtab_v = val
        elif tag == 10:
            strsz = val
    if strtab_v is None:
        return [], []
    so = vaddr_to_off(strtab_v)
    if so is None:
        return [], []
    strtab = data[so:so + strsz]

    def s(off):
        end = strtab.find(b"\0", off)
        return strtab[off:end].decode("utf-8", "replace")

    needed = [s(v) for t, v in entries if t == 1]
    runpath = [s(v) for t, v in entries if t in (15, 29)]
    return needed, runpath


# Libraries Android itself always provides. Anything required but not in this
# set has to be shipped inside the bundle, or the JVM will fail to start.
#
# libc++_shared.so is deliberately NOT here. It is the NDK C++ runtime, which
# every app is expected to ship itself — it is not part of the platform. It was
# wrongly listed as a system library at first, so this check passed while the
# JVM still failed on the device with:
#     dlopen failed: library "libc++_shared.so" not found
# Only put a name here if Android genuinely guarantees it.
ANDROID_SYSTEM_LIBS = {
    "libc.so", "libm.so", "libdl.so", "liblog.so", "libz.so", "libstdc++.so",
    "libandroid.so", "libjnigraphics.so", "libEGL.so", "libGLESv2.so",
    "libGLESv3.so", "libGLESv1_CM.so", "libOpenSLES.so", "libvulkan.so",
    "libmediandk.so", "libnativewindow.so", "libaaudio.so",
}


# ── the build ────────────────────────────────────────────────────────────────

def build_compose_kit(cfg, bundle: Path, staging: Path, args) -> dict:
    """
    Everything a Compose app needs, made ready before it reaches the phone.

    Compose is a compiler plugin plus about seventy libraries. The plugin
    already ships inside kotlinc — versioned with Kotlin, so there is no
    version matrix to keep in step — which leaves only the libraries, and they
    arrive as AARs that nothing on a phone knows how to prepare.

    So the preparing happens here, once:

      compose/libs/   classes.jar out of each AAR, for the compile classpath
      compose/flat/   each library's resources, already run through aapt2
      compose/dex/    every library's classes, already run through d8
      compose/packages.txt  one package name per line, for --extra-packages

    **The dex step is the reason this exists.** Dexing these on the phone takes
    just under a minute, measured, and it would be the same minute on every
    build for ever. Done here it costs about twenty-five seconds, once, and the
    phone only merges the result — the same trick the engine already uses for
    the Kotlin stdlib.

    Needs `aapt2` and `d8`, both of which the bundle already has by this point:
    the arm64 aapt2 cannot run on the build machine, so the host SDK's copy is
    used for the resource step, while d8 is pure JVM and runs anywhere.
    """
    step("Compose kit (libraries, resources and dex)")
    comp = cfg.get("compose")
    if not comp:
        say("no compose section in sources.json — skipping")
        return {}

    kit = bundle / "compose"
    for d in ("libs", "flat", "dex"):
        (kit / d).mkdir(parents=True, exist_ok=True)
    stage = staging / "compose"
    stage.mkdir(parents=True, exist_ok=True)

    # ── download, verify, unpack ────────────────────────────────────────
    packages: list[str] = []
    res_dirs: list[tuple[str, Path]] = []
    for art in comp["artifacts"]:
        group_path = art["group"].replace(".", "/")
        base = comp["google_maven"] if art["group"].startswith("androidx.") \
            else comp["maven_central"]
        filename = f"{art['name']}-{art['version']}.{art['ext']}"
        url = f"{base}/{group_path}/{art['name']}/{art['version']}/{filename}"

        local = download(url, CACHE / "compose" / filename)
        check_hash(local, art.get("sha256"), f"{art['group']}:{art['name']}")

        flat_name = f"{art['group']}_{art['name']}"
        if art["ext"] == "jar":
            shutil.copy2(local, kit / "libs" / f"{flat_name}.jar")
            continue

        # An AAR is a zip holding classes.jar, res/ and a manifest.
        unpacked = stage / flat_name
        if unpacked.exists():
            shutil.rmtree(unpacked)
        with zipfile.ZipFile(local) as z:
            z.extractall(unpacked)

        classes = unpacked / "classes.jar"
        if classes.is_file():
            shutil.copy2(classes, kit / "libs" / f"{flat_name}.jar")

        res = unpacked / "res"
        if res.is_dir() and any(res.iterdir()):
            res_dirs.append((flat_name, res))

        # Each library's R class must be generated into its own package, and
        # the package name is only stated in the AAR's own manifest.
        man = unpacked / "AndroidManifest.xml"
        if man.is_file():
            m = re.search(r'package="([^"]+)"', man.read_text(encoding="utf-8", errors="replace"))
            if m:
                packages.append(m.group(1))

    jars = sorted((kit / "libs").glob("*.jar"))
    say(f"{len(jars)} libraries, {sum(j.stat().st_size for j in jars) / 1048576:.1f} MB")

    (kit / "packages.txt").write_text(
        "\n".join(sorted(set(packages))) + "\n", encoding="utf-8")
    say(f"{len(set(packages))} packages need an R class")

    # ── resources, through the host SDK's aapt2 ─────────────────────────
    #
    # The host's, not the bundled one: bin/aapt2 is a static arm64 binary and
    # will not run here. Compiled resources are a portable format, so which
    # aapt2 produced them does not matter — only that the versions are close,
    # and both come from the same build-tools release.
    host_aapt2 = find_host_aapt2(args.sdk)
    if host_aapt2 is None:
        say("WARNING: no host aapt2 found — Compose resources not precompiled.")
        say("         Compose builds will not work in this bundle.")
    else:
        for name, res in res_dirs:
            subprocess.run(
                [str(host_aapt2), "compile", "--dir", str(res),
                 "-o", str(kit / "flat" / f"{name}.zip")],
                check=True, capture_output=True,
            )
        say(f"compiled resources for {len(res_dirs)} libraries")

    # ── dex, once, here ────────────────────────────────────────────────
    #
    # The Kotlin stdlib goes in too. It is not a Compose library, but every
    # Compose app needs it and the engine would otherwise dex it separately —
    # and a spike that forgot it produced an APK that installed cleanly and
    # died on launch with ClassNotFoundException: kotlin.jvm.internal.Intrinsics.
    stdlib = bundle / "kotlinc" / "lib" / "kotlin-stdlib.jar"
    to_dex = [str(j) for j in jars]
    if stdlib.is_file():
        to_dex.append(str(stdlib))

    started = time.time()
    subprocess.run(
        ["java", "-Xmx3g", "-cp", str(bundle / "d8" / "r8.jar"),
         "com.android.tools.r8.D8", "--release", "--min-api", "28",
         "--lib", str(bundle / "platform" / "android.jar"),
         "--output", str(kit / "dex"), *to_dex],
        check=True, capture_output=True,
    )
    dexes = sorted((kit / "dex").glob("*.dex"))
    say(f"pre-dexed into {len(dexes)} files, "
        f"{sum(d.stat().st_size for d in dexes) / 1048576:.1f} MB, "
        f"in {time.time() - started:.0f}s")

    return {
        "artifacts": len(comp["artifacts"]),
        "packages": len(set(packages)),
        "dex_files": len(dexes),
        "resources_precompiled": host_aapt2 is not None,
    }


def find_host_aapt2(sdk: str | None) -> Path | None:
    """The build machine's own aapt2, newest build-tools first."""
    if not sdk:
        return None
    build_tools = Path(sdk) / "build-tools"
    if not build_tools.is_dir():
        return None
    for version in sorted(build_tools.iterdir(), reverse=True):
        for candidate in ("aapt2.exe", "aapt2"):
            p = version / candidate
            if p.is_file():
                return p
    return None


def main() -> int:
    ap = argparse.ArgumentParser(description="Build the Warp arm64 toolchain bundle")
    ap.add_argument("--sdk", default=os.environ.get("ANDROID_HOME") or
                                   os.environ.get("ANDROID_SDK_ROOT"),
                    help="Local Android SDK path (for android.jar)")
    ap.add_argument("--out", default=str(HERE / "build"), help="Output directory")
    ap.add_argument("--strict", action="store_true",
                    help="Fail if any source has an unpinned SHA-256 (use for releases)")
    ap.add_argument("--keep-staging", action="store_true",
                    help="Leave the unpacked staging tree in place for inspection")
    args = ap.parse_args()

    cfg = json.loads(SOURCES.read_text(encoding="utf-8"))
    out_root = Path(args.out).resolve()
    staging = out_root / "staging"
    bundle = out_root / "warp-toolchain-arm64"

    if bundle.exists():
        shutil.rmtree(bundle)
    if staging.exists():
        shutil.rmtree(staging)
    for d in (bundle / "bin", bundle / "lib", bundle / "jvm",
              bundle / "kotlinc" / "lib", bundle / "d8", bundle / "platform"):
        d.mkdir(parents=True, exist_ok=True)

    # 1 ── aapt2 and zipalign ------------------------------------------------
    step("aapt2 + zipalign (static AArch64 executables)")
    st = cfg["sdk_tools"]
    zip_path = download(st["url"], CACHE / "android-sdk-tools-aarch64.zip")
    check_hash(zip_path, st["sha256"], "android-sdk-tools")
    with zipfile.ZipFile(zip_path) as z:
        for member in st["take"]:
            target = bundle / "bin" / Path(member).name
            with z.open(member) as src, target.open("wb") as dst:
                shutil.copyfileobj(src, dst)
            say(f"extracted {target.name}  ({target.stat().st_size / 1048576:.1f} MB)")

    # 2 ── the JDK -----------------------------------------------------------
    step("OpenJDK 17 for arm64")
    jd = cfg["jdk"]
    deb = download(jd["url"], CACHE / "openjdk-17-aarch64.deb")
    check_hash(deb, jd["sha256"], "openjdk-17")
    jdk_stage = staging / "jdk"
    extract_deb(deb, jdk_stage)
    src_jdk = jdk_stage / jd["prefix_in_deb"]
    if not src_jdk.is_dir():
        raise SystemExit(f"expected {jd['prefix_in_deb']} inside the .deb, not found")
    shutil.copytree(src_jdk, bundle / "jvm", dirs_exist_ok=True)
    say(f"JDK staged into jvm/")

    # 3 ── trim the JDK ------------------------------------------------------
    step("Trimming the JDK")
    trim = cfg["jdk_trim"]
    freed = 0
    for d in trim["remove_dirs"]:
        p = bundle / "jvm" / d
        if p.is_dir():
            freed += sum(f.stat().st_size for f in p.rglob("*") if f.is_file())
            shutil.rmtree(p)
            say(f"removed {d}/")
    for f in trim["remove_files"]:
        p = bundle / "jvm" / f
        if p.is_file():
            freed += p.stat().st_size
            p.unlink()
            say(f"removed {f}")
    for libname in trim["remove_libs"]:
        for p in (bundle / "jvm").rglob(libname):
            freed += p.stat().st_size
            p.unlink()
            say(f"removed {p.relative_to(bundle / 'jvm')}")
    say(f"freed {freed / 1048576:.1f} MB")

    # 4 ── native support libraries -----------------------------------------
    step("Native libraries the JVM needs but does not ship")
    for item in cfg["support_libs"]["items"]:
        deb = download(item["url"], CACHE / f"{item['name']}.deb")
        check_hash(deb, item["sha256"], item["name"])
        stage = staging / "libs" / item["name"]
        extract_deb(deb, stage)
        found = 0
        for so in stage.rglob("*.so*"):
            if so.is_file() and not so.is_symlink():
                shutil.copy2(so, bundle / "lib" / so.name)
                say(f"took {so.name}")
                found += 1
        if found == 0:
            say(f"WARNING: no .so extracted from {item['name']}")

    # 5 ── kotlinc -----------------------------------------------------------
    step("Kotlin compiler")
    kt = cfg["kotlin"]
    kzip = download(kt["url"], CACHE / f"kotlin-compiler-{kt['version']}.zip")
    check_hash(kzip, kt["sha256"], "kotlin-compiler")
    with zipfile.ZipFile(kzip) as z:
        # We only need the jars. The shell scripts in bin/ assume a desktop
        # layout, so Warp invokes the compiler's main class directly instead.
        for name in z.namelist():
            if name.startswith("kotlinc/lib/") and name.endswith(".jar"):
                target = bundle / "kotlinc" / "lib" / Path(name).name
                with z.open(name) as src, target.open("wb") as dst:
                    shutil.copyfileobj(src, dst)
    jars = list((bundle / "kotlinc" / "lib").glob("*.jar"))
    total = sum(j.stat().st_size for j in jars) / 1048576
    say(f"{len(jars)} jars, {total:.1f} MB")

    # 6 ── d8 ----------------------------------------------------------------
    step("d8 (via r8.jar)")
    r8 = cfg["r8"]
    r8jar = download(r8["url"], CACHE / f"r8-{r8['version']}.jar")
    check_hash(r8jar, r8["sha256"], "r8")
    shutil.copy2(r8jar, bundle / "d8" / "r8.jar")

    # 7 ── android.jar from the local SDK ------------------------------------
    step("android.jar (from the local SDK — not redistributable)")
    if not args.sdk:
        raise SystemExit("no --sdk given and ANDROID_HOME is not set")
    aj = Path(args.sdk) / cfg["android_jar"]["local_sdk_relative"]
    if not aj.is_file():
        raise SystemExit(
            f"android.jar not found at {aj}\n"
            f"Install the platform with: sdkmanager \"platforms;android-37.0\""
        )
    shutil.copy2(aj, bundle / "platform" / "android.jar")
    say(f"copied android.jar ({aj.stat().st_size / 1048576:.1f} MB)")

    # 7b ── the Compose kit --------------------------------------------------
    compose_note = build_compose_kit(cfg, bundle, staging, args)

    # 8 ── sanity check: can every native dependency be satisfied? -----------
    step("Checking native dependencies")
    shipped = {p.name for p in (bundle / "lib").iterdir() if p.is_file()}
    shipped |= {p.name for p in (bundle / "jvm").rglob("*.so") if p.is_file()}
    missing: dict[str, list[str]] = {}
    scanned = 0
    for p in list((bundle / "jvm").rglob("*")) + list((bundle / "bin").iterdir()):
        if not p.is_file() or p.is_symlink():
            continue
        info = elf_info(p)
        if info is None:
            continue
        scanned += 1
        for need in info[0]:
            if need not in shipped and need not in ANDROID_SYSTEM_LIBS:
                missing.setdefault(need, []).append(
                    str(p.relative_to(bundle)).replace("\\", "/"))
    say(f"scanned {scanned} ELF files")
    if missing:
        say("MISSING native libraries — the JVM will not start:")
        for lib, users in sorted(missing.items()):
            say(f"    {lib}  <- needed by {users[0]}"
                + (f" (+{len(users) - 1} more)" if len(users) > 1 else ""))
    else:
        say("all native dependencies satisfied")

    # 9 ── manifest and archive ---------------------------------------------
    step("Writing manifest and packing")
    files = [p for p in bundle.rglob("*") if p.is_file()]
    total_bytes = sum(p.stat().st_size for p in files)
    manifest = {
        "bundle_version": cfg["bundle_version"],
        "abi": cfg["abi"],
        "components": {
            "aapt2": st["version"],
            "zipalign": st["version"],
            "jdk": cfg["jdk"]["version"],
            "kotlinc": kt["version"],
            "d8": r8["version"],
            "android_jar": cfg["android_jar"]["local_sdk_relative"],
        },
        "layout": {
            "bin": "static arm64 executables — run directly",
            "jvm": "OpenJDK 17; launch jvm/bin/java",
            "lib": "extra .so files; put on LD_LIBRARY_PATH",
            "kotlinc/lib": "compiler jars — run on the bundled JVM",
            "d8": "r8.jar, contains d8",
            "platform": "android.jar for the compile classpath",
            "compose": "libs/ for the classpath, flat/ precompiled resources, "
                       "dex/ already dexed, packages.txt for --extra-packages",
        },
        # Recorded so the phone can tell a bundle that can build Compose from
        # one that cannot, rather than finding out three stages into a build.
        "compose": compose_note,
        "env_required": {
            "LD_LIBRARY_PATH": "<toolchain>/jvm/lib:<toolchain>/jvm/lib/server:<toolchain>/lib",
            "JAVA_HOME": "<toolchain>/jvm",
        },
        "file_count": len(files),
        "total_bytes": total_bytes,
        "native_deps_missing": sorted(missing.keys()),
    }
    (bundle / "MANIFEST.json").write_text(
        json.dumps(manifest, indent=2), encoding="utf-8")

    archive = out_root / f"warp-toolchain-arm64-{cfg['bundle_version']}.zip"
    if archive.exists():
        archive.unlink()
    # Written deterministically: fixed timestamps, fixed permissions, sorted
    # order. Two builds from the same pinned sources produce a byte-identical
    # zip, so the bundle's own SHA-256 is meaningful and can be published.
    FIXED_TIME = (1980, 1, 1, 0, 0, 0)
    EXECUTABLE = {"bin/aapt2", "bin/zipalign"}
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for p in sorted(bundle.rglob("*"), key=lambda q: q.relative_to(bundle).as_posix()):
            if not p.is_file():
                continue
            rel = p.relative_to(bundle).as_posix()
            info = zipfile.ZipInfo(rel, date_time=FIXED_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            # 0o755 for things we exec on the phone, 0o644 for the rest.
            is_exec = rel in EXECUTABLE or rel.startswith("jvm/bin/")
            info.external_attr = (0o755 if is_exec else 0o644) << 16
            z.writestr(info, p.read_bytes())

    if not args.keep_staging and staging.exists():
        shutil.rmtree(staging)

    print()
    step("Done")
    say(f"unpacked : {bundle}")
    say(f"           {len(files)} files, {total_bytes / 1048576:.1f} MB")
    say(f"archive  : {archive}")
    say(f"           {archive.stat().st_size / 1048576:.1f} MB")
    say(f"sha256   : {sha256_of(archive)}")

    if UNPINNED:
        print()
        say(f"{len(UNPINNED)} source(s) had no pinned SHA-256:")
        for label, digest in UNPINNED:
            say(f'    "{label}": "{digest}"')
        if args.strict:
            raise SystemExit("\n--strict was given and some hashes are unpinned.")

    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())
