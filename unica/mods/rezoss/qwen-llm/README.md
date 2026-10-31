# Rezoss AI API app with optional MLC fallback

This module builds `QWEN_LLM_rezoss.apk`, a small settings app and trusted API bridge for the existing ROM text-generation flow.

## Setup

1. Install the ROM's `QWEN_LLM_rezoss.apk` and open **Rezoss AI**.
2. Choose OpenAI, Gemini, OpenRouter, or a custom endpoint; enter the provider's model name and API key.
3. Save the settings. The API key is encrypted with Android Keystore and is not included in the ROM build.
4. Optionally download the on-device fallback in the app. Gemma 2 2B Instruct is downloaded once to app-private storage (about 1.5 GB); it is not included in the ROM or APK.

The app sends non-streaming OpenAI Chat Completions requests. The built-in presets use these endpoints:

- OpenAI: `https://api.openai.com/v1/chat/completions`
- Gemini's OpenAI-compatible endpoint: `https://generativelanguage.googleapis.com/v1beta/openai/chat/completions`
- OpenRouter: `https://openrouter.ai/api/v1/chat/completions`

Custom endpoints must accept a Bearer API key and OpenAI-style `chat/completions` requests, then return text in `choices[0].message.content`. This covers other providers that implement that protocol. There is no single protocol for every vendor: APIs that only expose a different native format need a matching adapter.

## Data flow

The ROM keeps its existing screen-text extraction, prompt adaptation, call-screening, and reply/summary formats. The configured API is tried first. If it is unconfigured or unavailable, the same prompt goes to the on-device MLC model when its runtime and model are installed. The bridge validates the result before returning it to the existing system caller. When local fallback is installed, API attempts leave up to 6.5 seconds of the caller's deadline for local generation.

The local model is Gemma 2 2B Instruct in MLC's `q4f16_0` weight layout: 4-bit weights, 16-bit activations, a 2048-token maximum context, and 128-token prefill chunks. The model stays loaded while the app process remains alive. There is no personal-memory feature. The API key stays inside the Rezoss AI app process; the AIOS bridge can request a completion but cannot read the saved key. Prompts, responses, and API keys are not written to logs.

When an API endpoint is configured, the app tries it first and sends the prompt to that endpoint over HTTPS. If that attempt fails, local fallback runs on-device when installed. The app does not follow redirects.

## Build

Run `build.sh` after sourcing the UN1CA `buildenv.sh`. To include the local fallback runtime, first build MLC's Android runtime and Gemma model library:

```sh
export MLC_LLM_SOURCE_DIR=/path/to/mlc-llm
./mlc/build-package.sh
```

Then run the ROM build. The app build discovers `mlc/dist/lib/mlc4j` automatically; set `MLC_ANDROID_DIST` to another generated `dist/lib/mlc4j` directory if needed. Without that package the ROM build still produces the API app, and the local-model download control is disabled. The app is signed with the ROM platform certificate and written to the existing archived APK output path.
