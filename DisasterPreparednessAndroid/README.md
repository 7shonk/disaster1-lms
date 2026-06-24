# 📱 智慧防災安檢鏡頭 - Android 系統模組

本目錄包含「智慧防災學習平台 LMS」智慧安檢鏡頭的 **Android 原生系統應用程式**（採用 Kotlin + Jetpack Compose + CameraX 進行開發）。

## 🌟 核心功能特色
1. **O2O 網頁喚醒 (Deep Link)**：適配 `disasterlms://scan?user_id=...&name=...&mode=...`。在網頁端各教室點擊「啟動手機 AI 鏡頭」時，可一鍵自動開啟此 Android 應用，並自動帶入學生身分與教室主題（如防火、防震等）。
2. **CameraX 高質感相機**：自動進行解析度限制與 Exif 旋轉校正，確保上網辨識速度流暢。
3. **Gemini 2.5 Flash 直連分析**：不經過任何第三方中轉，直接將影像及對應模式的 `systemInstruction` 發送給 Google Gemini 2.5 Flash 進行視覺健檢，生成缺失清單與專家建議。
4. **Supabase 數據直寫**：直接將安檢報告（整備分數、反饋建議、缺失與隱患）寫入專案的 Supabase 中，自動在 `missing_items` 首位插入 `mode` 標籤，讓網頁教室的輪詢檢測引擎可以在 2 秒內同步更新 UI。
5. **完全解耦 Firebase**：本 Android 應用完全不使用 Firebase，可 100% 獨立在本機環境中運行。

---

## 🛠️ 開發與編譯指南

### 1. 系統需求
*   **Android Studio** (建議版本 Iguana 或更高)
*   **JDK 17** (Gradle 8.2 的相容版本)
*   **Android SDK 34** (Compile SDK)
*   **Android 實體手機** (需開啟開發者模式與 USB 偵錯) 或 **帶有相機模擬的 AVD 模擬器**

### 2. 用 Android Studio 開啟專案
1. 開啟 Android Studio。
2. 點擊 **File -> Open**，選取此 `DisasterPreparednessAndroid` 資料夾。
3. 等待 Gradle 同步完成。

### 3. 編譯與安裝
1. 將 Android 手機連線至電腦。
2. 在上方工具列選取您的裝置，點擊 **Run 'app'** 綠色播放鍵。
3. App 安裝完成後，首次啟動會請求**相機權限**，請點選「允許」。

---

## 🧪 連動測試方法
1. 在電腦上啟動本地 Python 後端 `python sync_backend.py`。
2. 用電腦瀏覽器打開任何一間教室（例如 [earthquake.html](file:///../earthquake.html)）。
3. 登入學生帳號，點擊 **「📱 啟動手機 AI 防震固定安檢鏡頭」** 按鈕。
4. 在手機瀏覽器中，這將會觸發 Scheme 跳轉並直接喚醒您安裝的 Android 應用。
5. 在 App 中進行拍攝、取得 AI 健檢書，點擊 **「將資料同步至提案健檢書」**。
6. 觀察電腦瀏覽器，網頁教室的「AI 智慧安檢結果區」將會在 2 秒內同步顯影您的健檢分數與專家反饋！
