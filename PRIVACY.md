# Privacy Policy for Ramblr

Ramblr is an Android dictation app that records speech, transcribes it, and inserts the result into text fields across apps. Ramblr is a private fork of [kafkasl/phone-whisper](https://github.com/kafkasl/phone-whisper).

## Data handling

Ramblr supports two transcription modes.

### Local mode

In local mode, audio is processed on-device using local speech recognition models. Audio does not leave the device.

If optional cleanup is also enabled while local mode is selected, the transcribed *text* (not audio) is sent from the device to the cleanup provider(s) you configure (see "Cleanup providers" below) to fix grammar, punctuation, and clarity — unless you configure fully on-device cleanup, in which case nothing leaves the device. The app shows a one-time confirmation before local transcription is first combined with off-device cleanup (no confirmation is needed for on-device-only cleanup), and the cleanup setting always names the destination host it sends text to.

### Cloud mode

In cloud mode, recorded audio is sent directly from the device to OpenAI's transcription API to generate text.

If optional cleanup is enabled, the transcribed text is also sent from the device to the cleanup provider(s) you configure (see "Cleanup providers" below) to improve punctuation, capitalization, and clarity.

## Cleanup providers

Cleanup sends the transcribed *text* (never audio) to the provider steps *you configure* in
Settings, tried in order, falling through to the next configured step when one fails. That
fallthrough is the point of the feature — so if you configure more than one provider, a failure
at your first choice means the text **is** sent to your next configured provider. Text is only
ever sent to steps you added yourself; there are no built-in silent fallbacks to providers you
didn't configure.

The possible destinations are:

- **OpenAI** (`api.openai.com`) — the default simple "Cloud" choice, and/or a direct-OpenAI
  waterfall step.
- **Anthropic** (`api.anthropic.com`) — a direct-Anthropic waterfall step.
- **A self-hosted or third-party OpenAI-compatible endpoint** (e.g. a router such as OmniRoute)
  — any custom base URL you set. This app has no way to vouch for the privacy practices of a
  custom endpoint; that responsibility is yours as the person who configured it.
- **Fully on-device** — cleanup runs against a small language model downloaded to the phone. A
  configuration whose only step(s) are on-device sends nothing off the device, ever: combined
  with local transcription mode, neither audio nor text leaves the phone.

The cleanup setting always names the destination host(s) it sends text to, so none of this is
silent. If every configured step fails, the raw transcript is used as-is — no request is sent to
any provider you did not configure.

## API keys

If you use cloud features, your API keys (OpenAI, Anthropic, and/or a custom endpoint's key) are
stored locally on your device in Android-Keystore-encrypted app storage and used to authenticate
requests sent directly to the corresponding provider.

I do not operate a relay server for these requests.

## Dictation history

Ramblr keeps a local history of your dictations (the raw transcript and, when cleanup ran, the
cleaned-up version) so a failed injection never loses your words. This history is stored only
on-device, in the app's private storage, and is excluded from Android backups (the app sets
`allowBackup=false`). It is never uploaded anywhere. You can turn history off in Settings, and
you can delete recorded entries from the history screen.

## Accessibility Service

Ramblr uses Android Accessibility Service only to identify the currently focused text field and insert dictated text after you explicitly interact with the floating overlay button.

Ramblr is not designed to monitor browsing, collect screen content for analytics, or perform background automation.

## Data collection

I do not run a backend for Ramblr and do not collect user accounts, analytics, or uploaded recordings myself.

Third-party services you choose to use, such as OpenAI, may process data according to their own terms and privacy policies.

## Contact

For questions about privacy, contact: pol.avms@gmail.com
