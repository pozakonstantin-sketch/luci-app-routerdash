# RouterDash — современный дашборд для OpenWrt

[![Build APK](https://github.com/pozakonstantin-sketch/luci-app-routerdash/actions/workflows/build.yml/badge.svg)](https://github.com/pozakonstantin-sketch/luci-app-routerdash/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/pozakonstantin-sketch/luci-app-routerdash)](https://github.com/pozakonstantin-sketch/luci-app-routerdash/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

**RouterDash** — адаптивная веб-панель мониторинга OpenWrt с тёмным интерфейсом. Она показывает состояние роутера, сеть, Wi-Fi, клиентов, службы, загрузки, пакеты и системный журнал в одном месте.

> **Важно о совместимости:** текущий установочный файл — пакет **APK v3** для **OpenWrt 25.12 и новее**. OpenWrt 24.10 и более ранние версии используют OPKG и формат IPK; этот APK на них не устанавливается.

[English documentation](README.en.md) · [Установка](#установка) · [FAQ](docs/FAQ.md) · [Как помочь проекту](CONTRIBUTING.md) · [Сообщить об ошибке](https://github.com/pozakonstantin-sketch/luci-app-routerdash/issues/new/choose)

![Обзор RouterDash](docs/screens/overview.jpg)

## Возможности

- **Общее состояние:** интернет, CPU, память, температура, load average, диски, соединения и службы.
- **Живые метрики:** CPU, RAM, температура, аптайм и состояние WAN.
- **Сеть и Wi-Fi:** интерфейсы, беспроводные сети и подключённые клиенты.
- **Службы и журналы:** просмотр состояния служб и системных сообщений.
- **Пакеты и загрузки:** быстрый доступ к соответствующим разделам.
- **VPN и туннели:** отображение состояния поддерживаемых интеграций, если они присутствуют в системе.
- **Адаптивный интерфейс:** поддержка мобильных экранов, сенсорного управления и широких экранов.
- **Экономный опрос:** последовательное обновление метрик; опрос приостанавливается, когда вкладка браузера скрыта.
- **Интеграция с LuCI:** используется штатная сессия LuCI, отдельная учётная запись RouterDash не создаётся.

## Скриншоты

<details open>
<summary><b>Обзор</b></summary>

![Обзор](docs/screens/overview.jpg)
</details>
<details>
<summary><b>Сеть</b></summary>

![Сеть](docs/screens/network.jpg)
</details>
<details>
<summary><b>Wi-Fi</b></summary>

![Wi-Fi](docs/screens/wifi.jpg)
</details>
<details>
<summary><b>Клиенты</b></summary>

![Клиенты](docs/screens/clients.jpg)
</details>
<details>
<summary><b>Службы</b></summary>

![Службы](docs/screens/services.jpg)
</details>
<details>
<summary><b>Загрузки</b></summary>

![Загрузки](docs/screens/downloads.jpg)
</details>
<details>
<summary><b>Пакеты</b></summary>

![Пакеты](docs/screens/packages.jpg)
</details>
<details>
<summary><b>Журнал</b></summary>

![Журнал](docs/screens/logs.jpg)
</details>

## Требования и совместимость

| Компонент | Требование |
|---|---|
| OpenWrt | **25.12 или новее** |
| Пакетный менеджер | APK v3 |
| LuCI | Установленный `luci-base` |
| Архитектура пакета | `noarch` |
| Браузер | Современный браузер с JavaScript |

Пакет `noarch` не содержит архитектурно-зависимых бинарных файлов. Это не означает, что RouterDash уже проверен на каждой модели роутера: совместимость зависит от доступных ubus-методов, драйверов и источников системных метрик. Сообщайте модель устройства и версию прошивки в Issues — это поможет расширить матрицу тестирования.

**Не поддерживается текущим APK:** OpenWrt 24.10, 23.05 и более ранние версии (OPKG/IPK). Поддержка этих веток потребует отдельной сборки IPK.

## Установка

1. Откройте [GitHub Releases](https://github.com/pozakonstantin-sketch/luci-app-routerdash/releases).
2. Скачайте файл `luci-app-routerdash-*.apk` из последнего стабильного релиза.
3. Скопируйте его на роутер и установите:

```sh
scp luci-app-routerdash-*.apk root@192.168.1.1:/tmp/
ssh root@192.168.1.1 "apk add --allow-untrusted /tmp/luci-app-routerdash-*.apk"
```

Замените `192.168.1.1` на адрес своего роутера.

Пакет в Releases не подписан ключом OpenWrt, поэтому для локального файла используется `--allow-untrusted`. Перед установкой стороннего пакета на роутер с правами root рекомендуется ознакомиться с исходным кодом и содержимым релиза.

После установки откройте адрес роутера в браузере. Если сессии LuCI нет, сначала появится штатная форма входа. Дашборд также доступен напрямую по адресу `/dashboard.html` и через меню LuCI.

> Установочный скрипт делает RouterDash стартовой страницей и сохраняет прежний `/www/index.html` в резервную копию. При штатном удалении пакета прежняя стартовая страница восстанавливается. Перед установкой на нестандартно настроенную систему рекомендуется сохранить резервную копию конфигурации.

## Обновление

Скачайте новый APK из Releases, скопируйте на роутер и выполните ту же команду установки:

```sh
scp luci-app-routerdash-*.apk root@192.168.1.1:/tmp/
ssh root@192.168.1.1 "apk add --allow-untrusted /tmp/luci-app-routerdash-*.apk"
```

## Удаление

```sh
ssh root@192.168.1.1 "apk del luci-app-routerdash"
```

При штатном удалении пакет восстанавливает резервную копию стартовой страницы, если она была создана установщиком.

## Сборка из исходников

Требуется Python 3.11+. OpenWrt SDK для текущего генератора APK не нужен:

```sh
cd package
python3 build-apk.py
```

Результат появится в `package/dist/`. Скрипт берёт единственный исходник интерфейса из `dashboard.html` в корне репозитория.

## Сообщить об ошибке

Перед созданием Issue проверьте [FAQ](docs/FAQ.md). В отчёт добавьте:

- модель роутера и вывод `ubus call system board`;
- версию RouterDash;
- что именно ожидалось и что произошло;
- относящиеся к проблеме сообщения `logread`;
- скриншот или ошибку консоли браузера, если проблема визуальная.

Не публикуйте пароли, токены LuCI, публичные IP-адреса и другие секреты. Удалите их из логов перед отправкой.

## Участие в разработке

Pull Request приветствуются. Сначала прочитайте [CONTRIBUTING.md](CONTRIBUTING.md). Особенно полезны проверки на разных SoC, версиях OpenWrt, браузерах и конфигурациях LuCI.

## Лицензия

RouterDash распространяется под лицензией [MIT](LICENSE).
