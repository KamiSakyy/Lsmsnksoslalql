# Tsuyu Android

Tsuyu — персональный Android-мессенджер на Java. Поддержка Android 8–16 (`minSdk 26`, `compileSdk/targetSdk 36`); постоянный package name: `com.tsuyu.messenger`.

## Сборка и обновления

GitHub Actions workflow: `.github/workflows/android.yml`. Он собирает debug/release APK, публикует прямой файл **`app-release.apk`** и ZIP исходников в GitHub Release. После сборки нативные библиотеки Signal очищаются от DWARF debug-данных, остаётся ABI **arm64-v8a**, затем APK выравнивается и подписывается. CI блокирует публикацию, если итоговый APK больше **30 000 000 байт**. Эмуляторы не запускаются. 32-битные устройства этой сборкой не поддерживаются. Debug APK имеет suffix `.debug` и не подходит для обновления release-версии.

Release APK подписывается постоянным `signing/tsuyu-release.p12`; `versionCode` автоматически растёт в CI. Владелец проекта явно выбрал хранение keystore и пароля в публичном Git. Это означает, что любой посетитель репозитория может получить ключ и подписывать APK как Tsuyu. Не используйте этот ключ для распространения приложения с чувствительными данными; если решение изменится, отзовите/замените ключ до публикации пользователям.

## Firebase

Клиент использует только Firebase Authentication и Realtime Database; правила находятся в `database.rules.json`, конфигурация deploy — в `firebase.json`. После настройки Firebase:

```sh
firebase deploy --only database
```

Используйте только публичную Android client config. Корневой файл `meowmessenger-firebase-adminsdk-…json` уже присутствовал в исходном репозитории и не используется приложением или workflow. Он содержит Admin credential: немедленно отзовите/ротируйте этот service-account key в Google Cloud/Firebase IAM и не копируйте его в Android, CI artifacts или source handoff.

## Протокол и ограничения

- Личные сообщения используют официальный `libsignal-android`: PQXDH/Double Ratchet; приватные состояния шифруются локально ключом Android Keystore. Исходник instrumentation-теста round-trip присутствует, но workflow его не запускает: проверка Signal на устройстве ещё не подтверждена.
- RTDB хранит шифротекст сообщений и публичные prekey bundle. SHA-256 отпечатки можно сверять вне чата. При изменении ключа приложение блокирует отправку, пока пользователь не подтвердит новый отпечаток и не сбросит сеанс.
- Профильные поля и presence **ограничиваются правилами RTDB**, но это не клиентское шифрование профиля: Firebase/оператор проекта, имеющий административный доступ, может прочитать данные. Username, публичные настройки, ключи и presence также не скрыты end-to-end.
- Фото/видео/голосовые передаются Base64 внутри E2EE payload и RTDB; крупные вложения ограничены лимитом размера записи/трафиком RTDB. Firebase Storage не используется.
- FCM client receiver и foreground RTDB listener реализованы. Доставку FCM инициирует доверенный server-side sender, которого в этом Android-only проекте нет; фоновое RTDB-обнаружение зависит от Android/производителя и не гарантируется после force-stop/ограничения фоновой работы.
- Круглые видеосообщения и P2P аудио-/видеозвонки пока не реализованы. Архивы `Meow-main (6)/(8).zip` с эталонной реализацией в checkout отсутствовали, поэтому точное совпадение с ними подтвердить нельзя.

Исходный handoff-архив версии находится в `handoff/` и исключает корневой Firebase Admin JSON.
