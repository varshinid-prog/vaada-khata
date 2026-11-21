"""HTTP client for the ledger backend (s-vyasa `feature/ledger-backend`). Standard library only.

* Network logic lives ONLY here (and in the default transport below). The dialogue core never imports it.
* No database access and no direct ledger writes: it only calls the backend's HTTP API.
* Every reply is parsed by the strict models in models.py; nothing from the network is trusted.
* Retries: only for calls that are safe to repeat (reads, confirm, cancel, a reversal that carries an
  idempotency key). `propose` and `create_customer` are NEVER retried automatically.
* Logging: method, a path TEMPLATE (never the real ids), status and timing. Bodies, customer names,
  amounts and proposal ids are never logged.
"""
import json
from contextvars import ContextVar
from contextlib import contextmanager
import logging
import re
import socket
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Callable, List, Optional, Tuple

from . import models as m
from .config import LedgerAPIConfig
from .errors import (LedgerBadResponse, LedgerConflict, LedgerHTTPError, LedgerNotFound, LedgerRejected,
                     LedgerTimeout, LedgerUnavailable, LedgerValidation)

log = logging.getLogger("vaada.ledger_api")

# Per-request authorization. ContextVar prevents one merchant's token
# from leaking into another concurrent request.
_ledger_authorization = ContextVar("ledger_authorization", default=None)


@contextmanager
def use_ledger_authorization(value):
    token = _ledger_authorization.set(value)
    try:
        yield
    finally:
        _ledger_authorization.reset(token)


# transport(method, url, body_bytes_or_None, timeout_s, max_bytes) -> (status_code, response_bytes)
Transport = Callable[[str, str, Optional[bytes], float, int], Tuple[int, bytes]]

_PROPOSAL_ID = re.compile(r"^[A-Za-z0-9_\-]{1,64}$")
INTENTS = ("add_credit", "record_payment")


def urllib_transport(method: str, url: str, body: Optional[bytes], timeout: float,
                     max_bytes: int) -> Tuple[int, bytes]:
    """The real network call. Error statuses are returned (not raised); connectivity problems raise."""
    headers = {"Accept": "application/json"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    authorization = _ledger_authorization.get()
    if authorization:
        headers["Authorization"] = authorization
    req = urllib.request.Request(url, data=body, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read(max_bytes + 1)
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read(max_bytes + 1)
    except urllib.error.URLError as exc:
        if isinstance(exc.reason, (socket.timeout, TimeoutError)):
            raise LedgerTimeout("timed out") from None
        raise LedgerUnavailable("cannot connect") from None
    except (socket.timeout, TimeoutError):
        raise LedgerTimeout("timed out") from None
    except (ConnectionError, OSError):
        raise LedgerUnavailable("connection failed") from None


def rupees_text(paise: int) -> str:
    """Exact rupee string from integer paise, integer maths only: 34050 -> '340.50'."""
    if isinstance(paise, bool) or not isinstance(paise, int) or paise <= 0:
        raise ValueError("amount_paise must be a positive integer")
    rupees, rem = divmod(paise, 100)
    return f"{rupees}.{rem:02d}"


class LedgerAPIClient:
    def __init__(self, config: Optional[LedgerAPIConfig] = None, transport: Optional[Transport] = None,
                 sleep: Callable[[float], None] = time.sleep):
        self.config = config or LedgerAPIConfig.from_env()
        self._transport = transport or urllib_transport
        self._sleep = sleep

    # ------------------------------------------------------------------ core request
    def _request(self, method: str, template: str, path: str, *, query: Optional[dict] = None,
                 body: Optional[dict] = None, retry: bool = False) -> Any:
        cfg = self.config
        url = cfg.base_url.rstrip("/") + path
        if query:
            url += "?" + urllib.parse.urlencode(query)
        data = json.dumps(body).encode("utf-8") if body is not None else None
        attempts = 1 + (cfg.max_retries if retry else 0)
        last: Optional[Exception] = None
        for i in range(attempts):
            started = time.monotonic()
            try:
                status, raw = self._transport(method, url, data, cfg.timeout_s, cfg.max_response_bytes)
            except (LedgerTimeout, LedgerUnavailable) as exc:
                last = exc
                log.warning("ledger %s %s -> %s (attempt %d/%d)", method, template, type(exc).__name__, i + 1, attempts)
                if i + 1 < attempts:
                    self._sleep(cfg.retry_backoff_s * (2 ** i))
                continue
            except LedgerBadResponse:
                raise
            except Exception as exc:            # a faulty transport must not leak odd exceptions
                raise LedgerUnavailable(f"transport failure: {type(exc).__name__}") from None
            log.info("ledger %s %s -> %s (%d ms)", method, template, status, (time.monotonic() - started) * 1000)
            if len(raw) > cfg.max_response_bytes:
                raise LedgerBadResponse("reply too large")
            if status in (502, 503, 504):
                last = LedgerUnavailable(f"backend unavailable (HTTP {status})")
                if i + 1 < attempts:
                    self._sleep(cfg.retry_backoff_s * (2 ** i))
                    continue
                raise last
            return self._interpret(status, raw)
        assert last is not None
        raise last

    @staticmethod
    def _interpret(status: int, raw: bytes) -> Any:
        try:
            payload = json.loads(raw.decode("utf-8")) if raw else None
        except (ValueError, UnicodeDecodeError, RecursionError):
            if 200 <= status < 300:
                raise LedgerBadResponse("reply is not valid JSON") from None
            payload = None
        if 200 <= status < 300:
            if payload is None:
                raise LedgerBadResponse("empty reply")
            return payload
        detail = payload.get("detail") if isinstance(payload, dict) else None
        cls = {400: LedgerRejected, 404: LedgerNotFound, 409: LedgerConflict, 422: LedgerValidation}.get(
            status, LedgerHTTPError)
        raise cls(status, detail)

    # ------------------------------------------------------------------ validation helpers
    @staticmethod
    def _proposal_id(pid: Any) -> str:
        if not isinstance(pid, str) or not _PROPOSAL_ID.match(pid):
            raise ValueError("invalid proposal id")
        return pid

    @staticmethod
    def _id(value: Any, what: str) -> int:
        if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
            raise ValueError(f"{what} must be a positive integer")
        return value

    # ------------------------------------------------------------------ proposal workflow
    def propose(self, intent: str, customer_query: str, amount_paise: int, item: Optional[str] = None) -> m.ProposalInfo:
        """Create a backend-controlled pending proposal. Writes NOTHING to the ledger. Never retried."""
        if intent not in INTENTS:
            raise ValueError("intent must be add_credit or record_payment")
        if not isinstance(customer_query, str) or not customer_query.strip() or len(customer_query) > 120:
            raise ValueError("customer_query must be 1-120 characters")
        body = {"intent": intent, "customer_name": customer_query.strip(), "amount": rupees_text(amount_paise),
                "item": item, "confirmation_required": True}
        return m.ProposalInfo.parse(self._request("POST", "/ai/contract/propose", "/ai/contract/propose", body=body))

    def confirm(self, proposal_id: str) -> m.ConfirmInfo:
        """Explicit confirmation. Safe to retry: the backend is idempotent per proposal id."""
        pid = self._proposal_id(proposal_id)
        return m.ConfirmInfo.parse(self._request(
            "POST", "/ai/proposals/{id}/confirm", f"/ai/proposals/{urllib.parse.quote(pid)}/confirm", retry=True))

    def cancel(self, proposal_id: str) -> m.CancelInfo:
        """Cancel a pending proposal. Safe to retry; refused (LedgerRejected) if it was already confirmed."""
        pid = self._proposal_id(proposal_id)
        return m.CancelInfo.parse(self._request(
            "POST", "/ai/proposals/{id}/cancel", f"/ai/proposals/{urllib.parse.quote(pid)}/cancel", retry=True))

    # ------------------------------------------------------------------ customers (reads; create is explicit)
    def health(self) -> dict:
        d = self._request("GET", "/health", "/health", retry=True)
        if not isinstance(d, dict) or d.get("status") != "healthy":
            raise LedgerBadResponse("backend not healthy")
        return d

    def lookup_customer(self, query: str) -> m.LookupResult:
        if not isinstance(query, str) or not query.strip() or len(query) > 120:
            raise ValueError("query must be 1-120 characters")
        return m.LookupResult.parse(self._request("GET", "/customers/lookup", "/customers/lookup",
                                                  query={"q": query.strip()}, retry=True))

    def list_customers(self, limit: int = 500, offset: int = 0) -> List[m.CustomerInfo]:
        if not (isinstance(limit, int) and 1 <= limit <= 500 and isinstance(offset, int) and offset >= 0):
            raise ValueError("limit must be 1..500 and offset >= 0")
        data = self._request("GET", "/customers", "/customers", query={"limit": limit, "offset": offset}, retry=True)
        if not isinstance(data, list):
            raise LedgerBadResponse("customer list is not a list")
        return [m.CustomerInfo.parse(c) for c in data]

    def get_customer(self, customer_id: int) -> m.CustomerInfo:
        cid = self._id(customer_id, "customer_id")
        return m.CustomerInfo.parse(self._request("GET", "/customers/{id}", f"/customers/{cid}", retry=True))

    def create_customer(self, name: str, aliases: Optional[List[str]] = None, phone: Optional[str] = None) -> m.CustomerInfo:
        """Explicit customer creation. NOT used by the voice flow (no automatic creation). Never retried."""
        if not isinstance(name, str) or not name.strip():
            raise ValueError("name is required")
        body = {"name": name.strip(), "aliases": [a.strip() for a in (aliases or []) if isinstance(a, str) and a.strip()]}
        if phone:
            body["phone"] = phone
        return m.CustomerInfo.parse(self._request("POST", "/customers", "/customers", body=body))

    # ------------------------------------------------------------------ ledger reads
    def get_balance(self, customer_id: int) -> m.BalanceInfo:
        cid = self._id(customer_id, "customer_id")
        return m.BalanceInfo.parse(self._request("GET", "/customers/{id}/balance", f"/customers/{cid}/balance", retry=True))

    def get_transactions(self, customer_id: int, limit: int = 20, offset: int = 0) -> List[m.TransactionInfo]:
        cid = self._id(customer_id, "customer_id")
        if not (isinstance(limit, int) and 1 <= limit <= 500 and isinstance(offset, int) and offset >= 0):
            raise ValueError("limit must be 1..500 and offset >= 0")
        data = self._request("GET", "/customers/{id}/transactions", f"/customers/{cid}/transactions",
                             query={"limit": limit, "offset": offset}, retry=True)
        if not isinstance(data, list):
            raise LedgerBadResponse("transaction list is not a list")
        return [m.TransactionInfo.parse(t) for t in data]

    # ------------------------------------------------------------------ reversal (explicit; not wired to voice)
    def reverse_transaction(self, transaction_id: int, reason: Optional[str] = None,
                            idempotency_key: Optional[str] = None) -> m.ReversalInfo:
        """Additive compensating reversal. It WRITES to the ledger immediately (the backend has no
        proposal step for it), so the voice flow never calls it. Retried only if a key is supplied."""
        tid = self._id(transaction_id, "transaction_id")
        body = {}
        if reason:
            body["reason"] = reason
        if idempotency_key:
            body["idempotency_key"] = idempotency_key
        return m.ReversalInfo.parse(self._request(
            "POST", "/transactions/{id}/reverse", f"/transactions/{tid}/reverse",
            body=body or None, retry=bool(idempotency_key)))
