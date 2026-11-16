"""Shared HTTP helpers that bound credentials to one origin.

GitHub answers API requests with redirects to other hosts for release assets.
The standard library keeps every caller-supplied header across a redirect, so a
token added for the API origin reaches the redirect target. ``OriginScopedRedirectHandler``
drops ``Authorization`` as soon as the scheme, host, or effective port changes,
and does not restore it when a later hop returns to the original origin.
"""

from __future__ import annotations

import urllib.request
from urllib.parse import urlsplit

DEFAULT_PORTS = {"http": 80, "https": 443}


def _origin_parts(url: str) -> tuple[str, str, int] | None:
    """Return ``(scheme, host, port)`` for an absolute URL, or ``None``."""
    parts = urlsplit(url)
    scheme = parts.scheme.lower()
    host = (parts.hostname or "").lower()
    if not scheme or not host:
        return None
    try:
        port = parts.port
    except ValueError:
        return None
    if port is None:
        port = DEFAULT_PORTS.get(scheme, 0)
    return scheme, host, port


def same_origin(first: str, second: str) -> bool:
    """Return ``True`` when two absolute URLs share scheme, host, and port."""
    left = _origin_parts(first)
    right = _origin_parts(second)
    return left is not None and left == right


class OriginScopedRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Follows redirects without carrying credentials to another origin."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        redirected = super().redirect_request(req, fp, code, msg, headers, newurl)
        if redirected is not None and not same_origin(req.full_url, newurl):
            redirected.remove_header("Authorization")
        return redirected


def origin_scoped_opener(*handlers: urllib.request.BaseHandler) -> urllib.request.OpenerDirector:
    """Build an opener that follows redirects without cross-origin credentials."""
    return urllib.request.build_opener(OriginScopedRedirectHandler(), *handlers)
