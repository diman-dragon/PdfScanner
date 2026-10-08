# Сторонние компоненты

| Компонент | Назначение | Лицензия |
|---|---|---|
| Tesseract OCR (через Tesseract4Android) | распознавание текста | Apache License 2.0 |
| tessdata_fast (rus, eng) | языковые модели OCR | Apache License 2.0 |
| PDFBox-Android | сборка и объединение PDF | Apache License 2.0 |
| PT Sans | шрифт невидимого текстового слоя PDF | SIL Open Font License 1.1 (`app/src/main/assets/fonts/OFL.txt`) |
| Google ML Kit Document Scanner | съёмка и выравнивание листа | условия Google ML Kit |
| Jetpack Compose, Room, Navigation | интерфейс и хранение | Apache License 2.0 |

## Добавить язык распознавания
Скачайте `<код>.traineddata` из https://github.com/tesseract-ocr/tessdata_fast и положите в
`app/src/main/assets/tessdata/`. Язык сам появится в настройках (например `ukr`, `srp`, `deu`).
Каждая модель добавляет в APK 2–5 МБ.
