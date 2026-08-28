# Privacy Policy for Ramblr

Ramblr is an Android dictation app that records speech, transcribes it, and inserts the result into text fields across apps.

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
on-device, in the app's private storage, and is excluded from Android backups and from
device-to-device transfer. It is never uploaded anywhere. You can turn history off in Settings, and
you can delete recorded entries from the history screen.

The optional **Ramblr Voice** keyboard records the final accepted output in this same local
history before attempting its one-shot insertion, when history and editor retention are allowed.
This makes text recoverable after a stale editor, a rejected `InputConnection.commitText()` call,
or a dead connection without retrying into a different field. A failed insertion is never reported
as saved unless the history write succeeded. History remains subject to the user's history toggle,
and is always suppressed for editors that request no personalized learning.

## Quality log (off by default)

Ramblr has an optional diagnostic "quality log" for comparing transcription/cleanup providers. When
— and only when — you enable it in Settings → Data & Logs, each dictation's raw transcript and
cleaned-up text, along with the provider and model that produced them, are appended to a file in
the app's private on-device storage. It is **off by default**: nothing is written unless you turn
it on. The log never leaves the device on its own — it is excluded from Android backups, from
device-to-device transfer, and from the app's own manual backup files; the only way it leaves the
device is if you explicitly share it with the "Share quality log" button. Turning the toggle off
offers to delete the already-saved log, and uninstalling the app removes it.

## Android backup and device transfer

Ramblr participates in Android's backup and "Copy your data" device-transfer flows, but on a
strict opt-in basis: only your plain settings (`ramblr.xml` — overlay appearance, personas,
provider chain ordering, per-app persona mappings, advanced toggles) and your custom overlay
icon are included.

Everything else is excluded by omission, notably:

- **Your API keys.** These live in Android-Keystore-encrypted storage whose key never leaves the
  originating device, so restoring their ciphertext elsewhere could not decrypt them anyway. You
  re-enter your key(s) once on a new device, which is the correct behavior.
- **Your dictation history.**
- The downloaded model files and the model-catalog network cache.

The exact rules are readable in the repository at `app/src/main/res/xml/backup_rules.xml`
(API 30) and `app/src/main/res/xml/data_extraction_rules.xml` (API 31+).

## Accessibility Service

Ramblr uses Android Accessibility Service only to identify the currently focused text field and insert dictated text after you explicitly interact with the floating overlay button.

Ramblr is not designed to monitor browsing, collect screen content for analytics, or perform background automation.

## Optional Ramblr Voice keyboard

Ramblr Voice is an opt-in Android input method. Ramblr only opens Android's supported input-method
settings screen after you tap **Enable voice keyboard**; it cannot and does not auto-enable or
auto-select itself. After you enable it, you select it with Android's keyboard switch control.

The keyboard shows streaming partial text locally in its own panel and inserts the final result
through the exact `InputConnection` and editor identity that were present when dictation began.
There is one insertion attempt only: output is never redirected or retried into a replacement
field. Hiding the keyboard, finishing/restarting input, changing the destination, or destroying the
service invalidates delivery immediately and cancels the runtime; audio-reader/native teardown then
finishes off the main thread while microphone ownership remains held until reader teardown.

Ramblr Voice uses the same local/cloud transcription and cleanup providers you configured for the
floating-button mode. Local processing stays on device; cloud routing sends audio/text directly to
the same configured provider destinations described above. The keyboard adds no separate provider,
account, relay, or hidden routing path.

Password, PIN, visible-password, and web-password editor variations are blocked before runtime or
capture starts: the mic is disabled and no audio or text is processed. Android's
`IME_FLAG_NO_PERSONALIZED_LEARNING` is different: dictation and insertion remain available, but the
IME session suppresses dictation history, transcript-bearing quality diagnostics, and vocabulary
suggestion collection. This policy affects only the IME session and does not change the existing
accessibility-host behavior.

## Text-selection menu ("Clean up with Ramblr")

Ramblr registers an entry in Android's text-selection menu (the Copy/Paste/Share popup). It is
invoked only when you select text yourself and then tap Ramblr in that menu; Android hands the
selected text to Ramblr at that moment and at no other time. This entry point does not use the
Accessibility Service at all and works whether or not the service is enabled — nothing above
changes.

The selected text is then treated exactly like a dictated transcript: it runs through the same
cleanup providers you have configured, so if that configuration includes a cloud provider, the
selected text is sent to that provider (turn "Use cloud for Cleanup" off to keep cleanup
on-device). The cleaned result is placed on your clipboard and returned to the app you selected
the text in; if that app reports the field as read-only, Ramblr copies the result to the
clipboard and tells you, rather than changing anything.

## Data collection

I do not run a backend for Ramblr and do not collect user accounts, analytics, or uploaded recordings myself.

Third-party services you choose to use, such as OpenAI, may process data according to their own terms and privacy policies.
