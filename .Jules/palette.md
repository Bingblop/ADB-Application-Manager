## 2024-04-18 - Enforce aria-labels on icon-only buttons
**Learning:** Found several icon-only `<button>` elements (such as `⋯` menu buttons and `✕` close buttons) missing `aria-label`s. This pattern is common for small UI controls that lack text content.
**Action:** Always enforce `aria-label`s on icon-only buttons or empty elements with background sprites to ensure screen reader users understand the button's action. Note that they need to use the i18n system for translations.
