"""Small Streamable HTTP client; cached canonical evidence stays outside the APK."""
import json
import re
import time
import unicodedata
import urllib.request
import uuid

from english_alignment_ledger import require


class QuranEvidence:
    def __init__(self, endpoint="https://mcp.quran.ai"):
        self.endpoint = endpoint
        self.headers = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream"}
        self.last_call = 0.0
        self.rpc("initialize", {"protocolVersion": "2025-03-26", "capabilities": {},
                                "clientInfo": {"name": "beautiful-quran-chapter-review", "version": "1"}})
        self.grounding = self.call("fetch_grounding_rules", {})
        match = re.search(r"<grounding_nonce>([^<]+)", str(self.grounding))
        self.nonce = match.group(1) if match else None
        self.tools = {t["name"]: t for t in self.rpc("tools/list", {})["tools"]}

    def rpc(self, method, params):
        identifier = str(uuid.uuid4())
        request = urllib.request.Request(self.endpoint, json.dumps({"jsonrpc": "2.0", "id": identifier,
            "method": method, "params": params}).encode(), self.headers)
        with urllib.request.urlopen(request, timeout=90) as response:
            if response.headers.get("Mcp-Session-Id"):
                self.headers["Mcp-Session-Id"] = response.headers["Mcp-Session-Id"]
            raw = response.read().decode("utf-8")
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
        for attempt in range(3):
            time.sleep(max(0, self.last_call + 1.1 - time.monotonic()))
            self.last_call = time.monotonic()
            result = self.rpc("tools/call", {"name": name, "arguments": arguments})
            if not result.get("isError"):
                return result
            if "[rate_limited]" not in str(result) or attempt == 2:
                raise ValueError(f"quran.ai {name}: {result.get('content')}")


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
