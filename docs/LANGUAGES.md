# 介面語言

自 0.1.6 起支援繁體中文、簡體中文及英文，不需額外下載語言包。

| 系統第一語言 | App 介面 |
| --- | --- |
| 繁體中文、zh-Hant、台灣／香港／澳門中文 | 繁體中文 |
| 簡體中文、zh-Hans、中國大陸／新加坡中文、未指定地區的中文 | 簡體中文 |
| 英文（任何地區） | 英文 |
| 其他語言，例如日文、法文、韓文、阿拉伯文 | 英文 |

語言標籤明確指定 Hans／Hant 時，以指定的文字系統優先。僅採系統第一語言，因此「法文、繁體中文」的語言清單會顯示英文。切換系統語言後重新開啟 App。系統相片選擇器、瀏覽器授權頁及 Nextcloud 網頁由各自的語言設定控制。

翻譯包含主介面、編輯器、傳輸通知、指紋解鎖說明及 App 自己產生的錯誤訊息。使用者的檔名、相簿名稱、帳號資訊及備份目的地保留原值，切換語言不會在雲端重新命名或移動檔案。伺服器／系統傳回的文字及以前已儲存的歷史錯誤保留原文。

## 開發維護

- `app/src/main/res/values/strings.xml`：完整英文預設資源。
- `app/src/main/res/values-zh/strings.xml`：簡體中文。
- `app/src/main/res/values-b+zh+Hant/strings.xml`：繁體中文。
- `i18n/Localization.kt`：第一語言解析、背景工作共用文字資源及組態快取。
- Activity 同樣使用解析後的語言，讓 App 內建播放控制與介面保持一致。

新增文字必須同步三份資源，並保留編號格式參數。執行 `python scripts/check-translations.py` 檢查完整性與參數一致性；Android 測試中的 `LocalizationTest` 驗證語系解析及實際 Compose 頁面。

使用 Android 資源機制，參考 [Android 本地化文件](https://developer.android.com/guide/topics/resources/localization)。無第三方翻譯或分析服務。
