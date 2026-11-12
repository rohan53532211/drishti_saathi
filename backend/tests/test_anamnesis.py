"""AI voice anamnesis with every Sarvam call mocked: JSON parsing, fallback questions, the stop rule, red-flag safety,
error codes and the Sarvam client's request format."""
import base64
import io
import json
import wave

import httpx
import pytest
from fastapi.testclient import TestClient

from app import anamnesis as an
from app.main import create_app
from app.sarvam import SarvamClient, SarvamConfig, SarvamError

KEY = "sk_test_SECRET_123"


def wav_b64(seconds=1.0, rate=16000, channels=1):
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(channels)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(b"\x00\x00" * int(rate * seconds) * channels)
    return base64.b64encode(buf.getvalue()).decode()


AUDIO = wav_b64()


class FakeSarvam:
    """Scripted Sarvam: queues of transcripts and chat replies (str, dict or SarvamError)."""

    def __init__(self, configured=True):
        self.configured = configured
        self.transcripts = []
        self.replies = []
        self.tts_fail = False
        self.stt_error = None
        self.chat_calls = []

    def transcribe(self, wav, language):
        if self.stt_error:
            raise SarvamError(self.stt_error)
        return self.transcripts.pop(0) if self.transcripts else "ok"

    def chat(self, messages):
        self.chat_calls.append(messages)
        r = self.replies.pop(0) if self.replies else SarvamError("NETWORK")
        if isinstance(r, Exception):
            raise r
        return r if isinstance(r, str) else json.dumps(r, ensure_ascii=False)

    def speak(self, text, language):
        if self.tts_fail:
            raise SarvamError("SARVAM_UNAVAILABLE")
        return base64.b64encode(b"RIFF-fake").decode()


def reply(extracted=None, q="What else?", cont=True):
    return {"extracted": extracted or {}, "next_question": q, "should_continue": cont}


@pytest.fixture
def fake():
    return FakeSarvam()


@pytest.fixture
def client(tmp_path, fake):
    return TestClient(create_app(str(tmp_path / "t.db"), token="", sarvam=fake))


def start(client, lang="en-IN"):
    r = client.post("/anamnesis/start", json={"language": lang})
    assert r.status_code == 200, r.text
    return r.json()


def turn(client, sid, audio=AUDIO):
    return client.post("/anamnesis/turn", json={"session_id": sid, "audio": audio})


# ---------------------------------------------------------------- happy path

def test_start_gives_first_question_with_audio(client):
    s = start(client, "hi-IN")
    assert s["question"] == an.TOPIC_BY_ID["age_group"].scripted["hi-IN"]
    assert s["audio"] and s["tts_error"] is None
    assert s["progress"]["question"] == 1 and s["progress"]["max"] == 8


def test_model_question_is_used_and_fields_extracted(client, fake):
    sid = start(client)["session_id"]
    fake.transcripts = ["I am 52"]
    fake.replies = [reply({"age": 52}, "Do you find it hard to see far, near or both?")]
    r = turn(client, sid).json()
    assert r["transcript"] == "I am 52"
    assert r["extracted"] == {"age": 52}
    assert r["topic"] == "main_difficulty" and r["source"] == "ai"
    assert r["next_question"] == "Do you find it hard to see far, near or both?"
    assert r["should_continue"] is True and r["progress"]["question"] == 2
    # the model was told which topic to ask about
    ctx = json.loads(fake.chat_calls[0][1]["content"])
    assert ctx["ask_next"] == an.TOPIC_BY_ID["main_difficulty"].about
    assert ctx["customer_answer"] == "I am 52"


def test_full_interview_and_finish(client, fake):
    sid = start(client)["session_id"]
    fake.replies = [
        reply({"age": 45}),
        reply({"visionProblem": "NEAR"}),
        reply({"redFlagAny": False}),
        reply({"wearsGlasses": True, "powerKnown": "NOT_SURE", "glassesAgeYears": 3}),
        reply({"screenHours": "H4_PLUS"}),
        reply({"readingFrequency": "DAILY_LONG", "nearTask": "NEWSPAPER"}),
        reply({"distanceSituation": "TV"}),
        reply({"headacheStrain": True, "eyeStrain": True}),
    ]
    topics = []
    for _ in range(8):
        r = turn(client, sid).json()
        topics.append(r["topic"])
    assert topics[1] == "red_flags"            # question 3
    assert r["should_continue"] is False       # 8 questions
    p = client.post("/anamnesis/finish", json={"session_id": sid}).json()
    assert p["questions_asked"] == 8 and p["red_flags_asked"] is True and p["urgent"] is False
    assert p["answers"]["visionProblem"] == "NEAR" and p["answers"]["redFlagAny"] is False
    assert len(p["transcript"]) == 8 and p["transcript"][2]["topic"] == "red_flags"
    # finish removes the session
    assert client.post("/anamnesis/finish", json={"session_id": sid}).json()["detail"]["code"] == "SESSION_EXPIRED"


# ---------------------------------------------------------------- JSON parsing and fallback

def test_parse_model_reply_accepts_fences_and_rejects_garbage():
    assert an.parse_model_reply('```json\n{"extracted": {"age": 40}, "next_question": "Q?", "should_continue": true}\n```').extracted == {"age": 40}
    assert an.parse_model_reply('Sure! {"extracted": {}, "next_question": "Q?"} hope that helps').next_question == "Q?"
    assert an.parse_model_reply("not json") is None
    assert an.parse_model_reply('{"extracted": [1,2], "next_question": "Q?"}') is None
    assert an.parse_model_reply('{"extracted": {}, "next_question": "' + "x" * 500 + '"}') is None


def test_invalid_values_are_dropped_one_by_one():
    out = an.clean_extracted({"age": "52", "visionProblem": "far", "screenHours": "h4_plus", "wearsGlasses": "yes",
                              "glassesAgeYears": 99, "diagnosis": "x", "redFlagFlashes": "maybe"})
    assert out == {"age": 52, "screenHours": "H4_PLUS", "wearsGlasses": True}


@pytest.mark.parametrize("bad", ["this is not json", SarvamError("NETWORK"), SarvamError("SARVAM_AUTH"),
                                 reply({}, "Your power is -2, you need lenses?")])
def test_fallback_scripted_question_when_model_fails(client, fake, bad):
    sid = start(client)["session_id"]
    fake.replies = [bad]
    r = turn(client, sid)
    assert r.status_code == 200
    r = r.json()
    # nothing extracted, so the same topic is asked again with the scripted wording
    assert r["source"] == "scripted"
    assert r["topic"] == "age_group"
    assert r["next_question"] == an.TOPIC_BY_ID["age_group"].scripted["en-IN"]


def test_fallback_moves_on_to_the_next_missing_topic(client, fake):
    sid = start(client)["session_id"]
    fake.replies = [reply({"age": 30}, "Your power may be high, any lens?")]   # banned wording
    r = turn(client, sid).json()
    assert (r["topic"], r["source"]) == ("main_difficulty", "scripted")
    assert r["next_question"] == an.TOPIC_BY_ID["main_difficulty"].scripted["en-IN"]


@pytest.mark.parametrize("q", ["You may have cataract. Can you see far?", "Do you need a prescription?",
                               "क्या आपका नंबर बढ़ा है?", "आपको मोतियाबिंद है?", ""])
def test_model_questions_with_diagnosis_or_power_words_are_rejected(q):
    assert not an.question_allowed(q)


def test_plain_questions_are_allowed():
    assert an.question_allowed("Do you use your phone a lot?")
    assert an.question_allowed("क्या आप रोज़ अखबार पढ़ते हैं?")


# ---------------------------------------------------------------- coverage and stop rule

def test_stops_at_eight_questions_even_if_nothing_is_understood(client, fake):
    sid = start(client)["session_id"]
    for i in range(8):
        r = turn(client, sid).json()
        assert r["should_continue"] is (i < 7), i
    assert r["progress"]["question"] == 8
    assert turn(client, sid).json()["detail"]["code"] == "INTERVIEW_FINISHED"


def test_each_topic_is_asked_at_most_twice():
    t = an.CoverageTracker()
    t.asks["age_group"] = 2
    t.asked = 3
    t.asks["red_flags"] = 1
    assert t.next_topic() == "main_difficulty"


def test_stops_when_all_topics_covered_after_minimum(client, fake):
    sid = start(client)["session_id"]
    everything = {"age": 30, "visionProblem": "DISTANCE", "wearsGlasses": False, "screenHours": "H2_4",
                  "readingFrequency": "SOMETIMES", "distanceSituation": "DRIVING", "headacheStrain": False,
                  "eyeStrain": False, "nightDriving": True, "outdoorWork": True}
    fake.replies = [reply(everything), reply({"redFlagAny": False})]
    r1 = turn(client, sid).json()
    assert r1["topic"] == "red_flags"   # everything else answered: the red-flag question is still asked
    r2 = turn(client, sid).json()
    assert r2["should_continue"] is False


def test_tracker_never_stops_before_red_flags_are_asked():
    t = an.CoverageTracker()
    for topic in an.TOPICS:
        if topic.id != "red_flags":
            t.asks[topic.id] = 2
    t.asked = 8
    assert t.next_topic() == "red_flags"


def test_red_flag_is_question_three_with_fixed_wording_even_if_model_words_it(client, fake):
    sid = start(client, "hi-IN")["session_id"]
    fake.replies = [reply({"age": 60}), reply({"visionProblem": "BOTH"}, "कोई और दिक्कत?")]
    turn(client, sid)
    r = turn(client, sid).json()
    assert r["topic"] == "red_flags" and r["progress"]["question"] == 3
    assert r["next_question"] == an.TOPIC_BY_ID["red_flags"].scripted["hi-IN"]
    assert r["source"] == "scripted"


# ---------------------------------------------------------------- red-flag safety

def test_red_flag_yes_ends_the_interview_as_urgent(client, fake):
    sid = start(client)["session_id"]
    fake.replies = [reply({"age": 58}), reply({"visionProblem": "DISTANCE"}), reply({"redFlagFlashes": True})]
    fake.transcripts = ["58", "far", "yes I saw flashes yesterday"]
    turn(client, sid), turn(client, sid)
    r = turn(client, sid).json()
    assert r["should_continue"] is False and r["urgent"] is True
    assert r["next_question"] == an.URGENT_CLOSING["en-IN"]
    p = client.post("/anamnesis/finish", json={"session_id": sid}).json()
    assert p["urgent"] is True and p["answers"]["redFlagFlashes"] is True


def test_red_flag_stays_yes_once_said():
    t = an.CoverageTracker()
    t.merge({"redFlagOther": True}, "pain")
    t.merge({"redFlagOther": False}, "no")
    assert t.answers["redFlagOther"] is True and t.urgent


def test_red_flag_words_the_model_missed_make_it_unclear(client, fake):
    sid = start(client)["session_id"]
    fake.transcripts = ["Sometimes I see a curtain, I am 70"]
    fake.replies = [reply({"age": 70, "redFlagFlashes": False})]
    turn(client, sid)
    p = client.post("/anamnesis/finish", json={"session_id": sid}).json()
    assert p["red_flag_unclear"] is True and p["urgent"] is False


def test_headache_alone_is_not_a_red_flag_word():
    assert not an.red_flag_words_in("I get a headache after reading")
    assert not an.red_flag_words_in("पढ़ते समय सिरदर्द होता है")
    assert an.red_flag_words_in("आँख में दर्द है")
    assert an.red_flag_words_in("I saw flashes")


# ---------------------------------------------------------------- errors

def test_empty_transcript_keeps_the_same_question(client, fake):
    sid = start(client)["session_id"]
    fake.transcripts = ["   "]
    r = turn(client, sid)
    assert r.status_code == 422 and r.json()["detail"]["code"] == "EMPTY_TRANSCRIPT"
    fake.replies = [reply({"age": 33})]
    r = turn(client, sid).json()
    assert r["progress"]["question"] == 2


@pytest.mark.parametrize("audio,status,code", [
    ("not base64!!", 422, "BAD_AUDIO"),
    (base64.b64encode(b"hello world, not a wav").decode(), 422, "BAD_AUDIO"),
    (wav_b64(1, rate=44100), 422, "BAD_AUDIO"),
    (wav_b64(1, channels=2), 422, "BAD_AUDIO"),
    (wav_b64(16), 413, "AUDIO_TOO_LONG"),
    (wav_b64(0.1), 422, "EMPTY_TRANSCRIPT"),
])
def test_bad_audio(client, audio, status, code):
    sid = start(client)["session_id"]
    r = turn(client, sid, audio)
    assert r.status_code == status and r.json()["detail"]["code"] == code


def test_tts_failure_still_returns_the_question_text(client, fake):
    fake.tts_fail = True
    s = start(client)
    assert s["audio"] is None and s["tts_error"] == "TTS_FAILED" and s["question"]
    fake.replies = [reply({"age": 40})]
    r = turn(client, s["session_id"])
    assert r.status_code == 200 and r.json()["tts_error"] == "TTS_FAILED"


@pytest.mark.parametrize("code,status", [("SARVAM_AUTH", 502), ("NETWORK", 504), ("RATE_LIMITED", 503)])
def test_speech_to_text_errors_are_clean_codes(client, fake, code, status):
    sid = start(client)["session_id"]
    fake.stt_error = code
    r = turn(client, sid)
    assert r.status_code == status and r.json() == {"detail": {"code": code}}


def test_no_key_means_not_configured(tmp_path):
    c = TestClient(create_app(str(tmp_path / "t.db"), token="", sarvam=FakeSarvam(configured=False)))
    r = c.post("/anamnesis/start", json={"language": "en-IN"})
    assert r.status_code == 503 and r.json()["detail"]["code"] == "NOT_CONFIGURED"
    assert c.get("/health").json()["voiceAi"] is False


def test_unknown_session_and_language(client):
    assert turn(client, "nonexistent-session").json()["detail"]["code"] == "SESSION_EXPIRED"
    assert client.post("/anamnesis/start", json={"language": "fr-FR"}).status_code == 422


def test_sessions_expire():
    clock = [1000.0]
    store = an.SessionStore(ttl_s=60, clock=lambda: clock[0])
    s = store.create("en-IN")
    clock[0] += 61
    with pytest.raises(Exception) as e:
        store.get(s.id)
    assert e.value.detail["code"] == "SESSION_EXPIRED"


def test_token_protects_the_voice_endpoints(tmp_path, fake):
    c = TestClient(create_app(str(tmp_path / "t.db"), token="s3cret", sarvam=fake))
    assert c.post("/anamnesis/start", json={"language": "en-IN"}).status_code == 401
    assert c.post("/anamnesis/start", json={"language": "en-IN"}, headers={"X-Bridge-Token": "s3cret"}).status_code == 200


# ---------------------------------------------------------------- the real client, against a mock transport

def test_sarvam_client_request_format_and_key_never_leaks():
    seen = []

    def handler(req: httpx.Request):
        seen.append(req)
        if req.url.path == "/speech-to-text":
            return httpx.Response(200, json={"request_id": "r", "transcript": " नमस्ते ", "language_code": "hi-IN"})
        if req.url.path == "/v1/chat/completions":
            return httpx.Response(200, json={"choices": [{"message": {"role": "assistant", "content": "{}"}}]})
        if req.url.path == "/text-to-speech":
            return httpx.Response(200, json={"request_id": "r", "audios": ["UklGRg=="]})
        return httpx.Response(404)

    c = SarvamClient(SarvamConfig(api_key=KEY), transport=httpx.MockTransport(handler))
    assert c.transcribe(b"RIFF....", "hi-IN") == "नमस्ते"
    assert c.chat([{"role": "user", "content": "x"}]) == "{}"
    assert c.speak("नमस्ते", "hi-IN") == "UklGRg=="
    stt, chat, tts = seen
    assert all(r.headers["api-subscription-key"] == KEY for r in seen)
    assert b'name="model"' in stt.content and b"saaras:v4" in stt.content and b"hi-IN" in stt.content
    body = json.loads(chat.content)
    assert body["model"] == "sarvam-105b" and body["response_format"] == {"type": "json_object"}
    assert body["reasoning_effort"] is None
    body = json.loads(tts.content)
    assert (body["model"], body["speaker"], body["language_code"], body["speech_sample_rate"]) == ("bulbul:v3", "priya", "hi-IN", 16000)


@pytest.mark.parametrize("status,code", [(401, "SARVAM_AUTH"), (403, "SARVAM_AUTH"), (429, "RATE_LIMITED"),
                                         (400, "SARVAM_REJECTED"), (500, "SARVAM_UNAVAILABLE")])
def test_sarvam_http_errors_map_to_codes_without_the_key(status, code):
    t = httpx.MockTransport(lambda req: httpx.Response(status, json={"error": {"message": f"bad key {KEY}"}}))
    c = SarvamClient(SarvamConfig(api_key=KEY), transport=t)
    with pytest.raises(SarvamError) as e:
        c.transcribe(b"x", "en-IN")
    assert e.value.code == code and KEY not in str(e.value)


def test_sarvam_network_error_and_missing_key():
    def boom(req):
        raise httpx.ConnectError("down")
    with pytest.raises(SarvamError) as e:
        SarvamClient(SarvamConfig(api_key=KEY), transport=httpx.MockTransport(boom)).chat([])
    assert e.value.code == "NETWORK"
    with pytest.raises(SarvamError) as e:
        SarvamClient(SarvamConfig(api_key="")).speak("x", "en-IN")
    assert e.value.code == "NOT_CONFIGURED"


def test_key_is_not_in_any_api_response(tmp_path):
    t = httpx.MockTransport(lambda req: httpx.Response(403, json={"detail": f"invalid {KEY}"}))
    c = TestClient(create_app(str(tmp_path / "t.db"), token="",
                              sarvam=SarvamClient(SarvamConfig(api_key=KEY), transport=t)))
    s = c.post("/anamnesis/start", json={"language": "en-IN"})
    assert s.status_code == 200 and KEY not in s.text and s.json()["tts_error"] == "TTS_FAILED"
    r = turn(c, s.json()["session_id"])
    assert r.status_code == 502 and KEY not in r.text and r.json() == {"detail": {"code": "SARVAM_AUTH"}}
