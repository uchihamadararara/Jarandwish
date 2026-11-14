# JARVIS 1.8.17 – smoother tasks

- Read-only checks ("munni ka message aaya kya") are now verified locally, so task_completed is no longer rejected (that rejection caused the repeated scrolling and repeated answers).
- Scroll guard for such checks: at most 2 scrolls, never reversing direction.
- Prompt rule + step directive for lookups: read the screen once, answer once.
- Completion confirmation is spoken once; skipped if audio was just played.
- Automatic task continuation now waits for 900 ms of real silence (was 350 ms) so it no longer cuts speech.
- Listening/VAD behaviour is back exactly as in 1.8.10 (server VAD config, 450 ms local pause, thresholds).
- Lookup detection now also understands Devanagari transcripts (Gemini often writes Hindi in Devanagari).
- A scroll refusal twice in a row stops the task; lookup tasks are capped at 8 steps.
- Goals without a local verifier are accepted on the second task_completed (no more endless rejection loop).
- ROOT CAUSE: when the assistant spoke its answer without calling task_completed, the automatic "continue the existing task" loop (up to 25 times) kept prodding it. Each prod made it scroll again and repeat itself, while the mic stayed muted during its speech.
- Now the loop ends as soon as a lookup is answered, or after two spoken turns without any tool action; the continuation limit is 10 instead of 25.
