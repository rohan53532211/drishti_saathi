"""Thin Sarvam AI client: speech-to-text (Saaras), chat (Sarvam-105B) and text-to-speech (Bulbul).

Endpoints and fields follow docs.sarvam.ai (checked 12 Nov 2026):
  POST https://api.sarvam.ai/speech-to-text        multipart: file, model, language_code   -> {"transcript", ...}
  POST https://api.sarvam.ai/v1/chat/completions   JSON: model, messages, response_format   -> choices[0].message.content
  POST https://api.sarvam.ai/text-to-speech        JSON: text, language_code, model, speaker -> {"audios": [base64 wav]}
Auth: header "api-subscription-key". Model and speaker names come from the environment so a rename needs no code change.

Errors are turned into a short [SarvamError] code. The raw Sarvam response and the key are never passed on.
"""
from __future__ import annotations

import logging
import os
from dataclasses import dataclass
from typing import Any, Optional

import httpx

log = logging.getLogger("drishti.sarvam")


class SarvamError(Exception):
    """code is one of NOT_CONFIGURED, SARVAM_AUTH, RATE_LIMITED, SARVAM_REJECTED, SARVAM_UNAVAILABLE, NETWORK, BAD_RESPONSE."""

    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


@dataclass
class SarvamConfig:
    api_key: str = ""
    base_url: str = "https://api.sarvam.ai"
    stt_model: str = "saaras:v4"
    chat_model: str = "sarvam-105b"
    tts_model: str = "bulbul:v3"
    tts_speaker: str = "priya"
    tts_pace: float = 0.9
    timeout_s: float = 25.0

    @staticmethod
    def from_env() -> "SarvamConfig":
        e = os.environ.get
        return SarvamConfig(
            api_key=(e("SARVAM_API_KEY") or "").strip(),
            base_url=(e("SARVAM_BASE_URL") or "https://api.sarvam.ai").rstrip("/"),
            stt_model=e("SARVAM_STT_MODEL") or "saaras:v4",
            chat_model=e("SARVAM_CHAT_MODEL") or "sarvam-105b",
            tts_model=e("SARVAM_TTS_MODEL") or "bulbul:v3",
            tts_speaker=e("SARVAM_TTS_SPEAKER") or "priya",
            tts_pace=float(e("SARVAM_TTS_PACE") or 0.9),
            timeout_s=float(e("SARVAM_TIMEOUT_S") or 25),
        )


class SarvamClient:
    def __init__(self, config: Optional[SarvamConfig] = None, transport: Optional[httpx.BaseTransport] = None):
        self.config = config or SarvamConfig.from_env()
        self._transport = transport

    @property
    def configured(self) -> bool:
        return bool(self.config.api_key)

    def _post(self, path: str, **kwargs: Any) -> dict[str, Any]:
        if not self.configured:
            raise SarvamError("NOT_CONFIGURED")
        headers = {"api-subscription-key": self.config.api_key}
        try:
            with httpx.Client(timeout=self.config.timeout_s, transport=self._transport) as c:
                r = c.post(self.config.base_url + path, headers=headers, **kwargs)
        except httpx.TimeoutException:
            raise SarvamError("NETWORK")
        except httpx.HTTPError:
            raise SarvamError("NETWORK")
        if r.status_code in (401, 403):
            raise SarvamError("SARVAM_AUTH")
        if r.status_code == 429:
            raise SarvamError("RATE_LIMITED")
        if 400 <= r.status_code < 500:
            # Only the status is logged: the body may echo the request.
            log.warning("sarvam %s rejected: HTTP %s", path, r.status_code)
            raise SarvamError("SARVAM_REJECTED")
        if r.status_code >= 500:
            raise SarvamError("SARVAM_UNAVAILABLE")
        try:
            return r.json()
        except ValueError:
            raise SarvamError("BAD_RESPONSE")

    def transcribe(self, wav: bytes, language: str) -> str:
        data = {"model": self.config.stt_model, "language_code": language}
        out = self._post("/speech-to-text", data=data, files={"file": ("answer.wav", wav, "audio/wav")})
        t = out.get("transcript")
        if not isinstance(t, str):
            raise SarvamError("BAD_RESPONSE")
        return t.strip()

    def chat(self, messages: list[dict[str, str]]) -> str:
        body = {
            "model": self.config.chat_model,
            "messages": messages,
            "temperature": 0.2,
            "max_tokens": 600,
            "reasoning_effort": None,
            "response_format": {"type": "json_object"},
        }
        out = self._post("/v1/chat/completions", json=body)
        try:
            content = out["choices"][0]["message"]["content"]
        except (KeyError, IndexError, TypeError):
            raise SarvamError("BAD_RESPONSE")
        if not isinstance(content, str):
            raise SarvamError("BAD_RESPONSE")
        return content

    def speak(self, text: str, language: str) -> str:
        """Base64 WAV (16 kHz) of [text]."""
        body = {
            "text": text[:2500],
            "language_code": language,
            "model": self.config.tts_model,
            "speaker": self.config.tts_speaker,
            "pace": self.config.tts_pace,
            "speech_sample_rate": 16000,
            "output_audio_codec": "wav",
        }
        out = self._post("/text-to-speech", json=body)
        audios = out.get("audios")
        if not isinstance(audios, list) or not audios or not all(isinstance(a, str) for a in audios):
            raise SarvamError("BAD_RESPONSE")
        return audios[0]  # one input text -> one WAV
