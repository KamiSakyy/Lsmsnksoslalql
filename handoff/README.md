# Handoff

GitHub Actions собирает приложение и кладёт свежие файлы прямо в эту папку на рабочей ветке:

- `NoirP2P-debug.apk` — debug-подписанный, R8-минифицированный APK только для `arm64-v8a`;
- `NoirP2P-source.zip` — исходники проекта (без APK и предыдущих ZIP).

Файлы автоматически обновляются успешным workflow. Та же пара доступна в **GitHub → Actions → Android APK и handoff → запуск → Artifacts → NoirP2P-handoff**; артефакт хранится 30 дней.
