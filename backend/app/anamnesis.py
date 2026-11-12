"""AI voice anamnesis: STT -> chat -> TTS per turn, with the interview logic held in code.

The [CoverageTracker] decides which topic comes next and when to stop: the chat model only extracts answers and
phrases the next question. If the model fails, returns bad JSON, or words its question in a way we do not allow, the
scripted question for that topic is used, so the interview never breaks.

Safety:
- The red-flag topic is always asked, with fixed scripted wording, as question 3.
- A red flag that the model reports as yes ends the interview (urgent: the app's rules engine stops advice).
- A red-flag keyword in what the customer said that the model did not mark as yes makes the red flag "unclear":
  the app then asks the standard red-flag questions on the phone.
- The model is told never to diagnose, name conditions, or suggest power or lenses; its question is also checked.
"""
from __future__ import annotations

import base64
import io
import json
import os
import re
import secrets
import threading
import time
import wave
from dataclasses import dataclass, field
from typing import Any, Callable, Optional

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, ConfigDict, Field

from .sarvam import SarvamClient, SarvamError

MAX_QUESTIONS = 8
MIN_QUESTIONS = 5
RED_FLAG_POSITION = 3          # the red-flag question is always question 3
MAX_ASKS_PER_TOPIC = 2
MAX_AUDIO_SECONDS = 15.5
MIN_AUDIO_SECONDS = 0.3
SESSION_TTL_S = 30 * 60
MAX_SESSIONS = 200

LANGUAGES = {"hi-IN": "Hindi", "en-IN": "English"}

# ---- fields the model may extract (names match the app's InterviewAnswers where they exist) ----
ENUMS = {
    "visionProblem": ["DISTANCE", "NEAR", "BOTH", "NONE"],
    "screenHours": ["H0_2", "H2_4", "H4_PLUS"],
    "readingFrequency": ["RARELY", "SOMETIMES", "DAILY_LONG"],
    "nearTask": ["PHONE", "NEWSPAPER", "SEWING", "ACCOUNTS", "OTHER"],
    "distanceSituation": ["DRIVING", "TV", "SIGNBOARDS", "CLASSROOM_BOARD", "OTHER"],
    "powerKnown": ["YES", "NOT_SURE", "NO"],
}
BOOLS = [
    "eyeStrain", "headacheStrain", "outdoorWork", "nightDriving", "nightVisionDifficulty", "wearsGlasses",
    "redFlagFlashes", "redFlagOther", "redFlagAny",
]
INTS = {"age": (3, 110), "glassesAgeYears": (0, 60)}
RED_FLAG_FIELDS = ("redFlagFlashes", "redFlagOther", "redFlagAny")


@dataclass(frozen=True)
class Topic:
    id: str
    fields: tuple[str, ...]
    about: str                   # what to ask, for the model
    scripted: dict[str, str]     # fallback question per language


# Ask order = priority. red_flags is placed at RED_FLAG_POSITION by the tracker, whatever its place here.
# Built-in copy; the shared app file (anamnesis_questions.json, "aiTopics") is used when it is found.
BUILTIN_TOPICS: list[Topic] = [
    Topic("age_group", ("age",), "the customer's age in years",
          {"en-IN": "How old are you?", "hi-IN": "आपकी उम्र क्या है?"}),
    Topic("main_difficulty", ("visionProblem",), "whether seeing far, near (reading, phone) or both is difficult, or neither",
          {"en-IN": "Do you have trouble seeing far, near, or both?",
           "hi-IN": "क्या आपको दूर का, पास का, या दोनों देखने में दिक्कत है?"}),
    Topic("red_flags", RED_FLAG_FIELDS, "sudden flashes, floaters, a curtain, sudden vision loss, eye pain or redness, double vision",
          {"en-IN": "Have you suddenly seen flashes of light, floating spots or a curtain, or had a sudden drop in vision, "
                    "eye pain or redness, or double vision?",
           "hi-IN": "क्या अचानक रोशनी की चमक, तैरते धब्बे या आँख के आगे पर्दा दिखा, या अचानक नज़र कम हुई, "
                    "आँख में दर्द-लाली हुई, या दो-दो दिखता है?"}),
    Topic("existing_glasses", ("wearsGlasses", "powerKnown"), "whether they wear glasses now and, if yes, whether they remember the glasses number or have the slip",
          {"en-IN": "Do you wear glasses now? If yes, do you remember their number or have the slip?",
           "hi-IN": "क्या आप अभी चश्मा पहनते हैं? अगर हाँ, तो क्या चश्मे का नंबर याद है या पर्ची है?"}),
    Topic("screen_use", ("screenHours",), "how many hours a day they look at a mobile or computer",
          {"en-IN": "How many hours a day do you look at a mobile or computer?",
           "hi-IN": "आप रोज़ कितने घंटे मोबाइल या कंप्यूटर देखते हैं?"}),
    Topic("reading", ("readingFrequency", "nearTask"), "how much they read or do close work, and which close work",
          {"en-IN": "How much do you read or do close work, like the newspaper, phone or sewing?",
           "hi-IN": "आप कितना पढ़ते हैं या पास का काम करते हैं, जैसे अखबार, फ़ोन या सिलाई?"}),
    Topic("distance_tasks", ("distanceSituation",), "distance tasks: driving, TV, reading signboards",
          {"en-IN": "Is it hard to see when driving, watching TV or reading signboards?",
           "hi-IN": "क्या गाड़ी चलाते, टीवी देखते या बोर्ड पढ़ते समय देखने में दिक्कत होती है?"}),
    Topic("headache", ("headacheStrain",), "headaches during reading, screen or other visual work",
          {"en-IN": "Do you get headaches when you read or look at a screen?",
           "hi-IN": "क्या पढ़ते या स्क्रीन देखते समय सिरदर्द होता है?"}),
    Topic("eye_strain", ("eyeStrain",), "tired, strained or watery eyes",
          {"en-IN": "Do your eyes feel tired or strained?",
           "hi-IN": "क्या आपकी आँखें थकी हुई या भारी लगती हैं?"}),
    Topic("night_vision", ("nightDriving", "nightVisionDifficulty"), "seeing at night and driving at night",
          {"en-IN": "Is it hard to see at night? Do you drive at night?",
           "hi-IN": "क्या रात में देखने में दिक्कत होती है? क्या आप रात में गाड़ी चलाते हैं?"}),
    Topic("outdoor_indoor", ("outdoorWork",), "whether they mostly work outdoors in the sun or indoors",
          {"en-IN": "Do you mostly work outside in the sun, or indoors?",
           "hi-IN": "क्या आप ज़्यादातर बाहर धूप में काम करते हैं, या अंदर?"}),
]
# The app's question file: same topics for the phone's scripted questions and the AI interview.
DEFAULT_QUESTIONS_PATH = os.path.join(os.path.dirname(__file__), "..", "..", "app", "app", "src", "main", "assets",
                                      "anamnesis_questions.json")
KNOWN_FIELDS = set(ENUMS) | set(BOOLS) | set(INTS)


def load_topics(path: Optional[str] = None) -> list[Topic]:
    """Topics from the shared questions file (ANAMNESIS_QUESTIONS env, else the app's assets file).

    Falls back to [BUILTIN_TOPICS] when the file is missing or invalid: every topic needs an id, known fields,
    and a scripted question in both languages, and red_flags must be there.
    """
    p = path or os.environ.get("ANAMNESIS_QUESTIONS") or DEFAULT_QUESTIONS_PATH
    try:
        with open(p, encoding="utf-8") as f:
            raw = json.load(f)["aiTopics"]
        topics = [Topic(str(t["id"]), tuple(t["fields"]), str(t["about"]),
                        {k: str(t["scripted"][k]) for k in LANGUAGES}) for t in raw]
    except (OSError, KeyError, TypeError, ValueError):
        return list(BUILTIN_TOPICS)
    ids = [t.id for t in topics]
    ok = (topics and "red_flags" in ids and len(set(ids)) == len(ids)
          and all(t.fields and set(t.fields) <= KNOWN_FIELDS for t in topics))
    return topics if ok else list(BUILTIN_TOPICS)


TOPICS: list[Topic] = load_topics()
TOPIC_BY_ID = {t.id: t for t in TOPICS}

CLOSING = {
    "en-IN": "Thank you. That is all for now. The shop staff will continue with you.",
    "hi-IN": "धन्यवाद। अभी के लिए इतना ही। दुकान के लोग आगे आपकी मदद करेंगे।",
}
URGENT_CLOSING = {
    "en-IN": "Thank you for telling us. Please speak to the shop staff now.",
    "hi-IN": "बताने के लिए धन्यवाद। कृपया अभी दुकान के लोगों से बात करें।",
}

# The model's question is rejected (and the scripted one used) if it contains any of these.
BANNED_IN_QUESTION = [
    "prescription", "diagnos", "power", "lens", "cataract", "glaucoma", "myopia", "hyperopia", "presbyopia",
    "astigmatism", "disease", "infection", "retina", "detachment", "condition", "you have", "dioptre", "diopter",
    "पर्चा", "निदान", "पावर", "लेंस", "मोतियाबिंद", "काला मोतिया", "बीमारी", "रोग", "आपका नंबर", "संक्रमण",
]

# Red-flag words in what the customer said. A hit the model did not mark as yes makes the red flag "unclear".
RED_FLAG_WORDS = [
    "flash", "floater", "floating", "curtain", "shadow", "sudden", "pain", "red eye", "redness", "double", "halo",
    "rings around", "चमक", "धब्बे", "पर्दा", "परदा", "अचानक", "दर्द", "लाल", "लाली", "दो-दो", "दो दो", "दोहरा", "घेरे",
]
NOT_RED_FLAG_WORDS = ["headache", "head ache", "सिरदर्द", "सिर दर्द", "सर दर्द"]


def red_flag_words_in(text: str) -> bool:
    t = text.lower()
    for w in NOT_RED_FLAG_WORDS:
        t = t.replace(w, " ")
    return any(w in t for w in RED_FLAG_WORDS)


def clean_value(name: str, value: Any) -> Any:
    """The value if it is valid for [name], else None (invalid values are dropped one by one)."""
    if value is None:
        return None
    if name in ENUMS:
        v = str(value).strip().upper()
        return v if v in ENUMS[name] else None
    if name in BOOLS:
        if isinstance(value, bool):
            return value
        if isinstance(value, str) and value.strip().lower() in ("true", "yes"):
            return True
        if isinstance(value, str) and value.strip().lower() in ("false", "no"):
            return False
        return None
    if name in INTS:
        lo, hi = INTS[name]
        try:
            n = int(round(float(value)))
        except (TypeError, ValueError):
            return None
        return n if lo <= n <= hi else None
    return None


def clean_extracted(raw: Any) -> dict[str, Any]:
    if not isinstance(raw, dict):
        return {}
    out = {}
    for k, v in raw.items():
        c = clean_value(str(k), v)
        if c is not None:
            out[str(k)] = c
    return out


class ModelReply(BaseModel):
    """The JSON the chat model must return."""
    model_config = ConfigDict(extra="ignore")
    extracted: dict[str, Any] = Field(default_factory=dict)
    next_question: str = Field(default="", max_length=400)
    should_continue: bool = True


def parse_model_reply(text: str) -> Optional[ModelReply]:
    """Strict JSON (code fences tolerated); None when it does not fit the schema."""
    s = text.strip()
    if s.startswith("```"):
        s = re.sub(r"^```[a-zA-Z]*\s*", "", s)
        s = re.sub(r"\s*```$", "", s)
    try:
        data = json.loads(s)
    except json.JSONDecodeError:
        m = re.search(r"\{.*\}", s, re.DOTALL)
        if not m:
            return None
        try:
            data = json.loads(m.group(0))
        except json.JSONDecodeError:
            return None
    try:
        return ModelReply.model_validate(data)
    except Exception:
        return None


def question_allowed(q: str) -> bool:
    t = q.strip().lower()
    if not (3 <= len(t) <= 250):
        return False
    if t.count("?") > 2:   # one question (a two-part question like the scripted ones is fine)
        return False
    return not any(b in t for b in BANNED_IN_QUESTION)


@dataclass
class Turn:
    n: int
    topic: str
    question: str
    source: str               # "scripted" | "ai"
    answer: Optional[str] = None
    extracted: dict[str, Any] = field(default_factory=dict)


class CoverageTracker:
    """Required topics, what has been asked and answered, which topic is next and when to stop."""

    def __init__(self) -> None:
        self.answers: dict[str, Any] = {}
        self.asks: dict[str, int] = {t.id: 0 for t in TOPICS}
        self.asked = 0                    # questions posed so far
        self.red_flag_unclear = False

    def covered(self, topic_id: str) -> bool:
        t = TOPIC_BY_ID[topic_id]
        if topic_id == "red_flags":
            return self.asks["red_flags"] > 0 and any(self.answers.get(f) is not None for f in t.fields)
        if topic_id == "existing_glasses":
            # "No glasses" is enough; "yes" also needs whether they remember the number.
            g = self.answers.get("wearsGlasses")
            return g is False or (g is True and self.answers.get("powerKnown") is not None)
        return any(self.answers.get(f) is not None for f in t.fields)

    def done(self, topic_id: str) -> bool:
        return self.covered(topic_id) or self.asks[topic_id] >= MAX_ASKS_PER_TOPIC

    @property
    def urgent(self) -> bool:
        return any(self.answers.get(f) is True for f in RED_FLAG_FIELDS)

    def missing(self) -> list[str]:
        return [t.id for t in TOPICS if not self.covered(t.id)]

    def next_topic(self, assume_done: Optional[str] = None) -> Optional[str]:
        """The topic for the next question, or None to stop. [assume_done]: treat this topic as answered (planning)."""
        done = lambda tid: tid == assume_done or self.done(tid)
        if self.urgent:
            return None
        red_pending = self.asks["red_flags"] == 0 and assume_done != "red_flags"
        if red_pending and self.asked + 1 >= RED_FLAG_POSITION:
            return "red_flags"
        if self.asked >= MAX_QUESTIONS:
            return None
        # Ends when every topic is covered or asked twice. With 11 topics that takes at least MIN_QUESTIONS unless
        # the customer answered several topics at once; then there is nothing left to ask.
        for t in TOPICS:
            if t.id != "red_flags" and not done(t.id):
                return t.id
        return "red_flags" if red_pending else None   # never stop before the red-flag question

    def merge(self, extracted: dict[str, Any], transcript: str) -> None:
        for k, v in extracted.items():
            if k in RED_FLAG_FIELDS and self.answers.get(k) is True:
                continue   # a red flag once said yes stays yes
            self.answers[k] = v
        if red_flag_words_in(transcript) and not self.urgent:
            self.red_flag_unclear = True

    def expected_total(self) -> int:
        remaining = sum(1 for t in TOPICS if not self.done(t.id))
        return max(MIN_QUESTIONS, min(MAX_QUESTIONS, self.asked + remaining))


@dataclass
class Session:
    id: str
    language: str
    created: float
    tracker: CoverageTracker = field(default_factory=CoverageTracker)
    turns: list[Turn] = field(default_factory=list)
    lock: threading.Lock = field(default_factory=threading.Lock)

    @property
    def current(self) -> Turn:
        return self.turns[-1]


class SessionStore:
    """In memory, expires after [SESSION_TTL_S]. A restart loses open interviews (the app then falls back)."""

    def __init__(self, ttl_s: float = SESSION_TTL_S, clock: Callable[[], float] = time.time):
        self.ttl_s = ttl_s
        self.clock = clock
        self._items: dict[str, Session] = {}
        self._lock = threading.Lock()

    def _expire(self) -> None:
        now = self.clock()
        for k in [k for k, s in self._items.items() if now - s.created > self.ttl_s]:
            del self._items[k]

    def create(self, language: str) -> Session:
        with self._lock:
            self._expire()
            if len(self._items) >= MAX_SESSIONS:
                oldest = min(self._items.values(), key=lambda s: s.created)
                del self._items[oldest.id]
            s = Session(secrets.token_urlsafe(16), language, self.clock())
            self._items[s.id] = s
            return s

    def get(self, sid: str) -> Session:
        with self._lock:
            self._expire()
            s = self._items.get(sid)
        if s is None:
            raise api_error(404, "SESSION_EXPIRED")
        return s

    def pop(self, sid: str) -> Session:
        with self._lock:
            self._expire()
            s = self._items.pop(sid, None)
        if s is None:
            raise api_error(404, "SESSION_EXPIRED")
        return s


ERROR_STATUS = {
    "NOT_CONFIGURED": 503, "SARVAM_AUTH": 502, "RATE_LIMITED": 503, "SARVAM_REJECTED": 502,
    "SARVAM_UNAVAILABLE": 503, "NETWORK": 504, "BAD_RESPONSE": 502,
}


def api_error(status: int, code: str) -> HTTPException:
    return HTTPException(status_code=status, detail={"code": code})


def decode_wav(b64: str) -> bytes:
    """The WAV bytes if they are 16 kHz mono 16-bit and 0.3 to 15.5 s long."""
    try:
        raw = base64.b64decode(b64, validate=True)
    except Exception:
        raise api_error(422, "BAD_AUDIO")
    try:
        with wave.open(io.BytesIO(raw)) as w:
            ok = w.getnchannels() == 1 and w.getframerate() == 16000 and w.getsampwidth() == 2
            seconds = w.getnframes() / 16000
    except Exception:
        raise api_error(422, "BAD_AUDIO")
    if not ok:
        raise api_error(422, "BAD_AUDIO")
    if seconds > MAX_AUDIO_SECONDS:
        raise api_error(413, "AUDIO_TOO_LONG")
    if seconds < MIN_AUDIO_SECONDS:
        raise api_error(422, "EMPTY_TRANSCRIPT")
    return raw


SYSTEM_PROMPT = """You help an optical shop take a short eye-care history (anamnesis) by voice, before the shop's own tests.
You are not a doctor. Rules:
- Never diagnose. Never name an eye condition or disease. Never suggest glasses power, lenses or treatment.
- Stay on the history. If the customer asks something else, gently return to the question.
- Read the customer's latest answer and extract only what they actually said into "extracted", using exactly the field
  names and allowed values below. Leave out anything they did not say. Do not guess.
- Then write "next_question": exactly ONE short, simple, friendly question in {language}, about the topic given in
  "ask_next". Use plain everyday words an older person understands. No lists. No more than 25 words.
- Reply with JSON only, no other text:
  {{"extracted": {{...}}, "next_question": "...", "should_continue": true}}

Fields:
- age: integer years
- visionProblem: DISTANCE | NEAR | BOTH | NONE   (trouble seeing far, near, both, or neither)
- screenHours: H0_2 | H2_4 | H4_PLUS   (hours a day on mobile or computer)
- readingFrequency: RARELY | SOMETIMES | DAILY_LONG
- nearTask: PHONE | NEWSPAPER | SEWING | ACCOUNTS | OTHER   (hardest close work)
- distanceSituation: DRIVING | TV | SIGNBOARDS | CLASSROOM_BOARD | OTHER   (where far-away blur is noticed most)
- headacheStrain: true | false   (headaches with reading, screen or visual work)
- eyeStrain: true | false   (tired or strained eyes)
- outdoorWork: true | false   (mostly works outdoors in the sun)
- nightDriving: true | false   (drives at night)
- nightVisionDifficulty: true | false   (hard to see at night)
- wearsGlasses: true | false
- glassesAgeYears: integer years
- powerKnown: YES | NOT_SURE | NO   (remembers the glasses number or has the slip)
- redFlagFlashes: true | false   (sudden flashes of light, floating spots, a curtain or shadow)
- redFlagOther: true | false   (sudden drop in vision, eye pain or redness, rings around lights with pain, double vision)
- redFlagAny: true | false   (use when they say yes or no to the warning-sign question without saying which)"""


def build_messages(s: Session, transcript: str, ask_next: Optional[str]) -> list[dict[str, str]]:
    t = s.tracker
    topic = TOPIC_BY_ID.get(ask_next) if ask_next else None
    context = {
        "language": LANGUAGES[s.language],
        "question_just_asked": s.current.question,
        "customer_answer": transcript,
        "already_known": t.answers,
        "missing_topics": [TOPIC_BY_ID[m].about for m in t.missing()],
        "ask_next": topic.about if topic else "nothing more: write a short thank-you",
    }
    return [
        {"role": "system", "content": SYSTEM_PROMPT.format(language=LANGUAGES[s.language])},
        {"role": "user", "content": json.dumps(context, ensure_ascii=False)},
    ]


class AnamnesisService:
    def __init__(self, sarvam: SarvamClient, store: Optional[SessionStore] = None):
        self.sarvam = sarvam
        self.store = store or SessionStore()

    def _speak(self, text: str, language: str) -> tuple[Optional[str], Optional[str]]:
        """(audio, error code). TTS failure is not fatal: the app shows the text and can read it with the phone."""
        try:
            return self.sarvam.speak(text, language), None
        except SarvamError:
            return None, "TTS_FAILED"

    def _progress(self, s: Session) -> dict[str, Any]:
        t = s.tracker
        return {"question": t.asked, "expected": t.expected_total(), "max": MAX_QUESTIONS,
                "covered": [x.id for x in TOPICS if t.covered(x.id)], "missing": t.missing()}

    def _pose(self, s: Session, topic: str, text: str, source: str) -> Turn:
        s.tracker.asks[topic] += 1
        s.tracker.asked += 1
        turn = Turn(s.tracker.asked, topic, text, source)
        s.turns.append(turn)
        return turn

    def start(self, language: str) -> dict[str, Any]:
        if not self.sarvam.configured:
            raise api_error(503, "NOT_CONFIGURED")
        s = self.store.create(language)
        first = TOPICS[0]
        turn = self._pose(s, first.id, first.scripted[language], "scripted")
        audio, tts_error = self._speak(turn.question, language)
        return {"session_id": s.id, "language": language, "question": turn.question, "topic": turn.topic,
                "audio": audio, "audio_format": "wav", "tts_error": tts_error, "progress": self._progress(s)}

    def turn(self, sid: str, audio_b64: str) -> dict[str, Any]:
        s = self.store.get(sid)
        with s.lock:
            if s.current.answer is not None:
                raise api_error(409, "INTERVIEW_FINISHED")
            wav = decode_wav(audio_b64)
            try:
                transcript = self.sarvam.transcribe(wav, s.language)
            except SarvamError as e:
                raise api_error(ERROR_STATUS.get(e.code, 502), e.code)
            if not transcript.strip():
                raise api_error(422, "EMPTY_TRANSCRIPT")   # same question again; nothing is counted
            t = s.tracker
            current = s.current
            current.answer = transcript

            # The topic to ask next is chosen before the model call (from what is known so far) and again after
            # merging, so the model is asked to word the right question in the normal case.
            planned = t.next_topic(assume_done=current.topic)
            reply = None
            try:
                reply = parse_model_reply(self.sarvam.chat(build_messages(s, transcript, planned)))
            except SarvamError:
                reply = None
            extracted = clean_extracted(reply.extracted) if reply else {}
            current.extracted = extracted
            t.merge(extracted, transcript)

            nxt = t.next_topic()
            if nxt is None:
                closing = (URGENT_CLOSING if t.urgent else CLOSING)[s.language]
                audio, tts_error = self._speak(closing, s.language)
                return {"transcript": transcript, "extracted": extracted, "next_question": closing, "topic": None,
                        "audio": audio, "audio_format": "wav", "tts_error": tts_error, "should_continue": False,
                        "urgent": t.urgent, "source": "scripted", "progress": self._progress(s)}

            topic = TOPIC_BY_ID[nxt]
            use_model = (reply is not None and nxt == planned and nxt != "red_flags" and question_allowed(reply.next_question))
            text = reply.next_question.strip() if use_model else topic.scripted[s.language]
            turn = self._pose(s, nxt, text, "ai" if use_model else "scripted")
            audio, tts_error = self._speak(turn.question, s.language)
            return {"transcript": transcript, "extracted": extracted, "next_question": turn.question, "topic": nxt,
                    "audio": audio, "audio_format": "wav", "tts_error": tts_error, "should_continue": True,
                    "urgent": False, "source": turn.source, "progress": self._progress(s)}

    def finish(self, sid: str) -> dict[str, Any]:
        s = self.store.pop(sid)
        t = s.tracker
        return {
            "session_id": s.id,
            "language": s.language,
            "questions_asked": t.asked,
            "answers": dict(t.answers),
            "red_flags_asked": t.asks["red_flags"] > 0 and any(x.topic == "red_flags" and x.answer for x in s.turns),
            "red_flag_unclear": t.red_flag_unclear,
            "urgent": t.urgent,
            "coverage": {x.id: t.covered(x.id) for x in TOPICS},
            "transcript": [
                {"n": x.n, "topic": x.topic, "question": x.question, "answer": x.answer, "source": x.source}
                for x in s.turns
            ],
        }


class StartIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    language: str = Field(pattern="^(hi-IN|en-IN)$")


class TurnIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    session_id: str = Field(min_length=8, max_length=64)
    audio: str = Field(min_length=8, max_length=1_000_000, description="base64 WAV, 16 kHz mono 16-bit, at most 15 s")


class FinishIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    session_id: str = Field(min_length=8, max_length=64)


def router(service: AnamnesisService, check_token: Callable[..., None]) -> APIRouter:
    r = APIRouter(prefix="/anamnesis", dependencies=[Depends(check_token)])

    @r.post("/start")
    def start(body: StartIn) -> dict[str, Any]:
        return service.start(body.language)

    @r.post("/turn")
    def turn(body: TurnIn) -> dict[str, Any]:
        return service.turn(body.session_id, body.audio)

    @r.post("/finish")
    def finish(body: FinishIn) -> dict[str, Any]:
        return service.finish(body.session_id)

    return r
