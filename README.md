# LightChat

A lightweight, ChatGPT-style Android chat client for any OpenAI-compatible API. The full APK is about 3.6 MB.

## Features

- Add any OpenAI-compatible provider (base URL + API key), one-tap model list fetching
- Per-provider allowed-models whitelist
- Model picker with search and horizontal provider filter chips
- Chat with file and image attachments
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
