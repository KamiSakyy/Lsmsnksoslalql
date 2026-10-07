# Handoff

После настройки GitHub Actions secrets успешный workflow выпускает **подписанный release APK** и исходники:

- `NoirP2P-release.apk` — актуальный минифицированный release APK; подпись проверяется CI по постоянному публичному сертификату из `signing/NoirP2P-release-cert.pem`;
- `releases/NoirP2P-<versionName>+<versionCode>.apk` — неизменяемая копия каждой версии; следующие выпуски добавляются, старые не удаляются;
- `NoirP2P-source.zip` — исходники проекта без APK и прежних ZIP.

`versionCode` автоматически увеличивается по номеру запуска GitHub Actions; `versionName` имеет вид `1.0.<номер запуска>`. Release собирается только GitHub Actions, с R8 и ABI `arm64-v8a`.

Для публикации релиза владелец репозитория должен один раз добавить Actions secrets `ANDROID_SIGNING_KEYSTORE_BASE64`, `ANDROID_SIGNING_STORE_PASSWORD` и `ANDROID_SIGNING_KEY_PASSWORD`. **Приватный keystore не коммитится**: репозиторий публичный, и опубликованный signing key позволил бы посторонним подписывать поддельные обновления. См. `signing/README.md`.

Файлы каждого успешного запуска также доступны в **GitHub → Actions → Android release APK и handoff → запуск → Artifacts** (временное хранение). Для проверки PR собирается отдельный debug APK; он не заменяет release APK. Существующий `NoirP2P-debug.apk` сохранён как прежний артефакт.

> Важно для первого перехода с прежнего debug APK: у него другая подпись, поэтому Android не сможет обновить его поверх. Потребуется удалить старую debug-версию перед установкой первого release APK; сохраните нужные локальные данные заранее.
