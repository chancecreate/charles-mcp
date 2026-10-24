"""Redaction applied before anything reaches an MCP client.

Layers, all always on:

1. header values by name (exact names + credential-ish fragments),
2. URL query parameters by key,
3. structured bodies (JSON, form-encoded) by key, recursively,
4. free-text patterns for well-known token formats (JWTs, bearer tokens, cloud keys, private keys).

Regex-style redaction is a mitigation, not a guarantee: secrets with no recognisable name or shape will
pass through. That limitation is documented in docs/security.md.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from urllib.parse import parse_qsl, quote, urlencode, urlsplit, urlunsplit

REDACTED = "[REDACTED]"

DEFAULT_HEADER_NAMES = frozenset({
    "authorization", "proxy-authorization", "cookie", "set-cookie", "set-cookie2", "x-api-key", "api-key",
    "apikey", "x-auth-token", "x-access-token", "x-refresh-token", "x-csrf-token", "x-xsrf-token",
    "x-amz-security-token", "x-goog-api-key", "ocp-apim-subscription-key", "x-session-id",
    "x-client-secret", "www-authenticate", "proxy-authenticate",
})
# Substrings marking a name as credential-bearing. Deliberately not "auth" (would hit "author").
SENSITIVE_FRAGMENTS = ("token", "secret", "password", "passwd", "api-key", "api_key", "apikey", "session",
                       "signature", "credential")
DEFAULT_QUERY_KEYS = frozenset({
    "key", "code", "sig", "pwd", "pass", "otp", "jwt", "sid", "x-amz-signature", "x-amz-credential",
    "x-amz-security-token", "client_secret", "access_token", "id_token", "refresh_token",
})
DEFAULT_JSON_KEYS = frozenset({
    "password", "passwd", "pwd", "secret", "token", "access_token", "refresh_token", "id_token", "api_key",
    "apikey", "client_secret", "authorization", "cookie", "session", "ssn", "credit_card", "card_number",
    "cardnumber", "cvv", "cvc", "pin", "private_key", "otp",
})

# Shapes of well-known secrets, applied to all text.
TEXT_PATTERNS: tuple[tuple[str, re.Pattern[str]], ...] = (
    ("jwt", re.compile(r"\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}")),
    ("bearer", re.compile(r"(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/=-]{8,}")),
    ("aws_access_key", re.compile(r"\b(AKIA|ASIA)[0-9A-Z]{16}\b")),
    ("google_api_key", re.compile(r"\bAIza[0-9A-Za-z_-]{35}\b")),
    ("github_token", re.compile(r"\bgh[pousr]_[A-Za-z0-9]{36,}\b")),
    ("slack_token", re.compile(r"\bxox[abprs]-[A-Za-z0-9-]{10,}\b")),
    ("stripe_key", re.compile(r"\b[rs]k_(live|test)_[A-Za-z0-9]{16,}\b")),
    ("private_key", re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----")),
)

_MAX_JSON_DEPTH = 64


def _norm(name: str) -> str:
    return name.strip().lower()


@dataclass
class Redactor:
    extra_header_names: frozenset[str] = field(default_factory=frozenset)
    extra_query_keys: frozenset[str] = field(default_factory=frozenset)
    extra_json_keys: frozenset[str] = field(default_factory=frozenset)

    @classmethod
    def from_settings(cls, header_names: list[str], query_keys: list[str], json_keys: list[str]) -> Redactor:
        return cls(frozenset(map(_norm, header_names)), frozenset(map(_norm, query_keys)),
                   frozenset(map(_norm, json_keys)))

    # -- predicates --------------------------------------------------------------------------

    def header_is_sensitive(self, name: str) -> bool:
        n = _norm(name)
        return n in DEFAULT_HEADER_NAMES or n in self.extra_header_names or any(f in n for f in SENSITIVE_FRAGMENTS)

    def query_key_is_sensitive(self, key: str) -> bool:
        k = _norm(key)
        return (k in DEFAULT_QUERY_KEYS or k in DEFAULT_JSON_KEYS or k in self.extra_query_keys
                or any(f in k for f in SENSITIVE_FRAGMENTS))

    def json_key_is_sensitive(self, key: str) -> bool:
        k = _norm(key)
        return k in DEFAULT_JSON_KEYS or k in self.extra_json_keys or any(f in k for f in SENSITIVE_FRAGMENTS)

    # -- transforms --------------------------------------------------------------------------

    def headers(self, headers: list[dict[str, str | None]]) -> tuple[list[dict[str, str | None]], int]:
        out: list[dict[str, str | None]] = []
        n = 0
        for h in headers:
            name = str(h.get("name") or "")
            value = h.get("value")
            if value is not None and value != REDACTED and self.header_is_sensitive(name):
                if not value.endswith(REDACTED):  # the bridge may already have redacted it
                    n += 1
                scheme = value.split(" ", 1)[0] if " " in value and _norm(name).endswith("authorization") else None
                value = f"{scheme} {REDACTED}" if scheme and len(scheme) < 32 else REDACTED
            elif value is not None:
                value, k = self.text(value)
                n += k
            out.append({"name": name, "value": value})
        return out, n

    def url(self, url: str | None) -> tuple[str | None, int]:
        if not url:
            return url, 0
        try:
            parts = urlsplit(url)
        except ValueError:
            return self.text(url)
        n = 0
        netloc = parts.netloc
        if "@" in netloc:  # userinfo (user:pass@host)
            netloc = REDACTED + "@" + netloc.rsplit("@", 1)[1]
            n += 1
        query = parts.query
        if query:
            pairs = parse_qsl(query, keep_blank_values=True)
            new_pairs = []
            for k, v in pairs:
                if self.query_key_is_sensitive(k) and v:
                    new_pairs.append((k, REDACTED))
                    n += 1
                else:
                    red, c = self.text(v)
                    n += c
                    new_pairs.append((k, red))
            query = urlencode(new_pairs, quote_via=quote, safe="[]")
        path, c = self.text(parts.path)
        n += c
        return urlunsplit((parts.scheme, netloc, path, query, parts.fragment)), n

    def text(self, s: str) -> tuple[str, int]:
        n = 0
        for _name, pat in TEXT_PATTERNS:
            s, k = pat.subn(REDACTED, s)
            n += k
        return s, n

    def json_value(self, value: object, depth: int = 0) -> tuple[object, int]:
        if depth > _MAX_JSON_DEPTH:
            return REDACTED, 1
        if isinstance(value, dict):
            out: dict[str, object] = {}
            n = 0
            for k, v in value.items():
                if self.json_key_is_sensitive(str(k)) and v not in (None, "", [], {}):
                    out[k] = REDACTED
                    n += 1
                else:
                    out[k], c = self.json_value(v, depth + 1)
                    n += c
            return out, n
        if isinstance(value, list):
            items = []
            n = 0
            for v in value:
                r, c = self.json_value(v, depth + 1)
                items.append(r)
                n += c
            return items, n
        if isinstance(value, str):
            return self.text(value)
        return value, 0

    def body_text(self, text: str, mime: str | None) -> tuple[str, int, bool]:
        """Redact a decoded body. Returns (text, redaction_count, was_structured)."""
        m = (mime or "").lower()
        if "json" in m or text.lstrip().startswith(("{", "[")):
            try:
                parsed = json.loads(text)
            except ValueError:
                parsed = None
            if parsed is not None:
                red, n = self.json_value(parsed)
                return json.dumps(red, ensure_ascii=False, indent=1), n, True
        if "x-www-form-urlencoded" in m:
            pairs = parse_qsl(text, keep_blank_values=True)
            n = 0
            out = []
            for k, v in pairs:
                if self.json_key_is_sensitive(k) or self.query_key_is_sensitive(k):
                    out.append((k, REDACTED))
                    n += 1
                else:
                    out.append((k, v))
            red, c = self.text(urlencode(out))
            return red, n + c, True
        red, n = self.text(text)
        return red, n, False
