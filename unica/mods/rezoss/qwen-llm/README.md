# Rezoss AI API app

This module builds `QWEN_LLM_rezoss.apk`, a small settings app and trusted API bridge for the existing ROM text-generation flow.

## Setup

1. Install the ROM's `QWEN_LLM_rezoss.apk` and open **Rezoss AI**.
2. Enter an OpenAI-compatible chat-completions HTTPS URL, a model name, and an API key.
3. Save the settings. The API key is encrypted with Android Keystore and is not included in the ROM build.

The default URL is `https://api.openai.com/v1/chat/completions`. Providers must accept a non-streaming Chat Completions request and return text in `choices[0].message.content`.

## Data flow

The ROM keeps its existing screen-text extraction, prompt adaptation, and reply/summary formats. The inference backend sends the resulting text to the configured endpoint and validates the response before returning it to the existing system caller. The API key stays inside the Rezoss AI app process; the AIOS bridge can request a completion but cannot read the saved key. Prompts, responses, and API keys are not written to logs.

Because generation uses a remote API, extracted screen text is sent to the endpoint selected by the user. The app requires HTTPS and does not follow redirects.

## Build

Run `build.sh` after sourcing the UN1CA `buildenv.sh`. The app is signed with the ROM platform certificate and written to the existing archived APK output path. It no longer downloads or embeds Qwen model weights.
