"""One persisted, cancellable cooldown for all model workers in a review run."""
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import re
from threading import RLock
import time
from zoneinfo import ZoneInfo


class ModelUnavailableError(RuntimeError):
    """Authentication/access errors require intervention rather than automatic retries."""


class UsageLimitError(ModelUnavailableError):
    """A temporary provider limit; retry the same request after its cooldown."""


def local_timezone():
    """Use the system's named zone so tomorrow's reset also handles DST correctly."""
    try:
        return ZoneInfo(str(Path('/etc/localtime').resolve()).split('/zoneinfo/', 1)[1])
    except (OSError, IndexError, ValueError):
        return datetime.now().astimezone().tzinfo


def reset_deadline(message, now, fallback_seconds=3600):
    """Parse stated reset times; unknown limits use a conservative hourly backoff."""
    current = datetime.fromtimestamp(now, local_timezone())
    relative = re.search(r'(?:retry|try again|resets?)\s+in\s+(\d+(?:\.\d+)?)\s*(seconds?|minutes?|hours?)', message, re.I)
    if relative:
        scale = {'s': 1, 'm': 60, 'h': 3600}[relative[2][0].lower()]
        return now + float(relative[1]) * scale + 60
    if re.search(r'resets? at midnight UTC', message, re.I):
        utc = datetime.fromtimestamp(now, timezone.utc)
        return (utc + timedelta(days=1)).replace(hour=0, minute=0, second=0, microsecond=0).timestamp() + 60
    explicit = re.search(r'(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2})?(?:Z|[+-]\d{2}:\d{2}))', message)
    if explicit:
        try:
            deadline = datetime.fromisoformat(explicit[1].replace('Z', '+00:00')).timestamp()
            if deadline >= now:
                return deadline + 60
        except ValueError:
            pass
    # The CLI reports clock-only daily limits and dated weekly limits in local time.
    dated = re.search(r'(?:try again|resets?)\s+at\s+([A-Za-z]+\s+\d{1,2},?\s+\d{4})[, ]+\s*(\d{1,2}:\d{2}\s*[AP]M)', message, re.I)
    if dated:
        value = dated[1].replace(',', '') + ' ' + re.sub(r'\s*([AP]M)$', r' \1', dated[2].upper())
        for pattern in ['%B %d %Y %I:%M %p', '%b %d %Y %I:%M %p']:
            try:
                deadline = datetime.strptime(value, pattern).replace(tzinfo=current.tzinfo).timestamp()
                return deadline + 60 if deadline >= now else now + fallback_seconds
            except ValueError:
                pass
    clock = re.search(r'(?:try again|resets?)\s+at\s+(\d{1,2}):(\d{2})\s*([AP]M)', message, re.I)
    if clock:
        hour, minute = int(clock[1]), int(clock[2])
        if 1 <= hour <= 12 and 0 <= minute < 60:
            deadline = current.replace(hour=hour % 12 + (12 if clock[3].upper() == 'PM' else 0),
                                       minute=minute, second=0, microsecond=0)
            if deadline.timestamp() <= now:
                deadline += timedelta(days=1)
            return deadline.timestamp() + 60
    return now + fallback_seconds


class UsageWait:
    """Workers share a deadline; waiting spends neither a semantic attempt nor a request."""
    def __init__(self, path, cancelled, clock=time.time, sleeper=None):
        self.path, self.cancelled, self.clock = path, cancelled, clock
        self.sleeper = sleeper or cancelled.wait
        self.lock = RLock()
        self.state = json.loads(path.read_text()) if path.exists() else {}

    def _save(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_suffix('.tmp')
        temporary.write_text(json.dumps(self.state, indent=2) + '\n')
        temporary.replace(self.path)

    def defer(self, message):
        with self.lock:
            # Simultaneous rejected requests share one backoff instead of escalating it.
            already_waiting = self.state.get('until', 0) > self.clock()
            count = self.state.get('consecutive_limits', 0) + (not already_waiting)
            fallback = min(6 * 3600, 3600 * 2 ** min(max(0, count - 1), 3))
            until = max(self.state.get('until', 0), reset_deadline(message, self.clock(), fallback))
            self.state.update(status='waiting', until=until, reason=message,
                              consecutive_limits=count, updated_at=self.clock())
            self._save()
            print('Usage limit: all workers wait until ' + datetime.fromtimestamp(until).astimezone().isoformat(), flush=True)

    def wait(self):
        while True:
            if self.cancelled.is_set():
                raise KeyboardInterrupt
            with self.lock:
                remaining = self.state.get('until', 0) - self.clock()
                if remaining <= 0:
                    if self.state.get('status') == 'waiting':
                        self.state.update(status='ready', updated_at=self.clock())
                        self._save()
                        print('Usage cooldown ended; resuming saved model stages.', flush=True)
                    return
            self.sleeper(min(60, remaining))

    def succeeded(self):
        with self.lock:
            if self.state.get('consecutive_limits'):
                self.state.update(consecutive_limits=0, updated_at=self.clock())
                self._save()
