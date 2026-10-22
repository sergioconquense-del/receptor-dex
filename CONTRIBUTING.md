# Contributing

[**English**](CONTRIBUTING.md) · [Español](CONTRIBUTING.es.md)

Thanks! This is an experimental v1.0 and needs help mainly with (see issues labelled `help wanted`):

1. **TV remote as mouse/keyboard**: only the Space key reaches DeX. The UIBC key/pointer code table DeX accepts is unknown.
2. **Cursor latency (~1 s)**: Media3 and audio sink buffering.
3. **Testing with other phones and TVs** and reporting what happens (use the "Test report" template).
4. **Boot start, TEARDOWN/PAUSE/PLAY, resolution change**: implemented, untested.

## Before sending a change
- Read [SPEC.md](SPEC.md) (architecture) and [REQUIREMENTS.md](REQUIREMENTS.md) (status); both are in Spanish.
- One change per pull request; say how you tested it (phone and TV model).
- **Licenses:** the project is Apache-2.0. Do not paste GPL/AGPL or otherwise incompatible code
  (e.g. Moonlight is GPL-3.0). If you base work on another project, credit it in `NOTICE.md`.
- Build with `./gradlew assembleDebug`.
