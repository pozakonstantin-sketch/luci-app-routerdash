# luci-app-routerdash

RouterDash is a responsive OpenWrt dashboard. This package is built as **APK v3 for OpenWrt 25.12+**.

## Requirements

- OpenWrt 25.12 or later (APK v3 package manager).
- LuCI installed (`luci-base`).
- A modern browser with JavaScript enabled.

OpenWrt 24.10 and older use OPKG/IPK and are not supported by this APK build. Do not rename the APK file to IPK.

## What the package installs

- `/www/dashboard.html` — the dashboard UI.
- LuCI controller and menu entry for RouterDash.
- An install script that backs up the existing `/www/index.html` and redirects the start page to the dashboard.
- An uninstall script that restores the saved start page when available.

The package is marked `noarch`; this describes the package contents, not a claim that every router model has been tested.

## Install

Download the APK from GitHub Releases, copy it to the router and run:

```sh
scp luci-app-routerdash-*.apk root@192.168.1.1:/tmp/
ssh root@192.168.1.1 "apk add --allow-untrusted /tmp/luci-app-routerdash-*.apk"
```

The release package is not signed with a key trusted by OpenWrt. Review the source and package contents before installing third-party software as root.

## Upgrade

Install the newer APK using the same `apk add --allow-untrusted` command. The package manager replaces the installed version.

## Uninstall

```sh
ssh root@192.168.1.1 "apk del luci-app-routerdash"
```

The previous start page is restored if the backup exists.

## Build

Python 3.11+ is required. The project includes a standalone APK v3 builder:

```sh
cd package
python3 build-apk.py
```

The generated package is placed in `package/dist/`. The canonical UI source is `dashboard.html` in the repository root.

## Documentation

- [Project overview and installation (Russian)](../README.md)
- [Project overview and installation (English)](../README.en.md)
- [FAQ](../docs/FAQ.md)
- [Contributing](../CONTRIBUTING.md)
