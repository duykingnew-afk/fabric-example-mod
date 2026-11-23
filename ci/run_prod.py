#!/usr/bin/env python3
"""CI helper: run the real (production, intermediary mapped) game with the built mod jar.

The Loom development environment cannot load every mod (Iris fails there: its mixins cannot be remapped),
and players run the production jars anyway. So this installs Minecraft + Fabric Loader the way a launcher
does and starts the game on a virtual display with software OpenGL, with the mods dropped into `mods/`.

  python ci/run_prod.py install
  python ci/run_prod.py run --game-dir prod/iris --mod-jar build/libs/pocketfsr-0.1.0.jar \
        --mods fabric-api sodium iris --shaderpack ci/shaderpack --prefix iris-

A mod can be pinned with `slug=version_number`; otherwise the newest release (else the newest anything)
for Minecraft 1.21.1 on Fabric is taken from Modrinth.
"""
import argparse
import json
import os
import shutil
import signal
import subprocess
import sys
import time
import urllib.parse
import urllib.request

MC_VERSION = "1.21.1"
MC_DIR = os.path.abspath("prod/mc")
OPTIONS_TXT = (
    "onboardAccessibility:false\ngraphicsMode:1\nrenderDistance:8\nguiScale:0\npauseOnLostFocus:false\n"
    "tutorialStep:none\nmaxFps:260\nenableVsync:false\nlang:en_us\n"
)


def http(url, attempts=4):
    for attempt in range(1, attempts + 1):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "pocketfsr-ci (github.com/duykingnew-afk/fabric-example-mod)"})
            with urllib.request.urlopen(request, timeout=120) as response:
                return response.read()
        except Exception as error:  # noqa: BLE001 - retry on any network problem
            print(f"download failed ({error}), attempt {attempt}/{attempts}", flush=True)
            time.sleep(5 * attempt)
    raise SystemExit(f"could not download {url}")


def modrinth_pick(spec):
    """`slug` or `slug=version_number` -> (slug, version_number, download url, file name)."""
    slug, _, pinned = spec.partition("=")
    query = urllib.parse.urlencode({
        "loaders": json.dumps(["fabric"]),
        "game_versions": json.dumps([MC_VERSION]),
    })
    versions = json.loads(http(f"https://api.modrinth.com/v2/project/{slug}/version?{query}"))
    if not versions:
        raise SystemExit(f"no {slug} for Minecraft {MC_VERSION} on Fabric")
    versions.sort(key=lambda v: v["date_published"], reverse=True)
    print(f"{slug}: newest versions:", flush=True)
    for v in versions[:6]:
        print(f"    {v['version_number']:<40} {v['version_type']:<8} {v['date_published'][:10]}", flush=True)
    if pinned:
        matches = [v for v in versions if v["version_number"] == pinned]
        if not matches:
            raise SystemExit(f"{slug} {pinned} not found")
        chosen = matches[0]
    else:
        releases = [v for v in versions if v["version_type"] == "release"]
        chosen = (releases or versions)[0]
    file = next((f for f in chosen["files"] if f.get("primary")), chosen["files"][0])
    return slug, chosen["version_number"], file["url"], file["filename"]


def install():
    import minecraft_launcher_lib as mll

    os.makedirs(MC_DIR, exist_ok=True)
    callback = {"setStatus": lambda text: print(text, flush=True)}
    try:
        print("minecraft-launcher-lib", mll.utils.get_library_version(), flush=True)
    except Exception:  # noqa: BLE001 - only informational
        pass
    mll.install.install_minecraft_version(MC_VERSION, MC_DIR, callback=callback)
    if hasattr(mll, "fabric") and hasattr(mll.fabric, "install_fabric"):
        mll.fabric.install_fabric(MC_VERSION, MC_DIR, callback=callback)
    else:
        mll.mod_loader.get_mod_loader("fabric").install(MC_VERSION, MC_DIR, callback=callback)
    print("installed versions:", sorted(os.listdir(os.path.join(MC_DIR, "versions"))), flush=True)


def fabric_version_id():
    versions = os.path.join(MC_DIR, "versions")
    ids = [d for d in os.listdir(versions) if d.startswith("fabric-loader")] if os.path.isdir(versions) else []
    if not ids:
        raise SystemExit("Fabric Loader is not installed (run the install step first)")
    return sorted(ids)[-1]


def run(args):
    import minecraft_launcher_lib as mll

    game = os.path.abspath(args.game_dir)
    shutil.rmtree(game, ignore_errors=True)
    os.makedirs(os.path.join(game, "mods"))
    shutil.copy(args.mod_jar, os.path.join(game, "mods"))
    used = []
    for spec in args.mods:
        slug, version, url, filename = modrinth_pick(spec)
        used.append(f"{slug} {version} ({filename})")
        print(f"using {slug} {version}", flush=True)
        with open(os.path.join(game, "mods", filename), "wb") as out:
            out.write(http(url))
    used.append(f"pocketfsr ({os.path.basename(args.mod_jar)})")
    with open(os.path.join(game, "mods-used.txt"), "w") as out:
        out.write("\n".join(used) + "\n")

    with open(os.path.join(game, "options.txt"), "w") as out:
        out.write(OPTIONS_TXT)
    with open(os.path.join(game, "eula.txt"), "w") as out:
        out.write("eula=true\n")
    if args.shaderpack:
        shutil.copytree(args.shaderpack, os.path.join(game, "shaderpacks", "pocketfsr-test"))
        os.makedirs(os.path.join(game, "config"), exist_ok=True)
        with open(os.path.join(game, "config", "iris.properties"), "w") as out:
            out.write("enableShaders=true\nshaderPack=pocketfsr-test\n")

    options = {
        "username": "PocketFsr",
        "uuid": "00000000-0000-0000-0000-000000000001",
        "token": "0",
        "executablePath": args.java,
        "gameDirectory": game,
        "jvmArguments": ["-Xmx2G"],
        "enableLoggingConfig": True,
        "customResolution": True,
        "resolutionWidth": "1280",
        "resolutionHeight": "720",
    }
    command = mll.command.get_minecraft_command(fabric_version_id(), MC_DIR, options)
    command = ["xvfb-run", "-a", "-s", "-screen 0 1280x720x24"] + command
    env = dict(
        os.environ,
        POCKETFSR_SMOKETEST="1",
        POCKETFSR_SMOKETEST_PREFIX=args.prefix,
        LIBGL_ALWAYS_SOFTWARE="1",
        MESA_GL_VERSION_OVERRIDE="4.5",
        MESA_GLSL_VERSION_OVERRIDE="450",
    )
    with open(os.path.join(game, "command.txt"), "w") as out:
        out.write(" ".join(command) + "\n")
    print("starting the game, java:", args.java, flush=True)
    with open(os.path.join(game, "launcher-output.log"), "w") as out:
        process = subprocess.Popen(command, cwd=game, env=env, stdout=out, stderr=subprocess.STDOUT,
                                   start_new_session=True)
        try:
            code = process.wait(timeout=args.timeout)
        except subprocess.TimeoutExpired:
            print(f"the game did not finish within {args.timeout} s, killing it", flush=True)
            os.killpg(process.pid, signal.SIGKILL)  # xvfb-run, Xvfb and Java together
            process.wait()
            code = 124
    print("the game exited with code", code, flush=True)
    sys.exit(0 if code == 0 else 1)


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("install")
    run_parser = sub.add_parser("run")
    run_parser.add_argument("--game-dir", required=True)
    run_parser.add_argument("--mod-jar", required=True)
    run_parser.add_argument("--mods", nargs="*", default=[])
    run_parser.add_argument("--shaderpack")
    run_parser.add_argument("--prefix", default="")
    run_parser.add_argument("--java", default="java")
    run_parser.add_argument("--timeout", type=int, default=900)
    args = parser.parse_args()
    if args.command == "install":
        install()
    else:
        run(args)


if __name__ == "__main__":
    main()
