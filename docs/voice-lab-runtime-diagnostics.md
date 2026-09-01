# Voice Lab Runtime Diagnostics

Voice Lab intentionally exposes runtime costs that Nabu normally hides behind navigation, preloading, teardown, and background work.

## Why This Exists

The commercial Creator Voice Studio should feel responsive, but the internal lab needs to show the truth:

- engine catalog load time
- voice list load time
- selected engine and voice
- runtime state
- render wall time
- render failure reason

This helps separate product UX decisions from hard runtime constraints. If switching from one TTS engine to another takes several seconds, Voice Lab should measure that plainly. A later user-facing app can decide how to spend that time: voice browsing, setup choices, progress UI, education, animation, or background preparation.

## Current States

- `Loading`: Voice Lab is loading engine or voice metadata.
- `Unavailable`: No usable engine is selected or the selected engine cannot run for a non-model reason.
- `NeedsModel`: The selected engine needs a model download or required files are missing.
- `Ready`: The selected engine can render.
- `Rendering`: A preview or full render is running.
- `Failed`: The most recent action failed.

## Current Limits

These diagnostics are UI-level runtime observations. They do not yet measure lower-level ONNX session creation, NNAPI delegate setup, memory pressure, thermal throttling, or background preloading. Those are good future probes once the remaining engines have been validated end to end.
