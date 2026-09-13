# NextPhoto 0.1.7 測試與驗收紀錄

**測試結果：57／57 項自動測試通過；Android lint 0 errors、52 warnings。** 本版將 App 更名為 NextPhoto，桌面圖標直接採用使用者提供的 PNG，登入頁、關於頁及傳輸通知使用新名稱。保留原套件識別、簽名及雲端加密資料夾位置，以便覆蓋更新。既有多語系、相簿、傳輸、編輯、加密與指紋入口測試維持通過；指紋硬體及真實 Nextcloud 讀寫仍待實機驗證。

## 環境

- Windows 開發環境，專案私有 Temurin JDK 21；Gradle 9.4.1、AGP 9.2.0。
- compileSdk／targetSdk 36，minSdk 29。
- Robolectric 4.16.1，Android API 36；UI 使用原生圖形模式與 Compose 測試。
- 通訊測試使用 MockWebServer，沒有連線至使用者的真實 Nextcloud，也沒有接觸真實照片。

## 自動驗證

執行命令：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

測試套件共 57 項：

| 範圍 | 項目 |
| --- | --- |
| 協定，10 項 | HTTPS 網址、連接埠／子路徑、Unicode 檔名、跨站阻擋、相簿成員刪除邊界、移動防覆蓋、失敗 PROPPATCH、成功／失敗 propstat、日期格式、不完整 XML、空間／授權錯誤分類 |
| 資料庫，5 項 | 十萬筆資料分頁、檔案 ID 在改名後保留、移出相簿保留原檔、重複備份排程去重、取消狀態不可被完成狀態覆蓋、清空歷史保留待傳與去重、舊版資料庫升級 |
| 傳輸，6 項 | 條件式上傳與成功確認、同名保留雙方、程序中斷後不需要來源檔即可確認已提交內容、配額不足、21 MiB 三分塊組合、取消不發起上傳 |
| UI，3 項 | 空網址禁止登入、繁體中文登入畫面、雲端照片網格、全螢幕左右滑動、傳輸與設定導覽、淺色／深色渲染、清空歷史按鈕與待傳工作保留 |
| 加密，10 項 | 密碼包裹與跨工作階段復原、錯密碼與跨相簿拒絕、竄改／截斷／角色替換拒絕、隨機 nonce、鎖定清零金鑰與回收 Bitmap、過大輸入拒絕、一般移動禁止明文寫入專用目錄、模擬雲端匯入只傳密文且無磁碟快取、配額不足不發布照片也不刪原檔、解密圖片 UI 在背景返回後移除並要求重新解鎖及 FLAG_SECURE 設定 |
| 加密影片，10 項 | 分塊密文與索引最後提交、跨區塊 seek／EOF、區塊替換拒絕、鎖定清零緩衝及禁止重開、配額不足不發布索引、短讀與空檔拒絕、取消後不發布索引、索引長度／版本檢查、超過 2 GiB 的進度位置、真實 MP4 的 H.264／AAC 加密資料來源解析 |
| 語言，7 項 | 13 種語言標籤解析、法文優先且第二語言為中文時回退英文、組態變更後刷新文字快取、英文／簡體中文／法文系統 UI、香港繁體中文資源 |
| 編輯，6 項 | 裁剪框平移與邊界、角點縮放與最小範圍、照片留白座標命中、先旋轉後裁剪的像素結果、小數角度預覽與輸出一致、實際手勢拖曳／旋轉滑桿／歸零 |

十萬筆測試會一次插入每批 1000 筆、共 100 批，並確認兩個 90 筆頁面及剩餘項目計數。開發機上約 1–2 秒完成該案例；此數據是 JVM 模擬資料庫驗證，不代表手機幀率或真實網路索引速度。

加密影片測試使用兩秒合成 MP4（測試圖案與 440 Hz 正弦波），沒有私人影像。Media3 從自訂加密資料來源讀取並解析視訊與音訊樣本，另以模擬 3 GiB 影片驗證大位址 seek，沒有實際配置 3 GiB 記憶體。這些測試不代表實體裝置已完成硬體解碼、音訊輸出或網路效能驗收。

測試片 `app/src/test/resources/vault-sample.mp4` 由 FFmpeg 產生，命令為 `ffmpeg -f lavfi -i testsrc2=size=320x180:rate=12 -f lavfi -i sine=frequency=440:sample_rate=44100 -t 2 -c:v libx264 -pix_fmt yuv420p -c:a aac -movflags +faststart vault-sample.mp4`。FFmpeg 工具留在本機 `.tools/`，不包含在 APK 或原始碼 ZIP 中。

Android lint 的 0 error 為交付條件。保留的 warning 主要是可升級的依賴、刻意使用 Android 16 targetSdk、KTX 寫法建議，以及背景執行中刻意使用同步偏好設定寫入。另有原圖方形桌面圖標的形狀建議（IconLauncherShape）；保留使用者提供的原圖。沒有用 baseline 隱藏錯誤。

另外以 `python scripts/check-translations.py` 驗證三份語系資源各有 311 項文字，格式參數一致；未新增語言下載或翻譯服務。

## 畫面檢查

以下是實際 Compose 介面經 Robolectric 原生 Canvas 渲染的畫面。時間軸使用測試產生的漸層圖片，**不是使用者伺服器的照片，也不是實體手機截圖**。

- [英文設定](screenshots/locale-en-settings.png)
- [簡體中文設定](screenshots/locale-zh-cn-settings.png)
- [法文系統的英文回退](screenshots/locale-fr-fallback-transfers.png)
- [登入](screenshots/login.png)
- [淺色時間軸](screenshots/timeline-light.png)
- [深色時間軸](screenshots/timeline-dark.png)
- [設定](screenshots/settings-light.png)
- [指紋入口與首次設定提示](screenshots/vault-fingerprint-entry.png)
- [解密照片（模擬伺服器與合成圖片）](screenshots/vault-unlocked.png)
- [傳輸清空歷史後（模擬記錄）](screenshots/transfers-history.png)
- [裁剪與旋轉元件（測試圖片）](screenshots/editor-crop-rotation.png)

已查看標題、分頁、日期分組、圖塊、狀態標示與明暗配色。完整大型字體、不同手機尺寸及手勢效能仍列於實機驗收。

## 尚待真實環境驗收

尚未取得 Nextcloud 34.0.3 測試伺服器網址與登入授權，也沒有已連接的 Android 手機。本機 Android 16 模擬器缺少硬體加速驅動，嘗試軟體模式後未取得可用 adb 裝置。因此尚未宣稱通過 APK 實機安裝或真實伺服器端到端測試。

請用獨立測試資料夾完成以下驗收：

1. 瀏覽器授權登入，確認實際 Photos 版本、子路徑網址與帳號 UID 能正常存取。
2. App 與 Photos 網頁端雙向新增／改名相簿、加入／移出成員及收藏，確認原檔未被移除。
3. 以 JPEG、HEIC、PNG、GIF、短影片及大影片驗證縮圖、原圖、影片播放和離線播放。
4. 選擇／取消／撤回系統資料夾授權，測試 Wi-Fi 切換、鎖屏、省電、App 重啟及裝置重開後的備份排程。
5. 以測試檔案進行斷網、同名、權限不足、伺服器配額不足及來源檔改變測試，核對檔案內容與佇列結果。
6. 編輯測試照片，確認副本解析度、裁切、旋轉、亮度與原始照片保留；依裝置記憶體確認大型原圖的提示。
7. 建立／撤銷測試分享連結，確認密碼、有效期限及伺服器分享政策。
8. 在 Android 16 實機驗證指紋啟用／成功／失敗／取消、新增指紋失效、移除螢幕鎖定後的密碼備援；解密照片顯示中測試 Home、最近使用、鎖屏、轉向與程序終止，確認重新鎖定及無明文磁碟檔案。
9. 使用測試帳號確認加密照片／縮圖在網頁端只有密文，第二台裝置用密碼可復原；測試加密上傳中斷後刷新、32 MiB 上限及匯入來源原檔仍保留。
10. 加密影片在實機測試 MP4／MOV／WebM、音訊、暫停與 seek、長片及慢網路、返回／Home／鎖屏後停止聲音；確認只有密文寫入伺服器，舊版照片仍可解鎖。
11. 以大型真實照片庫、不同螢幕和放大字體檢查滑動、搜尋、縮放、排版與儲存空間使用。

## 安全與交付

`NextPhoto-v0.1.7.apk` 為 Release 正式簽名 APK，使用專案私有金鑰簽名並經 R8 縮減。已檢查套件名稱 `app.nextcloudphoto`、版本 0.1.7（versionCode 8）、桌面名稱 NextPhoto、圖標資源及非 debuggable 組態，簽名憑證與舊版一致。原始碼包不包含 `.signing/`、任何登入憑證、SDK、快取或上傳暫存。

交付包含 APK、原始碼 ZIP 和 SHA-256 檢查碼。完整機器產生的測試與 lint 報告位於 `app/build/reports/`。
