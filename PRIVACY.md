# Privacy Policy for Ramblr

Ramblr is an Android dictation app that records speech, transcribes it, and inserts the result into text fields across apps. Ramblr is a private fork of [kafkasl/phone-whisper](https://github.com/kafkasl/phone-whisper).

## Data handling

Ramblr supports two transcription modes.

### Local mode

In local mode, audio is processed on-device using local speech recognition models. Audio does not leave the device.

If optional cleanup is also enabled while local mode is selected, the transcribed *text* (not audio) is sent from the device to OpenAI's chat API to fix grammar, punctuation, and clarity. The app shows a one-time confirmation before this combination is first enabled, and the cleanup setting always names the destination host it sends text to.

### Cloud mode

In cloud mode, recorded audio is sent directly from the device to OpenAI's transcription API to generate text.

If optional cleanup is enabled, the transcribed text is also sent directly from the device to OpenAI's chat API to improve punctuation, capitalization, and clarity.

## API keys

If you use cloud features, your OpenAI API key is stored locally on your device in app storage and used to authenticate requests sent directly to OpenAI.

I do not operate a relay server for these requests.

### Custom cleanup endpoint

Cleanup can optionally be pointed at any OpenAI-compatible chat completions API instead of
OpenAI's, by setting a custom base URL and model name in Settings (e.g. to use a self-hosted
router such as OmniRoute). In this mode, the transcribed text is sent to whatever host you
configure — not to OpenAI — using the same API key field as Bearer token auth. This app has no
way to vouch for the privacy practices of a custom endpoint; that responsibility is yours as the
person who configured it. The cleanup toggle's subtitle always names the actual destination host
so this is never silent. If the configured endpoint is unreachable, cleanup is skipped and the
raw transcript is used instead — no request is sent elsewhere as a fallback.

## Accessibility Service

Ramblr uses Android Accessibility Service only to identify the currently focused text field and insert dictated text after you explicitly interact with the floating overlay button.

Ramblr is not designed to monitor browsing, collect screen content for analytics, or perform background automation.

## Data collection

I do not run a backend for Ramblr and do not collect user accounts, analytics, or uploaded recordings myself.

Third-party services you choose to use, such as OpenAI, may process data according to their own terms and privacy policies.

## Contact

For questions about privacy, contact: pol.avms@gmail.com
