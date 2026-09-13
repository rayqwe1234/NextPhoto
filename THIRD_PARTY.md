# 第三方元件與協定參考

本專案以原創 Kotlin 程式碼實作，不包含 Nextcloud Photos 的 PHP 或 TypeScript 程式碼。

協定來源：

- [Nextcloud Login Flow v2](https://docs.nextcloud.com/server/stable/developer_manual/client_apis/LoginFlow/index.html)
- [Nextcloud WebDAV](https://docs.nextcloud.com/server/stable/developer_manual/client_apis/WebDAV/basic.html)
- [Nextcloud 分塊上傳](https://docs.nextcloud.com/server/stable/developer_manual/client_apis/WebDAV/chunking.html)
- [Photos 相簿集合操作](https://github.com/nextcloud/photos/blob/master/src/store/collections.ts)
- [OCS 分享 API](https://docs.nextcloud.com/server/stable/developer_manual/client_apis/OCS/ocs-share-api.html)

執行階段使用 Kotlin、AndroidX（Compose、Room、Paging、WorkManager、Media3、ExifInterface、DocumentFile）、OkHttp、Okio、Coil，主要採 Apache License 2.0。測試使用 JUnit（EPL 1.0）、Robolectric（MIT）、MockWebServer 和 AndroidX Test。完整版本與傳遞依賴可透過 `gradlew :app:dependencies` 檢視；各元件版權及授權依其發佈內容為準。

Nextcloud 名稱及商標屬其權利人所有，本 App 沒有聲稱獲得官方背書。
