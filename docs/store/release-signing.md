# Подпись Android-релиза RouterDash

**Никогда не добавляйте keystore, пароли или приватные ключи в Git.** Потеря ключа может лишить возможности выпускать обновления приложения.

## 1. Создать ключ подписи

На доверенном компьютере с JDK 17 выполните:

```sh
keytool -genkeypair -v \
  -keystore routerdash-release.jks \
  -storetype JKS \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -alias routerdash
```

Задайте надёжные пароли и сохраните keystore, пароли и резервную копию в защищённых местах. Не отправляйте ключ в чат и не коммитьте его в репозиторий.

Для совместимых обновлений в Google Play и RuStore заранее согласуйте стратегию сертификата. Если пользователи должны бесшовно переходить между магазинами, сертификат APK, устанавливаемого из обоих магазинов, должен совпадать. При включении Play App Signing проверьте, какой именно сертификат будет использоваться для подписи распространяемых APK; upload key и app signing key могут быть разными.

## 2. Добавить GitHub Actions Secrets

В настройках репозитория откройте **Settings → Secrets and variables → Actions** и добавьте:

- `ANDROID_KEYSTORE_BASE64` — keystore, закодированный в Base64 одной строкой;
- `ANDROID_KEYSTORE_PASSWORD` — пароль keystore;
- `ANDROID_KEY_ALIAS` — alias ключа (например, `routerdash`);
- `ANDROID_KEY_PASSWORD` — пароль ключа.

PowerShell для Base64:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes(".\routerdash-release.jks"))
```

Скопируйте вывод в Secret `ANDROID_KEYSTORE_BASE64`. Не сохраняйте Base64 в репозитории или публичных issue.

## 3. Собрать подписанные файлы

После добавления всех четырёх Secrets откройте **Actions → Android release → Run workflow** или создайте тег `android-v2.5.1`.

Успешная сборка публикует артефакт с:
- `app-release.aab` — для Google Play и при необходимости RuStore;
- `app-release.apk` — для RuStore;
- `SHA256SUMS` — контрольные суммы.

Workflow проверяет подпись APK и AAB до публикации артефактов.

## 4. Первый выпуск

Google Play: загрузите AAB в Play Console и включите Play App Signing, внимательно выбрав схему ключей.  
RuStore: загрузите подписанный APK. Если используете AAB, предварительно добавьте сертификат подписи в RuStore Console согласно их инструкции.

Не меняйте `applicationId` (`ru.routerdash.android`) и не теряйте ключ подписи между версиями. Для каждого следующего выпуска увеличивайте `versionCode`.
