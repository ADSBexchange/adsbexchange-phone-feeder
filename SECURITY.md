# Security

Please report security problems privately, not in a public issue: use this repository's
**Security** tab, then **Report a vulnerability**
(https://github.com/ADSBexchange/adsbexchange-phone-feeder/security/advisories/new). Include what
you found, how to reproduce it, the app version (Settings > About), and the phone model and
Android version.

We acknowledge reports within 5 business days. Once a fix is released on Google Play, we publish
a security advisory for the vulnerability and credit the reporter, unless they'd rather not be
named. Please give us 90 days to release a fix before disclosing the vulnerability publicly.

Of particular interest:

- anything that could run code in the app through readsb, which parses radio input anyone can
  transmit, or through the USB code (librtlsdr, libusb and the app's own USB layer), which talks to
  whatever device is plugged in;
- anything that leaks a feeder's precise location or feeder ID beyond what the README and the
  app's "What ADS-B Exchange receives" (Settings > About) describe, including through "Share
  diagnostics" or the log;
- a way for another app on the phone to read the receiver position, reach readsb's ports, or
  impersonate readsb.

For help with the app itself, ask on ADS-B Exchange's Discord server (Settings > Help).
