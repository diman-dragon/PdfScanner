# Публикация в Google Play

## 0. Перед первой загрузкой
- **Смените имя пакета** `com.example.pdfscanner` на своё (например `com.yourname.pdfscan`):
  `applicationId` и `namespace` в `app/build.gradle.kts`, папки `app/src/main/java/...` и строки
  `package`/`import` во всех `.kt`. После первой загрузки в Play имя изменить нельзя.
- Проверьте название приложения в `app/src/main/res/values/strings.xml`.

## 1. Ключ подписи (делается один раз)
```
keytool -genkeypair -v -keystore release.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
```
Сохраните `release.jks` и пароли в надёжном месте (менеджер паролей + резервная копия вне GitHub).
**Не коммитьте** `release.jks` в репозиторий.

Преобразуйте файл в base64:
- Linux/macOS: `base64 -w0 release.jks`
- Windows (PowerShell): `[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks"))`

## 2. Секреты GitHub
Repo → Settings → Secrets and variables → Actions → New repository secret:

| Секрет | Значение |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | base64 из шага 1 |
| `RELEASE_KEYSTORE_PASSWORD` | пароль хранилища |
| `RELEASE_KEY_ALIAS` | `upload` (или ваш alias) |
| `RELEASE_KEY_PASSWORD` | пароль ключа |

## 3. Сборка релиза
Actions → **Release** → Run workflow → введите версию (например `2.0.0`).
Результат в Artifacts: `app-release.aab` (для Google Play) и `app-release.apk` (для проверки на телефоне).
`versionCode` берётся из номера запуска и растёт сам, Play требует каждый раз большее значение.

Перед загрузкой в Play обязательно установите `app-release.apk` и пройдите по основным сценариям:
релиз собирается с минификацией (R8), и именно там возможны сбои, которых нет в debug.

## 4. Google Play Console
1. Аккаунт разработчика (разовый взнос).
2. Создать приложение → загрузить `.aab` во внутреннее тестирование (Internal testing). Включить Play App Signing.
3. Заполнить разделы:
   - **Политика конфиденциальности**: разместите `docs/PRIVACY_POLICY.md` на публичной странице
     (GitHub Pages или Gist), впишите ссылку в Play Console и в `strings.xml` → `privacy_policy_url`
     (тогда пункт появится в настройках приложения).
   - **Безопасность данных (Data safety)**: приложение не передаёт документы на серверы разработчика,
     аккаунтов и аналитики нет. Используется разрешение «Камера», обработка и распознавание на устройстве. Отвечайте по факту.
   - **Рейтинг контента**, **целевая аудитория**, **реклама** (нет).
4. Карточка магазина: иконка 512×512, графика 1024×500, минимум 2 скриншота телефона, краткое (80 символов) и полное описание.
5. Для личных аккаунтов, созданных после ноября 2023, Google требует закрытое тестирование
   (сейчас 12 тестировщиков в течение 14 дней) до выхода в продакшн. Актуальные условия смотрите в Play Console.

## 5. Обновления
Меняйте `version_name` при запуске workflow. Новый `.aab` загружайте в нужный трек.
