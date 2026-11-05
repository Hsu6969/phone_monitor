# 相機監看 (Phone Monitor)

把 Android 手機當成相機的外接監看螢幕：用 USB-C 連接後，手機全螢幕即時顯示相機的畫面。

## 第一步：即時同步相機畫面（已完成）

### 重要：兩種接法，看到的畫面不一樣

| 接法 | 手機看得到 | 需要的東西 |
|---|---|---|
| **A. 相機 HDMI → HDMI 擷取卡 → 手機 USB-C**（推薦） | **相機螢幕上的一切**：拍攝畫面、回放照片、選單、設定 | HDMI 擷取卡（UVC，常見晶片 MS2109 / MS2130，約 NT$300–800）＋ 相機對應的 HDMI 線 |
| B. 相機 USB-C ↔ 手機 USB-C（相機設成「網路攝影機 / UVC / USB 串流」模式） | 只有即時取景（乾淨畫面），**通常沒有選單和回放** | 相機需支援 UVC 網路攝影機模式 |

為什麼直接用 USB 線看不到選單？相機的 USB 埠一般跑的是 PTP/MTP（傳照片）或 UVC（網路攝影機）協定：
PTP 不輸出影像；UVC 模式只送感光元件的取景畫面。**相機的完整螢幕（含選單、回放）只會從 HDMI 輸出**，
所以要達成「任何時候的畫面都同步」，要走接法 A。

App 會自動判斷：
- 插的是 UVC 裝置（擷取卡或網路攝影機模式的相機）→ 直接顯示畫面
- 插的是 PTP/MTP 模式的相機 → 顯示提示，告訴你要改 USB 模式或改用 HDMI 擷取卡

### 使用方式

1. 安裝 APK（見下方「下載 / 編譯」）
2. 接法 A：相機 HDMI 輸出 → 擷取卡 → 手機（擷取卡若是 USB-A 頭，需要 USB-C OTG 轉接頭）
3. 打開 App（或插上時系統會詢問要不要用「相機監看」開啟）→ 允許 USB 權限
4. 畫面出現後：
   - 點一下畫面：顯示 / 隱藏上方工具列（裝置名稱、解析度、實際 fps）
   - 「解析度」：切換擷取解析度 / 格式（每台裝置會記住你的選擇）
   - 「完整顯示 / 填滿畫面」：切換黑邊或裁切填滿

相機端小提醒：
- Sony / Canon / Nikon / Fuji 等相機在 HDMI 輸出時，可在選單中開啟「HDMI 資訊顯示」，才會把對焦框、參數等疊加資訊一起輸出
- 部分相機接 HDMI 時機身螢幕會關閉，這是相機行為

### 技術說明

- 不依賴任何第三方 UVC 函式庫：
  - `app/src/main/java/.../uvc/UvcDescriptors.kt`：解析 USB 描述元，找出影像格式、解析度、幀率、傳輸端點
  - `app/src/main/java/.../uvc/UvcCamera.kt`：UVC PROBE/COMMIT 協商、選擇 alternate setting
  - `app/src/main/cpp/uvc_stream.c`：透過 Linux usbfs 進行 isochronous / bulk 傳輸（Android Java API 不支援 isochronous）、組合 UVC payload 成完整影格
  - `app/src/main/java/.../FrameRenderer.kt`：解碼並繪製到 SurfaceView，解碼跟不上時自動丟舊影格，確保低延遲
- 支援格式：MJPEG（自動補上缺少的 Huffman 表）、YUY2、NV12
- 支援 USB 2.0 高頻寬 isochronous、USB 3.x SuperSpeed、bulk 傳輸的裝置
- 最低 Android 8.0（API 26），手機需支援 USB Host / OTG

## 下載 / 編譯

- **直接下載**：GitHub → Actions → 最新一次「Android build」→ Artifacts → `phone-monitor-debug-apk`
- **自行編譯**：Android Studio 開啟本專案，或
  ```bash
  ./gradlew assembleDebug
  ```
  APK 在 `app/build/outputs/apk/debug/`

## iPad 版（`ios/PhoneMonitor.swiftpm`）

- 只支援 **USB-C 接口的 iPad**，iPadOS 17 以上（iPhone 系統不支援外接 USB 影像裝置）
- 用 iPadOS 內建的外接相機支援（AVFoundation），不需要任何 USB 底層程式
- 是 Swift Playgrounds 專案：**不需要 Mac、不需要付費開發者帳號**
- 安裝：
  1. App Store 安裝免費的「Swift Playgrounds」
  2. 在 iPad Safari 打開 https://github.com/Hsu6969/phone_monitor/releases/download/ipad-latest/PhoneMonitor.swiftpm.zip
  3. 「檔案」App →「下載項目」→ 點 zip 解壓縮 → 點 `PhoneMonitor.swiftpm`（會用 Swift Playgrounds 開啟）
  4. 按「執行」(▶)，允許相機權限；之後插上相機 / 擷取卡就會自動顯示

## 之後的功能（待定）

例如：截圖 / 錄影、峰值對焦 (focus peaking)、斑馬紋、輔助線、放大檢視、畫面翻轉、LUT 預覽、透過 PTP 遙控拍攝等。
