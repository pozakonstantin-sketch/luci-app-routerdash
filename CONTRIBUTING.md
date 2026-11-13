# Contributing to RouterDash

Thanks for helping improve RouterDash! Bug reports, compatibility reports, documentation fixes and code contributions are welcome.

## Before opening an issue

1. Check the [FAQ](docs/FAQ.md) and search existing Issues.
2. Confirm that you are using OpenWrt 25.12 or later with LuCI for the current APK release.
3. Include:
   - router manufacturer and model;
   - OpenWrt version and target (output of `ubus call system board`);
   - RouterDash package version;
   - steps to reproduce and expected/actual behaviour;
   - relevant `logread` output and browser-console errors.

Please redact passwords, LuCI session tokens, public IP addresses, MAC addresses and other private information before posting logs.

## Development setup

The dashboard is maintained in the root-level `dashboard.html` file (HTML, CSS and JavaScript). The package builder copies this canonical file into the package tree.

A local package build requires Python 3.11+:

```sh
cd package
python3 build-apk.py
```

The generated APK is written to `package/dist/`. The current package is APK v3 for OpenWrt 25.12+. An IPK/OPKG build for OpenWrt 24.10 and older is not currently provided.

## Testing changes

Before submitting a Pull Request:

- run the package build;
- run the repository's static checks / CI workflow;
- test the changed screen in a browser where possible;
- test on a real OpenWrt router when the change depends on ubus, procfs, sysfs or service behaviour;
- state clearly which device and firmware were used.

Do not claim hardware compatibility based only on a successful build.

## Pull Requests

1. Create a feature branch from the current development base.
2. Keep changes focused and explain the user-visible impact.
3. Update documentation and changelog when behaviour or installation changes.
4. Include test evidence and note anything that still needs real-device verification.

## Security

Do not include credentials, session tokens, private router configurations or personal network information in commits or test fixtures. If you discover a security issue, avoid publishing exploit details in a public Issue; contact the maintainer privately through GitHub.
