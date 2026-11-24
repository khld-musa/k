# Self-hosted fonts

These variable-font files are vendored locally so `next build`/`next dev` never needs
network access to `fonts.googleapis.com`/`fonts.gstatic.com` (the deploy environment may not
have outbound internet access). They are loaded via `next/font/local` in `app/layout.tsx`.

| File | Font | License | Source |
| --- | --- | --- | --- |
| `JetBrainsMono-Variable.woff2` | JetBrains Mono (latin, wght 100-800) | SIL Open Font License 1.1 | fonts.gstatic.com (Google Fonts CDN), same file Google Fonts serves for `JetBrains+Mono:wght@100..800` |
| `Roboto-Variable.woff2` | Roboto (latin, wght 100-900) | Apache License 2.0 | fonts.gstatic.com (Google Fonts CDN), same file Google Fonts serves for `Roboto:wght@100..900` |

To update: fetch `https://fonts.googleapis.com/css2?family=<Font>:wght@<range>&display=swap`
with a modern browser `User-Agent` header (so it returns `woff2` variable-font URLs), take the
`latin` `@font-face` block's `src` URL, and re-download it over this file.
