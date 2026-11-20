# Phase 3 — Model-specific Nord Buds 2R controls

Target product ID: `062C14` (OnePlus Nord Buds 2r).

## Implemented
- Secure RFCOMM connection using the protocol UUIDs.
- Read handshake capability bitmap and product identity before enabling writes.
- Battery query `0x0106`, firmware query `0x0105`, EQ query `0x010F`, gesture table query `0x0108`.
- Built-in EQ preset selection using `0x0406`, followed by `0x010F` read-back.
- Find My Earbuds command `0x0400` with start/stop payload.
- Device writes are gated by exact product ID and advertised capability bitmap.

## Nord Buds 2r model profile
- Product ID `062C14`.
- EQ preset slots: 0 Balanced, 1 Bold, 2 Bass.
- Custom EQ is advertised, but the custom-band editor is deferred until its payload and read-back are validated.
- Gesture table is readable; gesture writes are deferred until action-option mapping is validated.
- Find feature is listed in the model profile.

## Limitations
- Missing battery levels are shown as `Not reported`; no percentages are estimated.
- Firmware is displayed as device/version type pairs, not a guessed marketing version.
- A Find command acknowledgement does not prove the tone was heard; physical testing is required.
- Call noise reduction is the built-in dual-mic AI call feature; no separate app toggle is implemented.
- Nord Buds 2r does not support Active Noise Cancellation (ANC).
- The parser supports whole unsliced frames only; large custom EQ frames are not sent.
- Physical Nord Buds 2r validation and GitHub Actions build are pending.
