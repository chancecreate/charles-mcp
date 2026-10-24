import json

from charles_mcp.redaction import REDACTED, Redactor

R = Redactor()


def test_header_redaction_keeps_scheme_and_counts():
    out, n = R.headers([
        {"name": "Authorization", "value": "Bearer abc.def.ghi"},
        {"name": "Cookie", "value": "a=1"},
        {"name": "Set-Cookie", "value": "sid=x; HttpOnly"},
        {"name": "X-Shopify-Access-Token", "value": "shpat_x"},
        {"name": "Content-Type", "value": "application/json"},
    ])
    vals = {h["name"]: h["value"] for h in out}
    assert vals["Authorization"] == f"Bearer {REDACTED}"
    assert vals["Cookie"] == REDACTED
    assert vals["Set-Cookie"] == REDACTED
    assert vals["X-Shopify-Access-Token"] == REDACTED
    assert vals["Content-Type"] == "application/json"
    assert n == 4


def test_already_redacted_by_bridge_is_not_double_counted():
    out, n = R.headers([{"name": "Authorization", "value": f"Bearer {REDACTED}"}])
    assert out[0]["value"] == f"Bearer {REDACTED}"
    assert n == 0


def test_extra_header_names_from_settings():
    r = Redactor.from_settings(["X-Internal-Id"], [], [])
    out, _ = r.headers([{"name": "x-internal-id", "value": "42"}])
    assert out[0]["value"] == REDACTED


def test_url_query_and_userinfo():
    url, n = R.url("https://user:pw@api.test/v1/items?api_key=SECRET1&page=2&access_token=SECRET2&code=abc")
    assert "SECRET1" not in url and "SECRET2" not in url and "pw" not in url and "abc" not in url
    assert "page=2" in url
    assert url.startswith("https://[REDACTED]@api.test/v1/items?")
    assert n == 4


def test_url_without_query_untouched():
    assert R.url("http://a.test/x") == ("http://a.test/x", 0)
    assert R.url(None) == (None, 0)


def test_jwt_in_path_is_redacted():
    jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"
    url, n = R.url(f"https://a.test/callback/{jwt}")
    assert jwt not in url and n == 1


def test_json_body_nested_keys():
    body = json.dumps({"user": "demo", "author": "Ada", "profile": {"password": "p", "plan": "free"},
                       "tokens": [{"access_token": "t1"}], "note": "Bearer abcdefghijklmnop"})
    text, n, structured = R.body_text(body, "application/json")
    data = json.loads(text)
    assert structured
    assert data["profile"]["password"] == REDACTED
    assert data["tokens"] == REDACTED  # key contains "token"
    assert data["author"] == "Ada", "'author' must not be treated as a credential"
    assert data["user"] == "demo"
    assert "abcdefghijklmnop" not in text
    assert n >= 3


def test_form_body():
    text, n, structured = R.body_text("user=a&password=b&client_secret=c", "application/x-www-form-urlencoded")
    assert structured and n == 2
    assert "password=b" not in text and "client_secret=c" not in text and "user=a" in text


def test_free_text_patterns():
    text, n = R.text("key AKIAABCDEFGHIJKLMNOP and ghp_" + "a" * 36 + " and -----BEGIN RSA PRIVATE KEY-----\nMII\n"
                     "-----END RSA PRIVATE KEY-----")
    assert "AKIA" not in text and "ghp_" not in text and "MII" not in text
    assert n == 3


def test_invalid_json_falls_back_to_text_rules():
    text, n, structured = R.body_text("{not json Bearer abcdefghijkl", "application/json")
    assert not structured and REDACTED in text and n == 1


def test_deeply_nested_json_is_bounded():
    deep: object = "leaf"
    for _ in range(100):
        deep = {"a": deep}
    out, n = R.json_value(deep)
    assert n >= 1
