#!/usr/bin/env python3
"""Write how the setup went, for its owner to send to UrTV's author if they want to (optimize/share.sh).

Only what helps fix the setup for others: the TV's maker, model and Android version, the computer's system, how
long each step took and where it stopped, memory before and after, the protected-features check, and the agent's
notes. Nothing personal: no city, clubs, favourites or apps, no names, network addresses or file paths.
"""
import datetime
import platform
import re
import os
import shutil
import subprocess
import sys
import urllib.parse
from pathlib import Path

for stream in (sys.stdout, sys.stderr):   # Windows writes pipes in its own code page
    if hasattr(stream, "reconfigure"):
        stream.reconfigure(encoding="utf-8", errors="replace")

RUN = Path(__file__).resolve().parent / "run"
OUT = RUN / "share.md"
QR = RUN / "share-qr.png"
AUTHOR = "https://x.com/A_Albihlal"
# X won't take a message's text from a link, so the QR code opens this page (docs/share/, on GitHub Pages) with the
# summary after the #: one tap there copies it and opens a message to the author in X
PAGE = "https://tshi-dev.github.io/UrTV/share/"


def scrub(text):   # in case a note or an output carries something personal
    text = re.sub(r"\b\d{1,3}(\.\d{1,3}){3}(:\d+)?\b", "<ip>", text)
    text = re.sub(r"\b([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\b", "<mac>", text)
    text = re.sub(r"(?i)\b[A-Z]:[\\/]+Users[\\/]+[^\\/\s]+", "<home>", text)
    text = re.sub(r"(?i)(/c)?/Users/[^/\s]+|/home/[^/\s]+", "<home>", text)
    return text


def tv():
    props = dict(re.findall(r"^(\S+) = (.*)$", (RUN / "tv.txt").read_text(encoding="utf-8"), re.M))
    return (f"{props.get('ro.product.manufacturer', '?')} {props.get('ro.product.model', '?')}, "
            f"Android {props.get('ro.build.version.release', '?')} (SDK {props.get('ro.build.version.sdk', '?')})")


def computer():
    name = platform.system()
    if name == "Windows" or "MINGW" in name.upper() or "MSYS" in name.upper():
        return f"Windows {platform.release()}"
    return {"Darwin": f"macOS {platform.mac_ver()[0]}"}.get(name, f"{name} {platform.release()}")


def steps():
    log = RUN / "steps.log"
    if not log.exists():
        return ["(no steps.log: an older run.sh)"]
    lines, started, first, last, stopped_at = [], {}, None, None, None
    for row in log.read_text(encoding="utf-8").splitlines():
        m = re.match(r"(\S+ \S+) (start|done|stopped) (\S+)(.*)", row)
        if not m:
            continue
        t = datetime.datetime.strptime(m.group(1), "%Y-%m-%d %H:%M:%S")
        first, last = first or t, t
        what, step = m.group(2), m.group(3)
        if what == "start":
            started[step] = t
            if stopped_at:   # the time between a stop and the next try went into finding out why
                gap = int((t - stopped_at).total_seconds())
                lines.append(f"[{gap // 60}:{gap % 60:02d} until it was run again]")
                stopped_at = None
        elif step in started:
            took = int((t - started.pop(step)).total_seconds())
            lines.append(f"{step} {took // 60}:{took % 60:02d}" + ("" if what == "done" else f" STOPPED{m.group(4)}"))
            stopped_at = t if what == "stopped" else None
    total = f"{int((last - first).total_seconds()) // 60} min in all" if first else ""
    return [", ".join(lines), total] if lines else [total]


def memory(summary):   # summary.md's table as short lines: they read better in a message and fit a QR code
    if not summary.exists():
        return ["(not measured)"]
    rows = [[c.strip() for c in l.strip("|").split("|")] for l in summary.read_text(encoding="utf-8").splitlines()
            if l.startswith("|") and not l.startswith("|---")]
    if not rows:
        return ["(not measured)"]
    head, out = rows[0], []
    total = "".join(ch for ch in (head[1] if len(head) > 1 else "") if ch.isdigit())
    for r in rows[1:]:
        if len(r) < 6 or not r[1].isdigit():
            out.append(f"- {r[0]}: {r[1] if len(r) > 1 else ''}")
            continue
        out.append(f"- {r[0]}: {r[1]} MB free{' of ' + total if total else ''}, {r[2]} MB swap, {r[3]} processes, "
                   f"{r[4] or '?'} CPU idle, {r[5]} disabled")
    return out


def report_checks():
    report = RUN / "report.md"
    if not report.exists():
        return ["(no report.md yet: the setup didn't finish)"]
    lines = report.read_text(encoding="utf-8").splitlines()
    ok = sum(l.startswith("- ok:") for l in lines)
    bad = [l[2:] for l in lines if l.startswith("- !!")]
    return [f"Protected features on: {ok}/{ok + len(bad)}", *bad,
            *[l for l in lines if l.startswith(("Home:", "Screensaver:"))]]


def main():
    if not (RUN / "tv.txt").exists():
        sys.exit("!! No setup results here (optimize/run/tv.txt): run the setup first.")
    notes = " ".join(sys.argv[1:]).strip()
    summary = RUN / "summary.md"
    parts = [
        f"UrTV setup report, {datetime.date.today()}",
        "",
        f"TV: {tv()}",
        f"Computer: {computer()}",
        "",
        "Steps, minutes:seconds:",
        *steps(),
        "",
        "Memory:",
        *memory(summary),
        "",
        "Checks:",
        *report_checks(),
    ]
    if notes:
        parts += ["", "Notes from the agent:", notes]
    text = scrub("\n".join(parts)) + "\n"
    OUT.write_text(text, encoding="utf-8")
    print(text)
    print(f"Saved in {OUT.relative_to(RUN.parent.parent)}.")
    # most people use X on their phone, not on this computer: a QR code carries it there
    if qr(PAGE + "#" + urllib.parse.quote(text)):
        print(f"QR code: {QR.relative_to(RUN.parent.parent)}" + (" (open on this screen)" if show(QR) else ""))
        print("Scan it with the phone's camera: a page opens with this text; its button copies it and opens a message")
        print("to the author in X; paste and send.")
    print(("Copied to the clipboard. " if copy(OUT) else "") + f"Or on this computer: {AUTHOR} → Message → paste.")


def qr(link):
    try:
        import segno
    except ImportError:
        print("(no QR code: run optimize/setup.sh once to install segno)")
        return False
    segno.make(link, error="l").save(str(QR), scale=6, border=4)
    return True


def show(path):
    """Open the picture on this computer's screen, if there is a way."""
    try:
        if sys.platform == "win32" or os.environ.get("MSYSTEM"):
            os.startfile(str(path))   # noqa: S606  (Windows' own viewer)
        elif sys.platform == "darwin":
            subprocess.run(["open", str(path)], check=True, timeout=20)
        elif shutil.which("xdg-open"):
            subprocess.run(["xdg-open", str(path)], check=True, timeout=20, capture_output=True)
        else:
            return False
        return True
    except Exception:  # noqa: BLE001
        return False


def copy(path):
    """Best effort: the system's own clipboard tool, if there is one."""
    tools = [["powershell", "-NoProfile", "-Command", f"Get-Content -Raw -Encoding UTF8 '{path}' | Set-Clipboard"],
             ["pbcopy"], ["wl-copy"], ["xclip", "-selection", "clipboard"]]
    for cmd in tools:
        if shutil.which(cmd[0]):
            try:
                feed = None if cmd[0] == "powershell" else path.read_bytes()
                return subprocess.run(cmd, input=feed, capture_output=True, timeout=20).returncode == 0
            except Exception:  # noqa: BLE001
                continue
    return False


if __name__ == "__main__":
    main()
