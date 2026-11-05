"""Log file in windows/logs/. Without a console (pythonw) print() would go nowhere."""
import collections
import datetime
import glob
import os
import sys
import threading

import paths


LOG_DIR = paths.data("logs")
KEEP = 7        # newest log files kept


class Recent:
    """The last lines written, with the time each one ended, for the status window.
    Text arrives in pieces (print sends the line and the newline apart), so a line is kept once it ends;
    stdout and stderr each have their own unfinished line."""

    def __init__(self, size=3000):
        self._lock = threading.Lock()
        self._lines = collections.deque(maxlen=size)
        self._partial = {}
        self.count = 0              # lines ever added: a reader asks for what came after the count it saw

    def add(self, text, now=None, stream="out"):
        with self._lock:
            parts = (self._partial.get(stream, "") + text.replace("\r", "\n")).split("\n")
            self._partial[stream] = parts.pop()
            stamp = now or datetime.datetime.now()
            for line in parts:
                if line.strip():
                    self._lines.append((self.count, stamp, line))
                    self.count += 1

    def since(self, seen):
        """[(index, time, text)] of the lines after the first `seen` (only those still kept)."""
        with self._lock:
            return [entry for entry in self._lines if entry[0] >= seen]


RECENT = Recent()


class _Tee:
    """Writes to the log file and, when there is one, to the original stream."""

    def __init__(self, log, original, name="out"):
        self.log, self.original, self.name = log, original, name

    def write(self, text):
        RECENT.add(text, stream=self.name)
        try:
            self.log.write(text)
            self.log.flush()
        except (OSError, ValueError):
            pass
        if self.original is not None:
            try:
                self.original.write(text)
            except (OSError, ValueError):
                pass
        return len(text)

    def flush(self):
        try:
            self.log.flush()
            if self.original is not None:
                self.original.flush()
        except (OSError, ValueError):
            pass

    def isatty(self):
        return False


def old_logs(names, keep=KEEP):
    """Which of the (sorted-by-name) log files to delete so only `keep` newest remain."""
    names = sorted(names)
    return names[:-keep] if len(names) > keep else []


def _log_files(directory):
    """Log files oldest first; the name before the date was "phonemic-" in older versions."""
    files = glob.glob(os.path.join(directory, "droidboard-*.log")) + glob.glob(os.path.join(directory, "phonemic-*.log"))
    return sorted(files, key=lambda f: os.path.basename(f).split("-", 1)[1])


def latest_log(directory=LOG_DIR):
    files = _log_files(directory)
    return files[-1] if files else None


def setup(directory=LOG_DIR):
    """Sends stdout and stderr to today's log file too. Returns the file path (None on failure)."""
    try:
        os.makedirs(directory, exist_ok=True)
        for old in old_logs(_log_files(directory)):
            try:
                os.remove(old)
            except OSError:
                pass
        path = os.path.join(directory, f"droidboard-{datetime.date.today():%Y%m%d}.log")
        log = open(path, "a", encoding="utf-8", buffering=1)
        log.write(f"\n=== Droidboard started {datetime.datetime.now():%Y-%m-%d %H:%M:%S} ===\n")
        sys.stdout = _Tee(log, sys.stdout)
        sys.stderr = _Tee(log, sys.stderr, "err")
        return path
    except OSError:
        return None
