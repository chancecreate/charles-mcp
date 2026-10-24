import base64
import json

from charles_mcp.bodies import build_body_view, charset_of, looks_textual
from charles_mcp.redaction import Redactor

R = Redactor()


def raw(data: bytes, **kw):
    return {"available": True, "base64": base64.b64encode(data).decode(), "captured_bytes": len(data),
            "truncated": False, "decoded": False, **kw}


def test_bodies_disabled_by_default_message():
    v = build_body_view(raw(b"x"), "text/plain", R, 1000, allow_bodies=False)
    assert not v.available and v.reason == "bodies_disabled" and "CHARLES_MCP_ALLOW_BODIES" in v.hint


def test_binary_is_never_decoded():
    png = b"\x89PNG\r\n\x1a\n" + bytes(range(256))
    v = build_body_view(raw(png), "image/png", R, 1000, True)
    assert v.kind == "binary" and v.text is None and v.sha256_of_returned
    v = build_body_view(raw(b"\x00\x01\x02garbage"), None, R, 1000, True)
    assert v.kind == "binary"
    v = build_body_view(raw(b"plain words"), "application/octet-stream", R, 1000, True)
    assert v.kind == "binary"


def test_json_body_is_redacted_and_marked_untrusted():
    body = json.dumps({"access_token": "synthetic", "ok": True}).encode()
    v = build_body_view(raw(body), "application/json; charset=utf-8", R, 1000, True)
    assert v.kind == "json" and "synthetic" not in v.text and v.redactions == 1
    assert v.untrusted and "untrusted" in v.notice.lower()


def test_text_truncation_flags():
    v = build_body_view(raw(b"a" * 5000, truncated=True), "text/plain", R, 1000, True)
    assert v.truncated and v.text_truncated and len(v.text) == 1000


def test_charset_handling_and_invalid_charset():
    assert charset_of("text/html; charset=ISO-8859-1") == "ISO-8859-1"
    assert charset_of("text/html; charset=nonsense-xyz") == "utf-8"
    v = build_body_view(raw("café".encode("latin-1")), "text/plain; charset=latin-1", R, 1000, True)
    assert v.text == "café"


def test_invalid_utf8_is_replaced_not_crashing():
    v = build_body_view(raw(b"ok \xff\xfe text"), "text/plain", R, 1000, True)
    assert "�" in v.text


def test_truncated_multibyte_tail_still_textual():
    assert looks_textual(None, "héllo wörld".encode()[:-1])


def test_malformed_base64_and_unavailable():
    v = build_body_view({"available": True, "base64": "!!notbase64"}, "text/plain", R, 1000, True)
    assert v.reason == "malformed_body_from_bridge"
    v = build_body_view({"available": False, "reason": "released_by_charles"}, "text/plain", R, 1000, True)
    assert v.reason == "released_by_charles"
