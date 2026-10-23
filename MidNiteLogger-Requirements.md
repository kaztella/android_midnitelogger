# MidNite Logger 要求仕様書

- 文書版数: 1.1
- 作成日: 2026-10-23
- 対象アプリ: MidNite Logger
- 現行アプリ版数: 0.7.2
- 主対象端末: AQUOS PHONE SERIE mini SHL24
- 移行予定端末: AQUOS sense SHV40

## 1. 目的

MidNite Solar Classic 150からModbus TCPで発電・充電関連データを定期取得し、Android端末内のSQLiteへ欠損に強い形で保存する。

外部インターネットへ接続できるときだけ、保存済みデータをSupabaseへ安全にアップロードし、将来的にWebダッシュボードから閲覧・分析できるようにする。

本システムでは、リアルタイ��性よりも次を優先する。

1. 測定データを失わないこと
2. Classicとのローカル通信とSQLite保存を止めないこと
3. クラウド障害やサービス変更があっても端末内に原本を残すこと
4. 古いAndroid端末でも安定して動作すること
5. 将来、別端末・別クラウドへ移行できること

---

## 2. 運用計画

### 2.1 使用端末

| 期間 | 本命端末 | 方針 |
|---|---|---|
| 2026年中 | SHL24 | 現行ロガーとして継続運用 |
| 2027年初以降 | SHV40 | SHL24から段階的に移行 |

### 2.2 端末の役割

```text
MidNite Classic 150
        ↓ Modbus TCP / Wi-Fi
Androidロガー（SHL24、将来SHV40）
        ↓
SQLite（測定データの原本）
        ↓ 外部インターネット接続時のみ
Supabase
        ↓
Webダッシュボード
```

### 2.3 原本管理

- SQLiteを測定データの原本とする。
- Supabaseは外部閲覧・集計用のコピーとする。
- Supabaseが停止・終了しても、SQLiteから再構築できること。
- クラウドへの送信失敗によってローカルロギングを停止しないこと。

---

## 3. 対象環境

### 3.1 Android

- 最低対応: Android 4.2.2、API 17相当のSHL24
- `minSdk`: 14を維持可能な実装を優先
- 新しいAPIだけに依存しないこと
- ラムダ式より匿名クラスを優先し、旧Android環境でのビルド・動作互換性を重視すること
- Foreground Serviceを使用し、長時間ロギングを継続すること

### 3.2 Classic通信

- 接続方式: Modbus TCP
- 既定ポート: 502
- Classicの既定IP候補: `192.168.10.1`
- 通常ロギングでは読取り専用を原則とする。
- 通常ロギングで不用意なModbus書込みを行わないこと。

---

## 4. 基本機能要求

## 4.1 Modbusロギング

- 60秒周期でClassicへ接続する。
- 測定周期ごとにClassicから必要レジスタを読み取る。
- Modbus通信成功後、Android端末のRTC時刻を測定時刻として付加する。
- Modbus応答異常、Socket例外、タイムアウトをeventログへ記録する。
- エラーが発生しても次周期で再試行する。
- 1周期内の通信は可能な限り1接続にまとめる方針とする。

## 4.2 測定時刻

- `measured_at`はAndroidロガー端末のRTCを元にする。
- Classic内部RTCは現在の測定時刻には使用しない。
- Supabase送信時は日本時間のオフセット付きISO 8601形式を使用する。

例:

```text
2026-10-22T08:15:00+09:00
```

- Supabaseの`measured_at`は`timestamptz`とし、内部では絶対時刻として扱う。
- Supabase Table EditorではUTC表示になる場合がある。
- `created_at`はSupabaseへ登録された時刻であり、測定時刻とは区別する。
- ダッシュボードの横軸は必ず`measured_at`を使用する。

### 将来拡張

- Classic RTCを診断用に別途取得する。
- Android RTCとClassic RTCの時刻差を表示・記録する。
- Classic RTCは測定時刻の主基準にはしない。

---

## 5. SQLite要求

## 5.1 測定データ

測定データは`log`テーブルへ保存する。

主要項目:

| SQLite列 | 内容 | 単位 |
|---|---|---|
| `ts` | Android端末で付与した測定時刻 | 日時 |
| `battery_v` | バッテリー電圧 | V |
| `pv_v` | PV入力電圧 | V |
| `classic_a` | Classicバッテリー電流 | A |
| `pv_a` | PV入力電流 | A |
| `watts` | PV電力 | W |
| `daily_kwh` | 本日発電量 | kWh |
| `stage`または`charge_stage` | 充電ステージコード | 数値 |
| `cstate`または`classic_state` | Classic状態コード | 数値 |
| `daily_ah` | 本日Ah | Ah |
| `lifetime_kwh` | 累積発電量 | kWh相当 |
| `lifetime_ah` | 累積Ah | Ah |
| `batt_temp` | バッテリー温度 | ℃ |
| `fet_temp` | FET温度 | ℃ |
| `pcb_temp` | 基板温度 | ℃ |
| `float_sec`または`float_seconds` | 当日Float時間 | 秒 |
| `wb_pos_ah` | Whizbang Jr. 正方向Ah | Ah |
| `wb_neg_ah` | Whizbang Jr. 負方向Ah | Ah |
| `wb_net_ah` | Whizbang Jr. 正味Ah | Ah |
| `wb_a` | Whizbang Jr.電流 | A |
| `wb_precise_a` | Whizbang Jr.精密電流 | A |
| `uploaded` | クラウド送信済みフラグ | 0/1 |

このほかに取得可能な項目として、次を管理する。

- `last_voc_v`
- `highest_pv_v`
- `info_flags`
- `minutes_no_pv`
- `reset_reason`

## 5.2 重複防止

- SQLiteの測定データは時刻を基本キーとして重複を防ぐ。
- バックアップDB統合時は`INSERT OR IGNORE`相当を使用する。
- 同一時刻データを複数登録しないこと。

## 5.3 未送信管理

- 新規測定データは`uploaded=0`で保存する。
- Supabaseから正常応答を得たバッチだけ`uploaded=1`へ更新する。
- 通信失敗時は`uploaded=0`のまま残し、次回再送する。

---

## 6. eventログ要求

## 6.1 ログ種別

想定するレベル:

- `INFO`
- `OK`
- `SEND`
- `RECV`
- `DATA`
- `ERROR`

## 6.2 保存期間

- 通常eventログは7日経過後に自動削除する。
- `[ERROR]`ログは期間制限なく永続保存する。
- 1か月以上経過後でもエラー原因を追跡できること。

削除条件の考え方:

```sql
DELETE FROM event
WHERE level <> 'ERROR'
  AND ts < datetime('now', '-7 days');
```

## 6.3 詳細通信ログ

- トラブル解析では送受信HEXログを利用できること。
- 将来的に詳細HEXログのON/OFF設定を追加する。
- 通常運用時は容量増加を抑えるためOFFを基本とする候補とする。

---

## 7. データ容量・状態表示

画面に次を表示する。

- 測定データ件数
- SQLite DBファイル使用容量
- `[ERROR]`件数
- 端末の空き容量
- 最古測定日時
- 最新測定日時

表示例:

```text
測定データ: 12,843件
DB使用容量: 5.8 MB
ERROR履歴: 3件
端末空き容量: 4.3 GB
最古記録: 2026/10/21 10:42
最新記録: 2026/10/30 08:15
```

### 容量方針

- 1分周期では年間525,600件となる。
- 測定データ本体より、詳細eventログやHEXログの方が容量増加要因となり得る。
- ERROR永続保存と通常ログ7日削除を組み合わせ、長期運用時の肥大化を防ぐ。

---

## 8. グラフ表示要求

## 8.1 横軸

- 横軸はデータ点番号ではなく、明示的な時刻軸とする。
- データ欠損区間が長い場合、実時間に比例した空白として表示する。
- データ点間隔を常に一定幅で描画しない。

## 8.2 表示項目切替

1枚のグラフをプルダウンで切り替える。

対象項目:

- バッテリー電圧 `[V]`
- PV電圧 `[V]`
- Classicバッテリー電流 `[A]`
- Whizbang Jr.電流 `[A]`
- Whizbang Jr.精密電流 `[A]`
- PV電流 `[A]`
- PV電力 `[W]`
- 本日発電量 `[kWh]`
- バッテリー温度 `[℃]`
- FET温度 `[℃]`
- 基板温度 `[℃]`

## 8.3 表示期間

- 直近24時間
- 選択日時から24時間
- 前日・翌日移動
- 将来候補: 7日、30日、期間指定

## 8.4 描画負荷

- SHL24の性能を考慮し、描画点数を最大240点程度へ間引けること。
- 間引き後も横軸は時刻を保持すること。

---

## 9. 充電ステータス表示

数値コードだけではなく、名称と簡単な説明を表示する。

例:

| 表示名称 | 説明 |
|---|---|
| Resting | 入力電力不足などにより充電を休止している状態 |
| Bulk MPPT | Absorb設定電圧へ向け、利用可能な電力を最大限使用して充電する状態 |
| Absorb | Absorb設定電圧を維持する定電圧充電状態 |
| Float | 満充電後、Float設定電圧を維持する状態 |
| Float MPPT | Float設定電圧へ戻すために最大電力で充電する状態 |
| Equalize | 均等充電状態 |
| EQ MPPT | Equalize設定電圧へ向けて充電する状態 |

### 注意

- `charge_stage`と`classic_state`は別の値として扱う。
- 数値コードから名称への対応は、MidNite公式レジスタ仕様と実機値を照合して確定する。
- 未確認コードは推測せず、`コード n`として表示する。

---

## 10. Supabase要求

## 10.1 位置づけ

- Supabaseは閲覧・集計用クラウドDBとする。
- SQLiteを原本とする。
- M2Xのような閉じたIoTサービスより、PostgreSQLとREST APIによる自由度を重視する。
- 将来、Grafana、Power BI、Excel、Python、独自Web画面等へ接続できる構成とする。

## 10.2 テーブル

テーブル名:

```text
measurements
```

主要列:

```text
id
client/device_id
measured_at
battery_v
pv_v
classic_a
pv_a
watts
daily_kwh
charge_stage
classic_state
battery_temp
fet_temp
pcb_temp
wb_a
wb_precise_a
last_voc_v
highest_pv_v
daily_ah
lifetime_kwh
lifetime_ah
info_flags
minutes_no_pv
float_seconds
reset_reason
wb_positive_ah
wb_negative_ah
wb_net_ah
created_at
```

実テーブルのデバイス識別列は`device_id`を使用する。

## 10.3 一意制約

```sql
unique(device_id, measured_at)
```

- 同一端末の同一測定時刻を重複登録しない。
- 再送可能な冪等アップロードとする。

## 10.4 時刻列

- `measured_at`: Android端末で記録した測定時刻
- `created_at`: Supabaseへ行が作成された時刻
- 両列とも`timestamptz`
- ダッシュボードは`measured_at`を使用する。

## 10.5 APIキー

Galaxy、SHL24、SHV40では次を使用する。

```text
Publishable key
sb_publishable_...
```

使用禁止:

```text
Secret key
service_role key
```

- Secret keyはRLSを迂回できるためAPKやAndroid端末へ保存しない。
- URLとPublishable keyは同一Supabaseプロジェクトの組み合わせを使用する。
- 入力値の前後空白、改行を除去する。
- URL入力欄は保存前に`trim()`する。
- APIキーは保存前にCR/LFを除去する。

## 10.6 RPC

送信先:

```text
https://<project-ref>.supabase.co/rest/v1/rpc/insert_measurements
```

PCおよびGalaxyで実績のあるプロジェクトURL:

```text
https://kzpjdbaocfatltvxmzks.supabase.co/rest/v1/rpc/insert_measurements
```

RPC関数:

```text
insert_measurements(p_rows jsonb)
```

動作:

- JSON配列を受け取る。
- `ON CONFLICT (device_id, measured_at) DO NOTHING`を実行する。
- 既存データは上書きせず無視する。
- 新規追加件数を返す。

## 10.7 RLS・権限

Androidアプリ用`anon`権限:

| 操作 | 権限 |
|---|---|
| RPC実行 | 許可 |
| 直接INSERT | 必要に応じて限定 |
| SELECT | 原則禁止または別用途で限定 |
| UPDATE | 禁止 |
| DELETE | 禁止 |

- RLSを有効にする。
- アプリには必要最小限の権限だけ与える。

## 10.8 バッチ送信

- 1バッチ最大100件を基本とする。
- 1回の手動アップロード処理は最大30秒を基本とする。
- 30秒内で複数バッチ送信できること。
- HTTP 2xx受信後に対象SQLiteレコードを`uploaded=1`へ変更する。
- HTTP 401はAPIキー不正として記録する。
- HTTP 404/PGRST202はRPC URLまたは関数名・引数名不一致として記録する。
- 応答不明時は未送信扱いのまま再送する。
- 重複再送はSupabase側で無視されること。

## 10.9 NULL列対応

現時点では、一部のSQLite列がアップロードJSONへ未追加のためSupabase側でNULLになる可能性がある。

今後の必須修正:

- SQLiteへ保存している測定項目を原則すべてJSONへ含める。
- `uploaded`、SQLite内部`id`、eventログは送信しない。
- 全列送信後に残るNULLだけを、Classic側未対応または取得不能として評価する。

---

## 11. Wi-Fi・アップロード運用

- Classic側Wi-Fi接続中はModbusロギングを優先する。
- 外部Wi-Fiまたはインターネット接続時にだけSupabaseへ送信する。
- クラウド送信のために長時間Classic通信を止めない。
- 外部Wi-Fi接続後の送信は短時間で完了させる。
- 送信後はClassic側ネットワークへ戻し、Modbus通信確認後に通常ロギングへ復帰する設計を目標とする。
- 新しいAndroidではWi-Fi自動切替制限があるため、端末世代ごとに挙動を分ける。

---

## 12. バックアップ・復元・統合

## 12.1 バックアップ

- SQLite DBを外部ストレージへバックアップできること。
- 保存先候補:

```text
/storage/emulated/0/MidniteLogger/
```

- ファイル名に保存日時を含める。

例:

```text
midnite_backup_20261023_083000.db
```

- Android世代ごとのストレージ権限・Scoped Storage差を考慮する。
- SHL24とGalaxyで同じ固定パスが使用できるとは限らない。

## 12.2 統合

- バックアップDBから測定データを現在DBへ統合できること。
- 重複データを作らないこと。
- 原則として測定データだけを統合し、eventログは統合しない。
- 元DBを壊さないこと。
- 統合件数を表示すること。

## 12.3 Google Drive

候補用途:

- SQLite DBバックアップの保管先
- CSVまたはDBファイルの保管

位置づけ:

- Google Driveはデータベースではなくバックアップ置き場として利用する。
- 長期的な時系列検索・ダッシュボードはSupabase側で行う。

---

## 13. Webダッシュボード要求

将来実装する。

### 13.1 基本表示

- 最終測定時刻
- Battery Voltage
- PV Voltage
- PV Power
- Whizbang Jr. Current
- Charge Stage名称
- 本日発電量
- 端末・デバイス選択

### 13.2 グラフ

- 24時間
- 7日
- 30日
- 任意期間
- 項目切替
- `measured_at`を横軸にする。

### 13.3 集計

- 日別発電量
- 最大PV電力
- 最低・最高バッテリー電圧
- 充電ステージ滞在時間
- アップロード遅延
- データ欠損区間

### 13.4 アクセス

- PC、iPhone、Galaxyのブラウザから閲覧できること。
- 専用アプリを必須にしない。

---

## 14. Classic内部Daily Log取込

## 14.1 背景

Classic表示パネルでは、約1年分のDaily Logを確認でき、古いものから上書きされる。

Classic内部ログは、通常の現在値レジスタ列ではなくEEPROM内のログ領域として保持され、Modbusの専用ログ読出し機構から取得できる可能性がある。

## 14.2 確認済み構造

Daily Logには少なくとも次のカテゴリがある。

| Category | 内容 |
|---:|---|
| 0 | kWh |
| 1 | Ah |
| 2 | Float Time |
| 3 | Time Stamp Low |
| 4 | Time Stamp High |
| 5 | High Power `[W]` |
| 6 | High Temperature |

Daily Log要求値の概念:

```text
Category = (DayLogCombCatIndex & 0xFC00) >> 10
Index    =  DayLogCombCatIndex & 0x03FF
```

- Indexは最新から何レコード前かを表す用途と考える。
- Timestamp Low/HighからClassic側の日付・時刻を復元する。

## 14.3 読出し方式候補

### 方式A: 1項目ずつ

- Daily LogのCategoryとIndexを指定する。
- 指定した値をログ読出しレジスタから取得する。
- 実装と検証は分かりやすいが、多数回アクセスが必要。

### 方式B: Modbus File Transfer

- `104 Read Internal Command`を利用する。
- `modbus_file_dailies_log`を読む。
- 32データポイント単位の取得が可能とされる。
- 約365日なら十数ブロックで取得できる可能性がある。

## 14.4 安全要求

- 通常ロギングは今後もFC03読取り専用を原則とする。
- Daily Log取込機能は通常ロガーの通信コードから分離する。
- 書込みを必要とする方式では、許可アドレスを限定し、他レジスタへ書き込めない設計にする。
- 可能であれば、File Transferの読取り専用コマンドを優先する。
- 実機MNGP表示と1日分の取得結果を比較してから全履歴取得へ進む。

## 14.5 保存先

現在の1分測定テーブルとは分ける。

Supabase候補テーブル:

```text
daily_history
```

候補列:

```text
device_id
log_date
daily_kwh
daily_ah
float_time_seconds
high_power_w
high_temperature_c
classic_timestamp
imported_at
source
```

一意制約候補:

```sql
unique(device_id, log_date)
```

---

## 15. セキュリティ要求

- Database PasswordをAPKへ保存しない。
- Secret key、service_role keyをAPKへ保存しない。
- Publishable keyだけを使用する。
- Publishable keyで許可される操作はRLSとGRANTで限定する。
- GitHubへSecret keyやDatabase Passwordをコミットしない。
- APIエラー本文にキーを出力しない。
- 画面上のAPIキーは将来的にマスク表示する。
- 診断表示では先頭文字と文字数だけ表示する候補とする。

---

## 16. ビルド・リポジトリ管理

- GitHub ActionsでAPKを自動ビルドする。
- Java 17と対応Gradle/Android Gradle Pluginを使用する。
- 旧Android互換のため、Javaコードでは匿名クラスを優先する。
- ZIP上書きでは、ZIPに存在しない旧ファイルはGitHubから自動削除されないことに注意する。

削除済み・削除対象となった旧ファイル:

```text
Uploader.java
DiagnosticLog.java
```

現行アップロード実装:

```text
SupabaseUploader.java
```

主要ソース候補:

```text
BootReceiver.java
ChartView.java
ClassicReader.java
Config.java
Database.java
LoggerService.java
MainActivity.java
Modbus.java
Record.java
SupabaseUploader.java
Time.java
Tls12.java
```

---

## 17. 実装済み・確認済み事項

### 17.1 アプリ側

- Modbus TCPによる現在値取得
- SQLite保存
- Foreground Service
- 60秒周期ロギング
- eventログ
- エラー記録
- 複数グラフ項目切替
- DB容量・件数・空き容量表示
- バックアップ・統合の基礎実装
- Supabase RPCアップロードの基礎実装

### 17.2 Supabase側

- `measurements`テーブル作成
- RLS有効化
- Publishable keyによるアクセス
- PCからREST POST成功
- 複数行バッチPOST成功
- `device_id + measured_at`重複時の競合確認
- `insert_measurements(p_rows jsonb)` RPC作成
- 重複無視と新規データだけの登録確認
- GalaxyからRPC POST成功

---

## 18. 残件一覧

### 優先度A

1. SQLiteに保存している全測定項目をSupabase JSONへ追加する。
2. Supabase側NULL列が意図どおり埋まることを確認する。
3. 充電ステータスコード対応を公式仕様・実機値で確定する。
4. GalaxyとSHL24で連続運転・連続アップロード試験を行う。
5. Wi-Fi復帰後にClassic Modbus通信の再確認を行う。
6. 既存SQLite DBから新DBスキーマへの安全な移行処理を確認する。

### 優先度B

1. 詳細HEXログON/OFF設定
2. APIキーのCR/LF除去とマスク表示
3. 最古・最新記録日時表示
4. 未送信件数表示
5. 最終アップロード時刻表示
6. アップロード件数・失敗件数表示
7. DBバックアップ先のAndroid世代別対応
8. Google Driveバックアップ
9. Webダッシュボード

### 優先度C

1. Classic RTC取得とAndroid RTCとの差分監視
2. Classic Daily Logの1日分テスト読出し
3. `modbus_file_dailies_log`のレコード構造確定
4. 約1年分Daily Log一括取込
5. `daily_history`テーブル作成とSupabase連携
6. Minute Log / Recent History最大380点の取込検討

---

## 19. 受入試験

### 19.1 ローカルロギング

- 24時間以上、60秒周期で継続記録できること。
- Wi-Fi断、Classic再起動、Socket例外後に自動回復すること。
- 測定間隔と時刻軸が正しいこと。

### 19.2 eventログ

- 通常ログが7日後に削除されること。
- ERRORが7日を超えて残ること。
- 1か月後でもERROR履歴を確認できること。

### 19.3 Supabase

- Publishable keyで送信できること。
- Secret keyなしで動作すること。
- 100件バッチが登録できること。
- 同じバッチを再送しても重複しないこと。
- HTTP失敗時にSQLiteの`uploaded`が変更されないこと。
- 成功時だけ`uploaded=1`になること。
- `measured_at`と`created_at`の差が正しく保持されること。

### 19.4 バックアップ

- SQLite DBを外部保存できること。
- バックアップを取り込んでも重複しないこと。
- 元DBが破損しないこと。

### 19.5 グラフ

- 項目切替できること。
- 欠測期間が実時間の空白として表示されること。
- 24時間範囲が正しいこと。
- 240点程度へ間引いても時刻関係が崩れないこと。

---

## 19.6 本チャットで追加・具体化した要求

### 19.6.1 Modbus TCPの1接続化
- 1回の60秒測定周期における通信は、1本のTCP接続にまとめる。
- TCP接続確立後、FC03で次の3ブロックを順番に読み取り、全応答受信後に切断する。
  - 開始アドレス4114、28レジスタ
  - 開始アドレス4364、7レジスタ
  - 開始アドレス4390、1レジスタ
- 各ブロックの読取りごとにTCP接続と切断を繰り返さない。
- 各要求でTransaction IDを更新し、応答のTransaction ID、Protocol ID、Unit ID、MBAP Length、Function CodeおよびByte Countを検証する。
- 途中ブロックでSocketException、`ECONNRESET`、タイムアウトまたはModbus例外が発生した場合、その周期の不完全な測定値はSQLiteへ保存しない。
- エラー時を含め、TCPソケットは確実に閉じ、次の60秒周期で再試行する。
- eventログは1周期につきTCP接続開始、接続成功、TCP切断を各1回記録し、その間に3組のSEND/RECVを記録する。

### 19.6.2 Androidグラフの時刻軸
- Androidアプリ内のグラフも、データ点番号ではなく実時刻を横軸に使用する。
- 直近24時間表示では、左端を表示開始時刻、中央を12時間後、右端を表示終了時刻として時刻ラベルを表示する。
- 各データ点のX座標は、表示開始時刻からの経過時間を24時間の表示幅に対応させて算出する。
- 欠測時間が長い場合、その時間に比例した間隔として表示し、データ点を等間隔に並べない。
- 最大240点へ間引いた後も、各点の実時刻関係を維持する。
- 旧Androidでのfloat精度低下を避けるため、描画計算にはUNIX時刻そのものではなく、表示開始時刻からの経過秒数などの相対時間を使用できること。

### 19.6.3 SHL24・Galaxy共通のバックアップ保存
- GalaxyのScoped Storage制限を考慮し、共有ストレージ直下の固定パスへ直接書き込まない。
- 原則として`Context.getExternalFilesDir(null)`配下に`MidniteLogger`フォルダーを作成する。
- 標準的な保存先例は`/storage/emulated/0/Android/data/jp.terada.midnitelogger/files/MidniteLogger/`とする。ただし、固定文字列で決め打ちせず、Android APIが返したパスを使用する。
- `getExternalFilesDir(null)`がnullの場合は、バックアップ不可の理由を画面表示し、測定DB本体を変更しない。
- フォルダー作成に失敗した場合は、保存先パスを含むエラーを表示する。
- DBバックアップとDB統合は同じフォルダーを参照する。
- 保存成功時は、実際の保存先ファイルパスを画面表示する。
- アプリ専用外部領域はアンインストール時に削除される可能性があるため、長期保管用としてStorage Access FrameworkまたはGoogle Driveへの書出しを将来追加する。

### 19.6.4 追加受入基準
- SHL24で1周期につきTCP接続開始とTCP切断が各1回となること。
- 1本のTCP接続内で4114/28、4364/7、4390/1の順に読取りが完了すること。
- 4364または4390の送信時に`sendto failed: ECONNRESET`が連続発生しないこと。
- 欠測区間が実時間に比例したグラフ間隔として表示されること。
- Galaxyで`java.io.IOException: failed to create folder`が発生せず、バックアップを保存できること。
- SHL24とGalaxyの双方でバックアップ保存とDB統合が同一フォルダーを使用すること。

## 20. 設計上の原則

1. ローカル記録を最優先する。
2. クラウド送信失敗をロギング停止の理由にしない。
3. データの時刻は測定時刻と登録時刻を分離する。
4. データ再送を前提に冪等性を確保する。
5. SQLiteを原本とし、クラウドサービスへロックインしない。
6. 古いAndroidでも動く保守的な実装を選ぶ。
7. 未確認のModbusコードや充電状態を推測で確定しない。
8. Classicへの書込みは最小限かつ機能を分離する。
9. ERRORは長期保存し、通常診断ログは期限管理する。
10. 既存構造を維持し、機能追加は局所的に行う。
