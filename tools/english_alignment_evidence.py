"""Small Streamable HTTP client; cached canonical evidence stays outside the APK."""
import json
from datetime import datetime, timedelta, timezone
from email.utils import parsedate_to_datetime
import re
import time
import unicodedata
import urllib.request
import urllib.error
import uuid

from english_alignment_ledger import require


def retry_delay(message, retry_after=None):
    """Honor explicit retry guidance; do not probe a rejected service every second."""
    now = time.time()
    if retry_after:
        try:
            return max(1, float(retry_after))
        except ValueError:
            try:
                return max(1, parsedate_to_datetime(retry_after).timestamp() - now)
            except (ValueError, TypeError, OverflowError):
                pass
    if re.search(r'resets? at midnight UTC', message, re.I):
        utc = datetime.fromtimestamp(now, timezone.utc)
        return (utc + timedelta(days=1)).replace(hour=0, minute=0, second=0, microsecond=0).timestamp() - now + 1
    match = re.search(r'retry\s+in\s+(\d+(?:\.\d+)?)\s*(seconds?|minutes?|hours?)', message, re.I)
    if match:
        return max(1, float(match[1]) * {'s': 1, 'm': 60, 'h': 3600}[match[2][0].lower()])
    return 3600 if re.search(r'daily|quota', message, re.I) else 60


class QuranEvidence:
    def __init__(self, endpoint="https://mcp.quran.ai", cancelled=None):
        self.endpoint = endpoint
        self.cancelled = cancelled
        self.headers = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream"}
        self.last_call = 0.0
        self.rpc("initialize", {"protocolVersion": "2025-03-26", "capabilities": {},
                                "clientInfo": {"name": "beautiful-quran-chapter-review", "version": "1"}})
        self.grounding = self.call("fetch_grounding_rules", {})
        match = re.search(r"<grounding_nonce>([^<]+)", str(self.grounding))
        self.nonce = match.group(1) if match else None
        self.tools = {t["name"]: t for t in self.rpc("tools/list", {})["tools"]}

    def pause(self, seconds):
        """Long server cooldowns remain interruptible without resetting the client identity."""
        if self.cancelled is None:
            time.sleep(seconds)
        elif self.cancelled.wait(seconds):
            raise KeyboardInterrupt

    def rpc(self, method, params):
        identifier = str(uuid.uuid4())
        request = urllib.request.Request(self.endpoint, json.dumps({"jsonrpc": "2.0", "id": identifier,
            "method": method, "params": params}).encode(), self.headers)
        while True:
            try:
                with urllib.request.urlopen(request, timeout=90) as response:
                    if response.headers.get("Mcp-Session-Id"):
                        self.headers["Mcp-Session-Id"] = response.headers["Mcp-Session-Id"]
                    raw = response.read().decode("utf-8")
                break
            except urllib.error.HTTPError as error:
                if error.code not in {429, 503}:
                    raise
                delay = retry_delay('', error.headers.get('Retry-After'))
                print(f'Quran.ai HTTP {error.code}: waiting {delay:.0f}s before the same request.', flush=True)
                error.close()
                self.pause(delay)
        # Split on literal LF: Unicode NEL inside Arabic/markup is not an SSE boundary.
        events = [json.loads(line[6:]) for line in raw.split("\n") if line.startswith("data: ")]
        events = events or [json.loads(raw)]
        matching = [e for e in events if e.get("id") == identifier]
        require(len(matching) == 1 and "error" not in matching[0], f"Invalid MCP response for {method}")
        return matching[0]["result"]

    def call(self, name, arguments):
        arguments = dict(arguments)
        schema = getattr(self, "tools", {}).get(name, {}).get("inputSchema", {})
        if getattr(self, "nonce", None) and "grounding_nonce" in schema.get("properties", {}):
            arguments["grounding_nonce"] = self.nonce
        while True:
            self.pause(max(0, self.last_call + 1.1 - time.monotonic()))
            self.last_call = time.monotonic()
            result = self.rpc("tools/call", {"name": name, "arguments": arguments})
            if not result.get("isError"):
                return result
            message = str(result.get('content'))
            if not any(marker in message.lower() for marker in ['[rate_limited]', '[rate limited]', 'daily limit reached']):
                raise ValueError(f"quran.ai {name} failed; no source payload saved")
            delay = retry_delay(message)
            print(f'Quran.ai {name} rate limit: waiting {delay:.0f}s before the same request.', flush=True)
            self.pause(delay)


def structured(result):
    """Accept the server's structured result or its JSON text representation."""
    if "structuredContent" in result:
        return result["structuredContent"]
    for block in result.get("content", []):
        if block.get("type") == "text":
            try:
                return json.loads(block["text"])
            except json.JSONDecodeError:
                pass
    raise ValueError("Missing structured canonical evidence")


def surface(text):
    """Comparison only; preserve original reader text and positions in the ledger."""
    return "".join(c for c in unicodedata.normalize("NFD", text)
                   if unicodedata.category(c)[0] == "L" and c != "ـ").translate(str.maketrans("ٱى", "اي"))
