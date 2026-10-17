# tokaWorlds

世界管理插件：不需要重寫設定檔或手動改資料夾，就能掛載、卸載世界，或批次更名，並同步更新 zero 與 tokaPortal 中記錄的世界名稱。

## 主要功能

- **掛載／卸載世界**：把地圖資料夾裡的世界載入或卸下，卸載不會刪除地圖。
- **批次更名**：一次排好多個世界的更名，一起執行；更名時可連動 zero 的世界欄位與 tokaPortal 的住家，不必逐一手改。
- **待辦清單**：所有操作先加入自己的待辦，檢查通過後再排程執行（可延遲 0–3600 秒），避免誤操作。
- **玩家安全**：工作開始時，受影響世界內的玩家會先被移到安全世界，完成後自動回到原本座標；目標世界已存在時不會被覆蓋。
- **舊物品轉換**：玩家上線時，把舊核心留下、現在已無法辨識的物品換成指定的新物品，並留下紀錄。

## 玩家指令

| 指令 | 用法 | 說明 |
| --- | --- | --- |
| `/worlds return` | `/worlds return` | 世界維護後若尚未回到原位置，手動回去 |

## 管理指令

在遊戲內輸入 `/worlds` 開啟管理視窗，或在後台使用下列指令（`/worlds help` 查看說明）。

| 指令 | 用法 | 說明 | 權限 |
| --- | --- | --- | --- |
| `list` | `/worlds list` | 列出世界與掛載狀態、線上人數 | `tokaWorlds.admin` |
| `mount` | `/worlds mount <世界>` | 加入待辦：掛載 | `tokaWorlds.admin` |
| `unmount` | `/worlds unmount <世界>` | 加入待辦：卸載（不刪除地圖） | `tokaWorlds.admin` |
| `rename` | `/worlds rename <A> <B> [--link｜--no-link]` | 加入待辦：更名；`--link` 連動 zero／tokaPortal，`--no-link` 只改地圖資料夾 | `tokaWorlds.admin` |
| `plan`、`check` | `/worlds plan`、`/worlds check` | 查看待辦／檢查全部待辦（排程前必須先檢查） | `tokaWorlds.admin` |
| `confirm` | `/worlds confirm [秒數]` | 排程執行，延遲 0–3600 秒，預設 0 | `tokaWorlds.admin` |
| `clear`、`cancel` | `/worlds clear`、`/worlds cancel` | 清空自己的待辦／工作開始前取消排程 | `tokaWorlds.admin` |
| `resend` | `/worlds resend [半徑]` | 重送周圍的樓梯、柵欄、玻璃片、牆、門等方塊（半徑 1–48，預設 16，只影響自己的畫面） | `tokaWorlds.admin` |
| `return` | `/worlds return` | 回到維護前的位置 | 所有人 |

| 權限 | 說明 | 預設 |
| --- | --- | --- |
| `tokaWorlds.use` | 使用 `/worlds return` | 所有人 |
| `tokaWorlds.admin` | 世界管理 | OP |

（圖：遊戲內 `/worlds` 管理視窗）

建議流程：`rename A B` → `plan` → `check` → `confirm 30`。每位管理員有各自的待辦；伺服器預設世界不能在線卸載或更名；執行中途失敗會停止，已完成的項目保留；重新啟動後待辦需重新檢查與確認。

## 設定（`config.yml`，變更後需重新啟動）

| key | 預設 | 說明 |
| --- | --- | --- |
| `language` | `zh-TW` | 訊息語言 |
| `reserved-names` | help、list、set、del、edit | 不可作為世界名稱的字 |
| `link-on-rename` | `true` | 更名時是否預設連動 zero 與 tokaPortal 的資料 |
| `evacuation-world` | 空白 | 卸載或更名時，玩家被移往的世界；空白使用預設世界 |
| `legacy-items` | 903、879 兩筆 | 舊物品編號轉換規則，`to` 為新物品（留空或 `remove` 代表移除），`per-item` 為每個舊物換幾個 |

物品轉換紀錄寫在 `plugins/tokaWorlds/legacy-items.log`，可依此手動還原。

## 疑難排解

- **為什麼 `confirm` 說尚未檢查？** 請先執行 `/worlds check`，待辦有變動後需重新檢查。
- **更名後住家的世界名稱沒變？** 確認該次更名使用 `--link`，或 `link-on-rename` 為 `true`。
- **玩家沒有被送回原位？** 請玩家輸入 `/worlds return`。

目前版本 1.1.4
