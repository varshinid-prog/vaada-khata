"""FastAPI voice gateway.

    Android audio --POST /v1/voice/turn--> Gnani STT --transcript--> DialogueManager --> LedgerAdapter --> ledger backend

This module only glues the pieces together. Dialogue logic, confirmation safeguards and ledger calls all live in
the existing `dialogue` and `ledger_api` packages and are used unchanged. The gateway never writes to a ledger,
never confirms anything itself, and never logs transcripts, names, amounts or proposal ids.

Run (from backend/):  uvicorn voice_gateway.app:app --host 0.0.0.0 --port 8100     Swagger UI: /docs
"""
import base64
import io
import hashlib
import logging
import os
import re
import time
import wave
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from typing import Any, Dict, List, Optional

from dotenv import load_dotenv
from collections import OrderedDict

from fastapi import Depends, FastAPI, File, Form, Header, UploadFile
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from dialogue import DialogueManager
from dialogue import models as M
from ledger_api import LedgerAPIClient, LedgerAPIConfig
from ledger_api.client import use_ledger_authorization
from ledger_api.adapter import LedgerAdapter
from voice_auth.api import get_voice_auth, router as voice_auth_router
from voice_auth.service import VoiceAuthService
from voice_gateway import gnani_stt as G
from voice_gateway import gnani_tts as T

load_dotenv(Path(__file__).resolve().parent.parent / ".env")      # GNANI_API_KEY etc.; the file is never printed
log = logging.getLogger("vaada.gateway")

SESSION_RE = re.compile(r"^[A-Za-z0-9_\-]{1,64}$")
MSG_ID_RE = re.compile(r"^[A-Za-z0-9_\-:.]{1,100}$")
_session_merchant: Dict[str, str] = {}
MAX_AUDIO_BYTES = int(os.environ.get("VAADA_MAX_AUDIO_BYTES") or 5_000_000)
MAX_AUDIO_SECONDS = 30.0                                         # Gnani REST limit
UNCLEAR_REPLY = "Awaaz samajh nahi aayi, dobara bolein."
SPOKEN = {                                                        # short Hindi-English lines Android can show/speak
    "BAD_REQUEST": "Request theek nahi thi, dobara try karein.",
    "BAD_AUDIO": "Audio theek se nahi mili, dobara bolein.",
    "STT_TIMEOUT": "Awaaz samajhne mein der lag rahi hai, dobara bolein.",
    "STT_UNAVAILABLE": "Awaaz samajhne ki service abhi available nahi hai.",
    "STT_RATE_LIMITED": "Thodi der ruk kar dobara bolein.",
    "STT_AUTH": "Awaaz service ki setting mein dikkat hai.",
    "STT_NOT_CONFIGURED": "Awaaz service set nahi hai.",
    "STT_BAD_RESPONSE": "Awaaz samajh nahi aayi, dobara bolein.",
    "INTERNAL": "Kuch gadbad ho gayi. Dobara try karein.",
    "MERCHANT_AUTH_REQUIRED": "Pehle app mein login karein.",
    "AUTH_REQUIRED": "Pehle awaaz se verify karein.",       # only when VAADA_VOICE_AUTH_MODE=enforce
}
HTTP_STATUS = {"MERCHANT_AUTH_REQUIRED": 401, "AUTH_REQUIRED": 401, "BAD_REQUEST": 422, "BAD_AUDIO": 400, "AUDIO_TOO_LARGE": 413, "STT_TIMEOUT": 504,
               "STT_UNAVAILABLE": 503, "STT_RATE_LIMITED": 429, "STT_AUTH": 502, "STT_NOT_CONFIGURED": 503,
               "STT_BAD_RESPONSE": 502, "INTERNAL": 500}


# ====================================================================== response models (shown in Swagger)
class Transcript(BaseModel):
    text: str = Field(description="What Gnani STT heard. Show it to the user; it is the only thing the dialogue acted on.")
    language_code: Optional[str] = Field(None, description="Language detected by the provider, when it says.")


class Reply(BaseModel):
    text: str = Field(description="The answer to this turn (always non-empty).")
    speak: List[str] = Field(description="Sentences to show/speak IN ORDER: [notice, text, followup]. Empty for a "
                                         "duplicate replay (nothing must be spoken twice).")
    kind: str = Field(description="Dialogue kind, e.g. CONFIRMATION_PROMPT, CONFIRMED, CLARIFICATION_PROMPT, "
                                  "CANCELLED, QUERY_PASSTHROUGH, BACKEND_ERROR, REMINDER, DUPLICATE_REPLAY.")


class AudioOut(BaseModel):
    status: str = Field(description="'generated' (audio is in `data`), 'unavailable' (TTS was attempted and failed: use the "
                                    "text) or 'skipped' (TTS was intentionally not run). The text reply is ALWAYS present.")
    reason: Optional[str] = Field(None, description="Why it was skipped/unavailable, e.g. nothing_to_speak, tts_disabled, "
                                                    "not_requested, text_too_long, tts_timeout, tts_auth, tts_rate_limited, "
                                                    "tts_unavailable, tts_invalid_audio, tts_not_configured, tts_busy.")
    mime_type: Optional[str] = Field(None, description="'audio/wav' (a complete WAV file with header, PCM 16-bit).")
    encoding: Optional[str] = Field(None, description="'base64' (standard alphabet, no line breaks).")
    data: Optional[str] = Field(None, description="The WAV file, base64-encoded. Present only when status is 'generated'.")
    sample_rate: Optional[int] = Field(None, description="Read from the WAV header actually received (Hz).")
    channels: Optional[int] = None
    duration_ms: Optional[int] = None


class PendingInfo(BaseModel):
    action_id: str
    intent: str
    customer_name: Optional[str] = None
    amount_paise: Optional[int] = None
    transaction_type: Optional[str] = Field(None, description="CREDIT or PAYMENT")
    needs: List[str] = Field(default_factory=list, description="Open clarification slots: CUSTOMER, AMOUNT, DIRECTION")


class Dialogue(BaseModel):
    state: str = Field(description="IDLE, AWAITING_CLARIFICATION or AWAITING_CONFIRMATION (after the whole turn)")
    awaiting: Optional[str] = Field(None, description="'confirmation' (user must say haan/nahi), 'clarification', or null")
    pending: Optional[PendingInfo] = None


class LedgerOutcome(BaseModel):
    committed: bool = Field(description="True ONLY when the ledger backend confirmed the entry was saved this turn.")
    transaction_id: Optional[Any] = None
    type: Optional[str] = None
    amount_paise: Optional[int] = None
    balance_paise: Optional[int] = Field(None, description="The backend's own balance; never computed here.")
    replayed: bool = False


class VoiceTurnResponse(BaseModel):
    status: str = Field(description="'ok' (dialogue produced a reply) or 'unclear' (nothing usable was heard; "
                                    "the dialogue was NOT called and nothing changed).")
    session_id: str
    transcript: Transcript
    reply: Reply
    dialogue: Optional[Dialogue] = None
    ledger: Optional[LedgerOutcome] = None
    notice: Optional[str] = None
    duplicate: bool = False
    audio: AudioOut = Field(default_factory=lambda: AudioOut(status="skipped", reason="tts_disabled"),
                            description="Spoken version of reply.speak (Gnani Timbre). Optional audio layer: see AudioOut.")


class ErrorBody(BaseModel):
    code: str
    message: str
    recoverable: bool = True


class ErrorResponse(BaseModel):
    status: str = "error"
    error: ErrorBody
    reply: Reply


class GatewayError(Exception):
    def __init__(self, code: str, message: str, recoverable: bool = True):
        self.code, self.message, self.recoverable = code, message, recoverable


# ====================================================================== services (overridable in tests)
@dataclass
class Services:
    stt: G.GnaniSTT
    manager: DialogueManager
    adapter: LedgerAdapter
    tts: Optional[T.GnaniTTS] = None          # optional audio layer; None = no TTS


_services: Optional[Services] = None


def build_services() -> Services:
    adapter = LedgerAdapter(LedgerAPIClient(LedgerAPIConfig.from_env()))
    manager = DialogueManager(120, ledger=adapter, query_resolver=adapter.answer_query)
    return Services(stt=G.GnaniSTT(), manager=manager, adapter=adapter, tts=T.GnaniTTS())


def get_services() -> Services:
    global _services
    if _services is None:
        _services = build_services()
    return _services


app = FastAPI(
    title="Vaada Kaatha voice gateway",
    version="0.1.0",
    description=(
        "One endpoint for the Android app: `POST /v1/voice/turn` takes a recorded utterance, transcribes it with "
        "Gnani Prisma STT, runs it through the dialogue manager (which proposes / confirms / cancels through the ledger "
        "backend) and returns what to show and say. The backend is authoritative for every saved entry. "
        "No authentication: for trusted-LAN demo use only."),
)


app.include_router(voice_auth_router)                  # additive: /v1/auth/* and /v1/admin/voiceprints/*
_session_account: "OrderedDict[str, str]" = OrderedDict()      # gateway session -> account it was last authenticated as


@app.exception_handler(GatewayError)
def _gateway_error(_request, exc: GatewayError):
    return _error(exc.code, exc.message, exc.recoverable)


@app.exception_handler(RequestValidationError)
def _validation_error(_request, exc: RequestValidationError):
    fields = sorted({str(e.get("loc", ["?"])[-1]) for e in exc.errors()})
    return _error("BAD_REQUEST", "missing or invalid field(s): " + ", ".join(fields))


def _error(code: str, message: str, recoverable: bool = True) -> JSONResponse:
    spoken = SPOKEN.get(code, SPOKEN["INTERNAL"] if code not in ("AUDIO_TOO_LARGE",) else SPOKEN["BAD_AUDIO"])
    body = ErrorResponse(error=ErrorBody(code=code, message=message, recoverable=recoverable),
                         reply=Reply(text=spoken, speak=[spoken], kind="ERROR"))
    return JSONResponse(status_code=HTTP_STATUS.get(code, 500), content=body.model_dump())


# ====================================================================== helpers
def _audio_name(upload: UploadFile) -> str:
    """Gnani detects the format from the file-name extension, so make sure it has a supported one."""
    ext = (upload.filename or "").rsplit(".", 1)[-1].lower() if "." in (upload.filename or "") else ""
    if ext not in G.AUDIO_EXTENSIONS:
        ext = G.CONTENT_TYPE_EXT.get((upload.content_type or "").split(";")[0].strip().lower(), "")
    if ext not in G.AUDIO_EXTENSIONS:
        raise GatewayError("BAD_AUDIO", "unsupported audio type; send wav, mp3, ogg, flac, aac or m4a")
    return f"audio.{ext}"


def _check_wav(data: bytes) -> None:
    if data[:4] == b"RIFF":
        try:
            with wave.open(io.BytesIO(data)) as w:
                seconds = w.getnframes() / float(w.getframerate() or 1)
        except (wave.Error, EOFError, ZeroDivisionError):
            raise GatewayError("BAD_AUDIO", "the WAV file could not be read") from None
        if seconds > MAX_AUDIO_SECONDS:
            raise GatewayError("BAD_AUDIO", f"audio longer than {int(MAX_AUDIO_SECONDS)} seconds")


def _usable(text: str) -> bool:
    return len(re.sub(r"[\W_]+", "", text, flags=re.UNICODE)) >= 2




def _voice_customer_create_name(text: str) -> Optional[str]:
    """Return a name only for an explicit spoken create-customer command."""
    value = re.sub(r"[!?.,;]+", " ", (text or "").strip())
    value = re.sub(r"\s+", " ", value).strip()
    patterns = [
        r"^(.{1,80}?)\s+naam\s+ka\s+(?:ek\s+)?(?:naya\s+)?customer\s+(?:naya\s+wala\s+)?(?:banao|bana\s+do|create\s+karo|add\s+karo)$",
        r"^(.{1,80}?)\s+naam\s+ki\s+(?:ek\s+)?(?:nayi\s+)?customer\s+(?:banao|bana\s+do)$",
        r"^(?:naya\s+)?customer\s+(?:banao|bana\s+do)\s+(.{1,80})$",
        r"^(?:create|add)\s+(?:a\s+)?(?:new\s+)?customer\s+(?:named\s+)?(.{1,80})$",
        r"^(.{1,80})\s+naam\s+ka\s+customer\s+(?:banao|bana\s+do)$",
    ]
    for pattern in patterns:
        match = re.match(pattern, value, flags=re.IGNORECASE)
        if match:
            name = match.group(1).strip(" \t-'")
            if name and len(name) <= 80 and not re.search(r"\d", name):
                return name
    return None


def _create_customer_voice_reply(services: "Services", session_id: str, transcript: str,
                                 language_code: Optional[str], customer_name: str,
                                 want_audio: bool) -> VoiceTurnResponse:
    """Create only after explicit user wording; never create for an ordinary transaction request."""
    client = services.adapter._client
    lookup = client.lookup_customer(customer_name)
    if lookup.status == "EXACT_MATCH" and lookup.customer is not None:
        spoken = f"{lookup.customer.name} pehle se customer list mein hai."
        kind = "CUSTOMER_ALREADY_EXISTS"
    elif lookup.status == "AMBIGUOUS_MATCH":
        spoken = lookup.clarification_prompt or f"{customer_name} naam ke kai customers mile. Kripya poora naam batayein."
        kind = "CUSTOMER_NEEDS_CLARIFICATION"
    elif lookup.status == "NOT_FOUND":
        created = client.create_customer(customer_name)
        services.adapter.invalidate_customers()
        spoken = f"{created.name} naya customer successfully add ho gaya."
        kind = "CUSTOMER_CREATED"
    else:
        raise RuntimeError("Unexpected customer lookup status")
    return VoiceTurnResponse(
        status="ok", session_id=session_id,
        transcript=Transcript(text=transcript, language_code=language_code),
        reply=Reply(text=spoken, speak=[spoken], kind=kind),
        dialogue=Dialogue(state="IDLE", awaiting=None, pending=None),
        ledger=None, duplicate=False, audio=_speak(services, [spoken], want_audio))



def _speak(services: "Services", speak: List[str], want_audio: bool) -> AudioOut:
    """Turn the FINAL reply text into audio. Runs strictly AFTER the dialogue/ledger work is finished, never raises, and has no
    way to influence a ledger action: it only reads text. Nothing about the text or audio is logged.

    Duplicate replays arrive with an empty `speak` and are therefore skipped (nothing is ever spoken twice, and no audio is
    cached or regenerated)."""
    if not want_audio:
        return AudioOut(status="skipped", reason="not_requested")
    text = " ".join(s.strip() for s in speak if s and s.strip())
    if not text:
        return AudioOut(status="skipped", reason="nothing_to_speak")
    tts = services.tts
    if tts is None or not tts.enabled:
        return AudioOut(status="skipped", reason="tts_disabled")
    try:
        r = tts.synthesize(text)
    except T.TTSError as exc:
        log.warning("voice turn: tts unavailable (%s)", exc.code)
        return AudioOut(status="skipped" if isinstance(exc, T.TTSTextTooLong) else "unavailable", reason=exc.code)
    except Exception as exc:
        log.error("voice turn: tts failed unexpectedly (%s)", type(exc).__name__)
        return AudioOut(status="unavailable", reason="tts_error")
    return AudioOut(status="generated", mime_type=r.mime_type, encoding="base64",
                    data=base64.b64encode(r.audio).decode("ascii"), sample_rate=r.sample_rate, channels=r.channels,
                    duration_ms=r.duration_ms)


def _dialogue_view(r: M.TurnResult) -> Dialogue:
    p = r.pending
    pending = PendingInfo(action_id=p.action_id, intent=p.intent, customer_name=p.customer_name,
                          amount_paise=p.amount_paise, transaction_type=p.transaction_type,
                          needs=list(p.needs)) if p else None
    awaiting = {M.AWAITING_CONFIRMATION: "confirmation", M.AWAITING_CLARIFICATION: "clarification"}.get(r.state)
    return Dialogue(state=r.state, awaiting=awaiting, pending=pending)


# ====================================================================== routes
@app.post(
    "/v1/voice/turn", response_model=VoiceTurnResponse, tags=["Voice"],
    summary="One spoken turn: audio in, reply out",
    responses={400: {"model": ErrorResponse, "description": "BAD_AUDIO: unreadable, empty, unsupported or too long"},
               413: {"model": ErrorResponse, "description": "AUDIO_TOO_LARGE"},
               422: {"model": ErrorResponse, "description": "BAD_REQUEST: bad session_id / language / client_msg_id"},
               429: {"model": ErrorResponse, "description": "STT_RATE_LIMITED"},
               502: {"model": ErrorResponse, "description": "STT_AUTH / STT_BAD_RESPONSE: provider problem"},
               503: {"model": ErrorResponse, "description": "STT_UNAVAILABLE / STT_NOT_CONFIGURED"},
               504: {"model": ErrorResponse, "description": "STT_TIMEOUT"}})
def voice_turn(
    audio: UploadFile = File(..., description="Recorded utterance: wav, mp3, ogg, flac, aac or m4a, up to 30 s "
                                              "(Gnani resamples to 16 kHz mono itself)."),
    session_id: str = Form(..., description="Stable id for this conversation, 1-64 chars of A-Z a-z 0-9 _ -. "
                                            "Pending confirmations live under it for 120 s."),
    language: str = Form("hi-IN,en-IN", description="'hi-IN,en-IN' (default, Hindi+English mix), a single code like "
                                                     "'hi-IN', up to 4 comma-separated codes, or 'indic-auto'."),
    client_msg_id: Optional[str] = Form(None, description="Optional unique id per recording. If the app retries the "
                                                          "SAME upload with the same id, the dialogue replays its "
                                                          "earlier answer and nothing is done twice."),
    x_voice_want_audio: Optional[str] = Header(None, description="Optional. 'false' skips text-to-speech for this turn (saves "
                                                                 "credits and latency). Only matters when the gateway runs "
                                                                 "with VAADA_TTS_ENABLED=true."),
    x_voice_auth_token: Optional[str] = Header(None, description="Voice-authentication token from POST /v1/auth/verify. "
                                                                  "Only checked when the gateway runs with "
                                                                  "VAADA_VOICE_AUTH_MODE=enforce; ignored otherwise."),
    authorization: Optional[str] = Header(None, description="Merchant login bearer token from /auth/login or /auth/register."),
    services: Services = Depends(get_services),
    auth: VoiceAuthService = Depends(get_voice_auth),
):
    t0 = time.monotonic()
    want_audio = (x_voice_want_audio or "true").strip().lower() not in ("0", "false", "no", "off")
    if not SESSION_RE.match(session_id or ""):
        raise GatewayError("BAD_REQUEST", "session_id must be 1-64 chars of A-Z a-z 0-9 _ -")
    if not authorization or not authorization.strip().lower().startswith("bearer "):
        raise GatewayError("MERCHANT_AUTH_REQUIRED", "merchant login required")
    merchant_token = authorization.strip()
    merchant_fingerprint = hashlib.sha256(merchant_token.encode()).hexdigest()
    previous_merchant = _session_merchant.get(session_id)
    if previous_merchant is not None and previous_merchant != merchant_fingerprint:
        services.manager.end_session(session_id)
    _session_merchant[session_id] = merchant_fingerprint
    if auth.enforcing:                       # authentication gate: before STT, dialogue or any ledger call
        account = auth.authorize(x_voice_auth_token, session_id)        # server-side lookup; the client cannot assert it
        if account is None:
            raise GatewayError("AUTH_REQUIRED", "voice authentication required for this session")
        previous = _session_account.get(session_id)
        if previous is not None and previous != account:
            services.manager.end_session(session_id)    # one account's pending confirmation is never confirmable by another
        _session_account[session_id] = account
        _session_account.move_to_end(session_id)
        while len(_session_account) > 10_000:
            _session_account.popitem(last=False)
    if client_msg_id is not None and not MSG_ID_RE.match(client_msg_id):
        raise GatewayError("BAD_REQUEST", "client_msg_id has invalid characters")
    lang = G.normalise_language(language)
    if lang is None:
        raise GatewayError("BAD_REQUEST", "language must be indic-auto or 1-4 of: " + ", ".join(sorted(G.SUPPORTED_CODES)))
    data = audio.file.read(MAX_AUDIO_BYTES + 1)
    if len(data) > MAX_AUDIO_BYTES:
        raise GatewayError("AUDIO_TOO_LARGE", f"audio larger than {MAX_AUDIO_BYTES} bytes")
    if len(data) < 100:
        raise GatewayError("BAD_AUDIO", "audio is empty")
    name = _audio_name(audio)
    _check_wav(data)

    try:
        stt = services.stt.transcribe(data, name, audio.content_type or "application/octet-stream", lang)
    except G.STTError as exc:
        log.warning("voice turn: stt failed (%s)", exc.code)
        raise GatewayError(exc.code, str(exc), recoverable=exc.code != "STT_NOT_CONFIGURED") from None

    if not _usable(stt.text):                       # silence / noise: never reaches the dialogue, never confirms
        spoken = UNCLEAR_REPLY
        return VoiceTurnResponse(status="unclear", session_id=session_id,
                                 transcript=Transcript(text=stt.text, language_code=stt.language_code),
                                 reply=Reply(text=spoken, speak=[spoken], kind="UNCLEAR"),
                                 audio=_speak(services, [spoken], want_audio))

    try:
        with use_ledger_authorization(merchant_token):
            create_name = _voice_customer_create_name(stt.text)
            if create_name:
                return _create_customer_voice_reply(
                    services, session_id, stt.text, stt.language_code, create_name, want_audio)
            known_customers = services.adapter.known_customers()
            result = services.manager.process_text(
                session_id, stt.text, known_customers=known_customers, today=date.today(),
                message_id=client_msg_id)
    except Exception as exc:                         # the manager is built not to raise; never leak internals if it does
        log.error("voice turn: dialogue failed (%s)", type(exc).__name__)
        raise GatewayError("INTERNAL", "dialogue failed", recoverable=True) from None

    lr = result.ledger_result
    ledger = LedgerOutcome(committed=True, transaction_id=lr.get("transaction_id"), type=lr.get("type"),
                           amount_paise=lr.get("amount_paise"), balance_paise=lr.get("balance_paise"),
                           replayed=bool(lr.get("replayed"))) if lr else None
    log.info("voice turn: ok kind=%s state=%s committed=%s %d ms", result.kind, result.state, ledger is not None,
             (time.monotonic() - t0) * 1000)
    speak = result.utterances()
    return VoiceTurnResponse(
        status="ok", session_id=session_id,
        transcript=Transcript(text=stt.text, language_code=stt.language_code),
        reply=Reply(text=result.message, speak=speak, kind=result.kind),
        dialogue=_dialogue_view(result), ledger=ledger, notice=result.notice, duplicate=result.duplicate,
        audio=_speak(services, speak, want_audio))          # the ledger work is already finished; TTS cannot change it


@app.get("/health", tags=["System"], summary="Gateway status (no secrets)")
def health(services: Services = Depends(get_services)) -> Dict[str, Any]:
    try:
        services.adapter._client.health()
        ledger = True
    except Exception:
        ledger = False
    return {"status": "ok", "stt_configured": services.stt.configured, "ledger_reachable": ledger}
