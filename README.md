# tokaWorlds

**tokaWorlds 1.1.2** 是 Nukkit 世界管理插件，提供世界掛載／卸載與批次更名，更名時可連動 zero 與 tokaPortal 的世界欄位。管理指令只有 `/worlds`，保留核心原本的 `/world`。

## 安裝

- 需要 Java 21；已驗證 Nukkit PM1E 4511。
- 將 `tokaWorlds-1.1.2.jar` 放入 `plugins/`，完整重啟（不使用 reload）。
- 選用相依（softdepend）：zero、tokaPortal（更名連動）。
- 使用共用套件 tokaStorage 與 tokaI18n，皆已內嵌於 JAR。

## 指令與權限

管理員輸入 `/worlds` 開啟視窗，或使用後台指令（`/worlds help` 查看全部子指令）：

| 指令 | 用途 |
| --- | --- |
| `worlds list` | 列出世界 |
| `worlds rename <A> <B> [--link｜--no-link]` | 加入更名待辦；`--no-link` 只改地圖資料夾 |
| `worlds mount <A>`、`worlds unmount <A>` | 加入掛載／卸載待辦（卸載不刪地圖） |
| `worlds plan`、`worlds check` | 查看、檢查待辦 |
| `worlds confirm [秒數]` | 確認並延遲執行（0–3600 秒） |
| `worlds clear`、`worlds cancel` | 清空待辦／工作開始前取消排程 |
| `worlds return`、`worlds resend` | 玩家返回原位置／重送附近方塊 |

| 權限 | 說明 | 預設 |
| --- | --- | --- |
| `tokaWorlds.use` | 使用自己的 `return` | 所有人 |
| `tokaWorlds.admin` | 世界管理 | OP |

要點：每位管理員有自己的待辦；目標世界已存在時不覆蓋；預設世界不能在線卸載或更名；失敗時只還原當下項目並停止。工作開始時才集中移出受影響世界的玩家，完成後依新名稱返回。重啟後待辦需重新檢查與確認。

## 設定（`config.yml`）

| key | 預設 | 說明 |
| --- | --- | --- |
| `language` | `zh-TW` | 語言 |
| `reserved-names` | help、list、set、del、edit | 不可作為世界名稱的字 |
| `link-on-rename` | `true` | 更名時是否預設連動其他插件資料 |
| `storage.backend` | `sqlite` | `sqlite`、`json`、`yaml`；更換需停服並用 StorageCli 遷移 |
| `evacuation-world` | `""` | 移出玩家的安全世界；空白用預設世界 |
| `legacy-items` | 903、879 兩筆規則 | 舊物品編號轉換（`to`、`per-item`） |

資料放在 `plugins/tokaWorlds/storage/<格式>/`；`link-backups/` 為住家引用備份，並非整張世界的備份。

## 版本紀錄

- 1.1.2：子表單關閉後回到選單，選單新增關閉按鈕。
- 1.1.0：所有文字移入 `lang/zh-TW.yml`（tokaI18n）；新增保留世界名稱（`reserved-names`）。
- 1.0.8：後台記錄世界數與舊物品規則數。
- 1.0.7：`/worlds resend` 重送附近樓梯、柵欄等方塊。
- 1.0.5：轉換目前核心不認得的舊物品編號。

授權：MIT。
