#!/usr/bin/env python3
"""Compile CircuitJS1 from the command line, without Eclipse.

Downloads JDK 8 and the GWT 2.7.0 SDK into .tmp/toolchain on first use, then
runs the GWT compiler. Output goes to war/circuitjs1/.

Usage:
    python tools/build_gwt.py            # full optimized build
    python tools/build_gwt.py --draft    # faster build with less optimization
    python tools/build_gwt.py --embed    # embed version in dist/embed
"""
import argparse
import hashlib
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TMP = ROOT / ".tmp"
TOOLCHAIN = TMP / "toolchain"

GWT_VERSION = "2.7.0"
# The SDK zip at storage.googleapis.com/gwt-releases returns 403, so the jars
# come from Maven Central: (path, artifact, version, classifier, local name).
MAVEN_CENTRAL = "https://repo1.maven.org/maven2"
GWT_JARS = [
    ("com/google/gwt", "gwt-dev", GWT_VERSION, "", "gwt-dev.jar"),
    ("com/google/gwt", "gwt-user", GWT_VERSION, "", "gwt-user.jar"),
    ("javax/validation", "validation-api", "1.0.0.GA", "", "validation-api-1.0.0.GA.jar"),
    ("javax/validation", "validation-api", "1.0.0.GA", "-sources", "validation-api-1.0.0.GA-sources.jar"),
    # The Maven build of gwt-dev does not bundle ASM, unlike the SDK zip.
    ("org/ow2/asm", "asm", "5.0.3", "", "asm-5.0.3.jar"),
    ("org/ow2/asm", "asm-tree", "5.0.3", "", "asm-tree-5.0.3.jar"),
    ("org/ow2/asm", "asm-util", "5.0.3", "", "asm-util-5.0.3.jar"),
    ("org/ow2/asm", "asm-commons", "5.0.3", "", "asm-commons-5.0.3.jar"),
]
GWT_MODULE = "com.lushprojects.circuitjs1.circuitjs1"
EMBED_MODULE = "com.lushprojects.circuitjs1.circuitjs1embed"
EMBED_DIST = ROOT / "dist" / "embed"
EMBED_SCRIPT = "circuitjs1embed.nocache.js"
EMBED_NOTICE = (
    "/* CircuitJS1 by Paul Falstad and Iain Sharp, modified in the circuitjs-embed project. "
    "Free software under the GNU GPL version 2 or later; see COPYING.txt. "
    "Source: https://github.com/pradhyuman-yadav/circuitjs-embed */\n"
)
JDK_API = (
    "https://api.adoptium.net/v3/assets/latest/8/hotspot"
    "?architecture=x64&image_type=jdk&vendor=eclipse&os={os}"
)
OS_NAMES = {"Windows": "windows", "Linux": "linux", "Darwin": "mac"}
USER_AGENT = "circuitjs1-build"


def fetch(url: str) -> urllib.request.addinfourl:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    return urllib.request.urlopen(request, timeout=120)


def download(url: str, target: Path, checksum: str, algorithm: str = "sha256") -> None:
    print(f"Downloading {url}", flush=True)
    target.parent.mkdir(parents=True, exist_ok=True)
    partial = target.with_name(target.name + ".part")
    digest = hashlib.new(algorithm)
    with fetch(url) as response, open(partial, "wb") as out:
        while chunk := response.read(1 << 20):
            digest.update(chunk)
            out.write(chunk)
    if digest.hexdigest().lower() != checksum.lower():
        partial.unlink()
        sys.exit(f"Checksum mismatch for {url}")
    partial.replace(target)


def unpack(archive: Path, destination: Path) -> None:
    print(f"Unpacking {archive.name}", flush=True)
    partial = destination.with_name(destination.name + ".part")
    shutil.rmtree(partial, ignore_errors=True)
    shutil.unpack_archive(str(archive), str(partial))
    partial.replace(destination)
    archive.unlink()


def find_java(jdk_dir: Path) -> Path | None:
    name = "java.exe" if os.name == "nt" else "java"
    matches = sorted(jdk_dir.glob(f"*/bin/{name}"))
    return matches[0] if matches else None


def ensure_jdk() -> Path:
    jdk_dir = TOOLCHAIN / "jdk8"
    java = find_java(jdk_dir)
    if java:
        return java
    os_name = OS_NAMES.get(platform.system())
    if not os_name:
        sys.exit(f"Unsupported platform: {platform.system()}")
    with fetch(JDK_API.format(os=os_name)) as response:
        package = json.load(response)[0]["binary"]["package"]
    archive = TOOLCHAIN / package["name"]
    download(package["link"], archive, package["checksum"])
    unpack(archive, jdk_dir)
    java = find_java(jdk_dir)
    if not java:
        sys.exit(f"No java executable found under {jdk_dir}")
    return java


def ensure_gwt() -> Path:
    sdk = TOOLCHAIN / "gwt" / f"gwt-{GWT_VERSION}"
    for path, artifact, version, classifier, local_name in GWT_JARS:
        target = sdk / local_name
        if target.exists():
            continue
        url = f"{MAVEN_CENTRAL}/{path}/{artifact}/{version}/{artifact}-{version}{classifier}.jar"
        with fetch(url + ".sha1") as response:
            sha1 = response.read().decode().split()[0]
        download(url, target, sha1, "sha1")
    return sdk


def package_embed() -> None:
    """Collect the files a host page needs into dist/embed."""
    compiled = TMP / "embed-war" / "circuitjs1embed"
    shutil.rmtree(EMBED_DIST, ignore_errors=True)
    shutil.copytree(
        compiled,
        EMBED_DIST,
        ignore=shutil.ignore_patterns("circuits", "locale_*", "setuplist.txt"),
    )
    shutil.copytree(ROOT / "war" / "font", EMBED_DIST / "font")
    page = (ROOT / "war" / "circuitjs.html").read_text(encoding="utf-8", errors="replace")
    styles = re.search(r"<style>(.*?)</style>", page, re.DOTALL)
    if not styles:
        sys.exit("No <style> block found in war/circuitjs.html")
    (EMBED_DIST / "embed.css").write_text(styles.group(1), encoding="utf-8")
    shutil.copy(ROOT / "COPYING.txt", EMBED_DIST / "COPYING.txt")
    script = EMBED_DIST / EMBED_SCRIPT
    script.write_bytes(EMBED_NOTICE.encode("utf-8") + script.read_bytes())
    print(f"Embed files: {EMBED_DIST}")


def compile_app(java: Path, sdk: Path, draft: bool, workers: int, embed: bool) -> int:
    classpath = os.pathsep.join(
        [str(ROOT / "src")] + [str(sdk / jar[4]) for jar in GWT_JARS]
    )
    command = [
        str(java),
        "-Xmx3g",
        "-Xss16m",
        f"-Dgwt.persistentunitcachedir={TMP / 'gwt-unitCache'}",
        "-cp",
        classpath,
        "com.google.gwt.dev.Compiler",
        "-war",
        str(TMP / "embed-war" if embed else ROOT / "war"),
        "-workDir",
        str(TMP / "gwt-work"),
        "-deploy",
        str(TMP / "gwt-deploy"),
        "-extra",
        str(TMP / "gwt-extra"),
        "-localWorkers",
        str(workers),
    ]
    if draft:
        command.append("-draftCompile")
    command.append(EMBED_MODULE if embed else GWT_MODULE)
    return subprocess.call(command, cwd=ROOT)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--draft", action="store_true", help="faster, less optimized build")
    parser.add_argument("--workers", type=int, default=4, help="parallel GWT workers")
    parser.add_argument("--embed", action="store_true", help="build the single-file embed version")
    args = parser.parse_args()

    java = ensure_jdk()
    sdk = ensure_gwt()
    result = compile_app(java, sdk, args.draft, args.workers, args.embed)
    if result != 0:
        return result
    if args.embed:
        package_embed()
    else:
        print(f"Build complete: {ROOT / 'war' / 'circuitjs1'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
