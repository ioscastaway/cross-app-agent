# Third-party assets

## Fluent Emoji — Microsoft

The bubble's six faces in `app/src/main/res/drawable-nodpi/face_*.png` are Microsoft's Fluent Emoji,
resized to 256 px and renamed. Nothing else was changed.

- Source: https://github.com/microsoft/fluentui-emoji
- Licence: MIT, full text in `fluent-emoji-LICENSE.txt`

| File | Emoji |
|---|---|
| `face_idle.png` | Waving hand |
| `face_listening.png` | Microphone |
| `face_working.png` | Hourglass not done |
| `face_asking.png` | Robot |
| `face_done.png` | Thumbs up |
| `face_failed.png` | Confused face |

The launcher icon reuses the same waving hand: `mipmap-*/ic_launcher_foreground.png` and the
monochrome layer are that emoji, composited over a gradient generated in this repository. The
gradient and the icon layout are original; the hand is Microsoft's, under the same MIT licence.
