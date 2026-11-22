# LightChat

A lightweight, ChatGPT-style Android chat client for any OpenAI-compatible API. The full APK is about 3.6 MB.

## Features

- Add any OpenAI-compatible provider (base URL + API key), one-tap model list fetching
- Per-provider allowed-models whitelist
- Model picker with search and horizontal provider filter chips
- Chat with file and image attachments
- PDF and DOCX text extraction (lightweight, no library)
- v0.5.0 production readiness: live streaming display, corrupt-file protection with backups, crash-safe saves, download filename sanitising, attachment size cap, sampled/cached image decoding, history trimming, no forced scroll while reading
- v0.5.1 launch-crash hotfix: crash handler installed before Store.load, startup guarded with tracing, scroll-to-bottom button null-safe, save thread can no longer kill the app; new TraceLog (debug.log, viewable/shareable from the Logs screen) records the startup path step by step so a future crash is diagnosable
- Fixed: user-message action buttons no longer overlap the message bubble (copy/edit stay in the long-press menu)
- Per-provider custom headers (e.g. HTTP-Referer / X-Title for OpenRouter)
- Markdown rendering: tables, code blocks (copy / save as file), blockquotes, nested lists
- Images in replies render inline, tap to view / share / save
- Message actions: copy, regenerate, edit (visible action buttons, like ChatGPT)
- Selectable text in messages
- Streaming responses, local chat history, on-screen logs for debugging
- No analytics, no tracking, no third-party services

## Build

```
./build.sh
```

Requires JDK 17, kotlinc, and Android SDK build tools (aapt2, d8, zipalign, apksigner). Paths are configured at the top of the script.

## License

AGPL-3.0
