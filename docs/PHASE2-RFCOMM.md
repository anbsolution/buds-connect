# Phase 2 RFCOMM diagnostic notes

Transport UUIDs are taken from the public QuickBuds protocol documentation:

- `0000079A-D102-11E1-9B23-00025B00A5A5` (first)
- `00001107-D102-11E1-9B23-00025B00A5A5` (fallback)

This build uses secure RFCOMM sockets only. It does not use generic SPP UUID `00001101` and does not fall back to insecure RFCOMM.

Read-only queries:

- `0x0100` handshake
- `0x0103` product ID
- `0x0106` battery
- `0x0105` firmware response (raw bytes)

No device-setting writes, gesture changes, EQ changes, Find My Earbuds commands, or firmware updates are sent.

The parser accepts complete unsliced frames only. It ignores pushes and unknown commands. Case battery can be absent when the case lid is closed. Missing values are shown as `Not reported`, never estimated.

This is an initial protocol diagnostic implementation, not yet verified on physical Nord Buds 2R hardware. First device validation must confirm RFCOMM connection, handshake, product ID `062C14`, and plausible battery readings.
