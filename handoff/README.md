# Handoff

APK сейчас намеренно не запускается автоматически. После завершения всех функций и ручного запуска GitHub Actions с `build_apk=true` здесь появятся:

- `NoirP2P-release.apk` — актуальный подписанный release APK;
- `releases/NoirP2P-<versionName>+<versionCode>.apk` — неизменяемая копия каждой версии; старые версии не удаляются;
- `NoirP2P-source.zip` — исходники проекта без APK и прежних ZIP;
- `NoirP2P-release-keystore.p12` и `NoirP2P-release-keystore-password.txt` — постоянная подпись для CI.

`versionCode` автоматически увеличивается по номеру запуска GitHub Actions; `versionName` имеет вид `1.0.<номер запуска>`. Release собирается только GitHub Actions, с R8 и ABI `arm64-v8a`. Подпись APK сверяется с сертификатом из `signing/NoirP2P-release-cert.pem`.

**Предупреждение:** по прямому запросу владельца keystore и пароль включены в публичный репозиторий и исходный ZIP. Любой может скачать их и подписать вредоносное APK как обновление Noir P2P. Эти файлы нельзя считать секретными, а удаление из будущего коммита не удалит Git-историю и уже сделанные копии. Более безопасный вариант — GitHub Actions Secrets.

Файлы текущего запуска также доступны во вкладке **GitHub → Actions → Android release APK и handoff → запуск → Artifacts** (release хранится 30 дней). Для PR собирается отдельный debug APK; он не заменяет release APK. Существующий `NoirP2P-debug.apk` сохранён как прежний артефакт.

> Первый release имеет другую подпись, чем прежний debug APK. Перед установкой release Android потребует удалить debug-версию; сохраните важные локальные данные заранее.
