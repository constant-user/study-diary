# Дневник — Android-обёртка

Лёгкая нативная обёртка (WebView) вокруг веб-приложения дневника.
**Ноль разрешений**: в манифесте нет `uses-permission` — у приложения нет
доступа в сеть, а данные дневника не покидают папку приложения.

## Как это устроено (и почему безопасно)

- HTML/JS/CSS лежат в `app/src/main/assets/` и раздаются через
  `shouldInterceptRequest` по зарезервированному Android домену
  `https://appassets.androidplatform.net/assets/…` — рекомендованный Google
  подход вместо `file://`: корректная модель одинакового происхождения,
  localStorage и CSP работают как в браузере.
- Любой запрос, отличный от локальных assets, отбрасывается пустым ответом —
  ни один внешний адрес в приложении не откроется.
- `allowFileAccess` / `allowContentAccess` выключены — скрипты не читают файлы.
- Экспорт («Экспорт JSON» / «Экспорт текста») идёт через узкий мост
  `DiaryAndroid.saveFile` в папку «Загрузки/Дневник» (MediaStore, без
  разрешений), имя файла очищается от опасных символов.
- Импорт — через стандартный выбор файла (`onShowFileChooser`).
- Кнопка «Назад» сначала закрывает открытый диалог или поиск внутри страницы.

## Сборка APK

Требуется JDK 17+ и Android SDK (`platforms;android-34`, `build-tools;34.0.0`):

```powershell
sdkmanager "platforms;android-34" "build-tools;34.0.0"
powershell -ExecutionPolicy Bypass -File android\build-apk.ps1
```

Скрипт соберёт APK без Gradle (aapt2 → javac → d8 → zipalign → apksigner)
и подпишет его debug-ключом: `android/bin/study-diary.apk`.

Перед сборкой в `assets` копируется текущее веб-приложение из родительской
папки (`index.html`, `app.js`, `style.css`) — версии всегда совпадают.

## Минимальная версия

Android 10 (API 29) — на более старых версиях MediaStore Downloads недоступен.
