# FAQ

## Which OpenWrt versions are supported?

The current downloadable package is an **APK v3** package for **OpenWrt 25.12 or later**, with LuCI (`luci-base`) installed.

OpenWrt 24.10 and older use OPKG and IPK packages. The current APK cannot be installed there. A separate IPK build is needed before those releases can be supported.

## Does it work on every router architecture?

The package is marked `noarch` because it does not include architecture-specific executable binaries. However, not every router model has been tested. Dashboard data depends on the ubus methods, drivers and metric sources exposed by the firmware. Please report your model and firmware version if something is missing.

## Why does installation require `--allow-untrusted`?

The release APK is not signed with a package signing key trusted by OpenWrt. The flag allows installation of this local file:

```sh
apk add --allow-untrusted /tmp/luci-app-routerdash-<version>.apk
```

Only install software you trust. Review the source and release artifacts before running third-party code with root privileges.

## The dashboard does not open

Check that the package is installed and the page exists:

```sh
apk list --installed | grep routerdash
ls -l /www/dashboard.html
```

Then try opening `http://ROUTER-IP/dashboard.html` directly. Replace `ROUTER-IP` with the router's address. If it still fails, attach relevant `logread` output and the browser console error to an Issue. Redact secrets first.

## CPU, temperature or internet status is missing

Metric availability depends on the device. Some routers do not expose a thermal sensor, and interface names or ubus responses can differ between firmware builds. Missing temperature data may be normal; missing CPU or WAN data should be reported with the router model and `ubus call system board` output.

## How do I restore the previous LuCI start page?

Remove the package:

```sh
apk del luci-app-routerdash
```

The uninstall script restores the saved `/www/index.html.routerdash-backup`, if the installer created it.

## How do I update RouterDash?

Download the newer APK from [GitHub Releases](https://github.com/pozakonstantin-sketch/luci-app-routerdash/releases), copy it to the router and install it with `apk add --allow-untrusted`.

## Does RouterDash change my router configuration?

The dashboard is designed to read system information through LuCI/ubus. The package installation scripts also set the dashboard as the web start page and save the previous start page for restoration on uninstall. Review the source before installing and report any unexpected behaviour.

## Is OpenWrt 24.10 supported?

Not by the current APK v3 release. OpenWrt 24.10 uses OPKG/IPK. Do not rename the APK to IPK; the formats are different. A separate IPK build is required.
