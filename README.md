# kenbun

**kenbun（検分）は、人間と AI が提出した bug の申告を「これは欠陥報告か、
それとも主張にすぎないか」で選別する決定ランタイムである。** 検分とは
「現場へ行って自分の目で確かめ、確かめた内容を記録に残す」こと。名前が機能を
示さないので最初に名乗る（superproject `CLAUDE.md` の規約: メタファ名の repo は
README 冒頭で名乗る）。ADR-2608170100。

`cloud-itonami/kenbun` は west project で、`orgs/cloud-itonami/kenbun` に展開される。

## なぜ tracker ではなく「選別」なのか

bugspot / Linear / Jira は **申告されたものを受け取って並べる**。人間だけが
申告している間はそれで足りる。人間と AI の両方が申告する瞬間に足りなくなる:

- AI は**自信のある散文を無限に**生成できる。再現しない欠陥も同じ流暢さで書ける。
- 人間も報奨がかかれば同じことをする。

このとき tracker に溜まるのは欠陥ではなく**未検証の主張**であり、しかも
検証済みの欠陥と**同じ顔**をしている。これは superproject `CLAUDE.md` の
「測れなかった検査が、測って問題が無かった検査と同じ値を返す」——沈黙が緑として
蓄積する——のちょうど裏返しで、**未再現の申告が bug として蓄積する**。

だから kenbun の中心は入口の 1 判定であり、それは **3 値**である:

| verdict | 意味 |
|---|---|
| `:admitted` | evidence が判別に足りている。これは欠陥報告である |
| `:rejected` | evidence を**見た上で**、主張を支えないと判断した |
| `:undecidable` | 検査そのものが**走らなかった**（未知の severity、未知の reporter 種別）。pass でも reject でもない |

`:undecidable` を他の 2 つに畳むことがこの repo の防ぎたいバグそのものなので、
`admit-batch` は 3 つのカウントを別々に返し、**単一の boolean を返さない**
（`:clean?` のような key は意図的に無い。1 つの数が欲しい呼び出し側は、
3 つのどれを指しているのか自分で選ばなければならない）。

## 誰が申告したかは、バーを変えない

`:human` と `:agent` は**記録される**が、admission の判定には一切入らない
（`kenbun.evidence/admit` は reporter kind を「既知か」しか見ない）。
記録が変えるのは**何が残るか**だけで、agent の申告は model と prompt-cid も
残す——後の監査が「どのモデルが再現しない申告を出したか」を問えるように。

corroboration（第 2 の再現者）も同様に種別を見ない。**agent が人間を裏づけても、
人間が agent を裏づけても、同じ 1 witness** である。裏づけているのは再現であって
再現者ではない。

## パイプライン

```
申告（人間 or agent）
   │
   ▼  kenbun.finding    形を整える。severity 不明は既定値に落とさず nil のまま
   ▼  kenbun.evidence   3 値の admission ← ここが repo の中心
   ▼  kenbun.dedupe     判別内容の指紋。同じ欠陥の別表現は 1 issue + 2 witness
   ▼  kenbun.triage     severity × witness → lane と、gate に渡す risk tier
   ▼  kotoba.issue.gate issue/proposal/review/merge の状態機械 + 全遷移の audit
   ▼  kenbun.credit     報告者の取り分（整数。値は動かさない）
```

`kenbun.intake` だけが store に書く。他は全部 pure な `.cljc` である。

## 設計判断（実装を読む前に知っておくと早いもの）

- **expected と observed の両方が要る。** 片側だけの申告、あるいは両側が
  同じことを言っている申告は、散文がどれだけ長くても欠陥を述べていない
  （`:no-discrimination` / `:expected-equals-observed`）。
- **正規化で桁を落とさない。** `returns 404` と `returns 500` は別の欠陥。
  落とすのは大文字小文字と空白だけ。
- **hedge は severity 依存で扱う。** 「再現できなかった」「たぶん」を含む
  evidence は `:high` 以上の主張と**矛盾する**ので reject するが、`:low` では
  通す——断続的な欠陥を正直に断続的と書いた報告を罰しないため。
- **dedupe は完全一致キーで比較し、hash は索引ハンドルにすぎない。** hash 衝突で
  2 つの実在の欠陥が併合されると片方が黙って消える。この repo は
  「黙って消えること」を止めるために在るので、そこは短いキーより正しさを取る。
- **high / critical は witness が何人いても人間に回る。** 「この系に致命的な欠陥が
  ある」と公開するのは外向きの行為で、同意した agent の数はその代わりにならない。
- **裏づけ者の取り分は、申告者の取り分から割らない。** 申告者から割る設計は、
  申告者に裏づけを潰すことを教える。
- **`kenbun.credit` は値を動かさない。** 整数を返すだけで、送金レールも台帳書き込みも
  持たない。純関数なので二者が再計算して突き合わせられる——それが監査可能性の中身。

## 最近接 repo との境界

| repo | 何を持つか | kenbun との境界 |
|---|---|---|
| [`kotoba-lang/kotoba-issue`](https://github.com/kotoba-lang/kotoba-issue) | issue → proposal → review → merge → audit の状態機械と `IssueStore` 契約 | **依存する。** 状態機械は再実装しない。kenbun は「issue にする価値があるか」だけを決め、決まった後は全部あちらに渡す |
| [`kotoba-lang/qa-governor`](https://github.com/kotoba-lang/qa-governor) | LLM の自己申告**スコア**を evidence と突き合わせる governor | **依存しない。** 述語が逆向き: あちらは「高スコアの主張が失敗 evidence と矛盾しないか」、こちらは「欠陥の主張を支える再現 evidence が在るか」。形は似ているが判定が違う |
| [`kotoba-lang/app-reviewer`](https://github.com/kotoba-lang/app-reviewer) | 提出アプリの公開前審査（自動チェック → 人間レビュー → 承認/却下） | 対象が違う。あちらは**提出物**を審査する、こちらは**提出物についての申告**を審査する |
| [`kotoba-lang/kip`](https://github.com/kotoba-lang/kip) | 規範面への変更提案キュー | 提案であって欠陥ではない |

## 使う

```clojure
(require '[kenbun.intake :as intake]
         '[kotoba.issue.store :as store])

(def s (store/mem-store))   ; 本番は kotobase / Datomic の adapter を差す

(intake/submit!
 s {:id "f-1"
    :title "Signup form returns 500 on valid input"
    :severity :medium
    :reporter {:id "agent-7" :kind :agent :model "murakumo-main"}
    :target {:repo "net-kotobase/site" :rev "abc1234" :surface "/signup"}
    :evidence {:steps ["open /signup" "submit a valid address"]
               :expected "201 and a confirmation mail"
               :observed "500, no mail"}})
;; => {:kenbun.intake/outcome :filed
;;     :kenbun.intake/issue    #:kotoba.issue{:id "issue:f-1" :state :open ...}
;;     :kenbun.intake/triage   #:kenbun.triage{:lane :needs-second-repro :risk :read-only}
;;     :kenbun.intake/credit   {"agent-7" 10} ...}
```

`submit!` は不正な申告で **throw しない**——選別のために在る入口が入力で落ちたら
入力を失う。壊れた申告も `:kenbun.intake/outcome` を持つ結果として返る。

## 永続 store（opt-in）

`kenbun.store.kotobase` が kotobase の datom 面に載る `IssueStore` である。
**`src` ではなく `src-kotobase` に置いてある**——`mem-store` で足りる利用者に
datom 面を引かせないため。有効化は `:kotobase` alias。

```clojure
(require '[kenbun.store.kotobase :as kbs]
         '[kotobase.core :as kb]
         '[kotobase.storage.memory :as memory])

(def s (kbs/kotobase-store
        (kb/open {:storage (memory/memory-store)   ; provider は呼び出し側が選ぶ
                  :encrypt-fn identity :decrypt-fn identity
                  :blind-fn pr-str :visible? (constantly true)})))
```

finding / issue / proposal / review / audit は**全部 1 つの ref に入る**。
`kotobase.core/open` は `:ref-name` を 1 つしか取らないので Datalog の到達範囲は
ちょうど ref 1 本（ADR-260726）——分けると「どの報告者の finding が merge された
修正になったか」が query で辿れなくなる。**代償は kenbun の全書き込みが 1 ref に
直列化すること**で、これは黙って取らず書いておく規則になっている。

### 素朴に書くと壊れる 3 点（すべて実測。仮定していない）

| 面の挙動 | 素朴な adapter だとどうなるか |
|---|---|
| `transact!` は**上書きせず蓄積する** | `status` が :proposed→:approved→:merged と動くと 3 つ全部残り、`q` は set なので**任意の 1 つ**が返る。merge 済みの proposal が未レビューとして読める |
| 値は文字列化され、`Date` は `pr-str` ではなく **`str`** | `"Thu Jan 01 09:00:00 JST 1970"` になり**読み戻せない**。audit の全レコードが Date を持つ |
| `nil` が `""` になる | 本物の空文字列と区別できない |

対策: (1) 書くたびに当該 predicate の既存値を**名指しで retract してから assert**
（同一 transaction 内。`[:db/retract s p nil]` のワイルドカードは**効かない**ことも実測済み）
(2) 値は一律 `pr-str` / `clojure.edn/read-string`（`#inst` が読める）
(3) 一律なので `nil` は `"nil"`、`""` は `"\"\""` になり衝突しない。

一律エンコードには実費がある——外部の Datalog query は `":critical"` と
エンコード後の形で当てる必要がある。ただしこれは**見える**代償（query が
何も返さない）であって、型ごとに使い分けたときの**黙った誤読**ではない。
`decode` は public にしてある。

なお `entity-from-triples` は、1 predicate に複数値が在った場合に
**1 つを選ばず throw する**。ここで推測すると、この adapter が防いでいるバグを
自分で作り直すことになる。

## HTTP 面

`kenbun.http` は **pure な `.cljc` 関数** `(handle ctx req) → resp`。I/O も
host JSON も transport も持たない——認証・socket・store の注入は deploy shell の
仕事で、これは `kotobase.protocols.issue` と同じ seam なので、自前の server を
持たずにその隣に mount できる。wire は **EDN**（`application/edn`）。

```
POST /findings                 申告する（201 filed / 200 corroborated /
                                       422 rejected / 409 undecidable）
GET  /findings                 一覧（?lane= ?confirmed=）
GET  /findings/{id}
GET  /issues                   一覧（?state= ?lane=）
POST /proposals/{id}/reviews   判定を記録する。:approve は確定まで行う
GET  /report                   いま store に在るものの数
```

### 報告者は ctx から取る。body からは取らない

**この面が存在する理由がこの 1 行である。** `kenbun.credit` は admitted な
finding を「名前の付いた報告者への取り分」に変えるので、**body が名乗る報告者 id は
「submitter が指名した相手に払え」という指示**になる。全 handler は ctx の認証済み
principal から報告者を作り、`:reporter` を積んだ body は**無視ではなく reject** する
——黙って落とすとクライアントからは尊重されたのと**見分けがつかない**。

同じ理由で `:reproduced-by` を body から受け取らない（自分で witness を名乗ると
credit を水増しでき、`:auto-file` lane に自力で入れてしまう）。principal の**認証**は
shell の仕事だが、**信用するかどうか**はこの ns が決めることではない——principal の
無い write は全部 401 で、匿名の報告者を発明しない。

### 3 値は wire でも 3 値のまま

`:rejected` は **422**（理解した上で処理しない）、`:undecidable` は **409**
（server がどちらとも断定するのを拒む）。同じ status に畳まない。

`GET /report` は**submission 総数を報告しない**——reject / undecidable は
entity を残さないので、store に在るものを「提出された全部」であるかのように
数えるのは admission gate が拒んでいる collapse そのもの。答えるのは
`intake/intake-report`（結果の集合が要る）の仕事。

## Deploy（live）

**https://kenbun.04-feasts-minded.workers.dev** — Cloudflare Worker、Durable Object
1 個を直列化器、D1 を storage に使う。ビルドは `worker/`。

```bash
cd worker
node ../../../../scripts/resource-guard.mjs run build -- npx shadow-cljs release worker
npx wrangler deploy
```

| 部品 | 役割 |
|---|---|
| `kenbun.worker` | socket・認証・store 注入。`kenbun.http/handle` は無改造 |
| `kenbun.worker.d1-store` | hydrate → 同期 handler → 差分 commit（1 batch = 1 transaction） |
| DO `KenbunStore`（1 instance） | **直列化器**。read も通す |
| D1 `kenbun` | 行の置き場（**共有バックエンド**。DO の中には置かない） |

### なぜ DO + D1 なのか

read-modify-write は 2 リクエストが交差すると**黙って**更新を落とす（両方 201 を返し、
片方の finding が消える）。DO は globally unique・single-threaded なので
「書き手はちょうど 1 人」を write lease も fencing epoch も**書かずに**得られる。
storage を DO の中ではなく D1 に置くのは workspace 規則（DO は直列化器、
ストレージは共有バックエンド）。

**R2 が第一候補だったが使えない** — この account の OAuth token に **`r2` scope が無い**
（ADR-2607299900 が踏んだのと同じ blocker、2026-08-17 に `wrangler whoami` で再実測）。
D1 は**アプリの DB** として使っており、分散を名乗る経路の premise ではない
（消せば kenbun は履歴を失う。だから kenbun はそういう主張をしない）。

### ⚠ D1 backend は暫定であり、恒久の設計ではない

**本来の backend は kotobase.net**（この workspace の graph BaaS）である。
`kotoba-lang/kotobase-client` は **ClojureScript** なので **Worker で動き**、
`q` / `datoms` / `pull` / `transact` / `fold` を持ち、サービスは live。
上の hydrate → 同期 handler → 差分 commit の橋は backend を差し替えるだけで
そのまま効くので、**「`IssueStore` が同期だから無理」は理由になっていなかった**
（2026-08-17、オーナー指摘で判明。私は埋め込みエンジン `kotobase.core` だけを見て
hosted BaaS を検討していなかった）。

kotobase.net に載せれば、下の「塞がるもの」は**塞がらない**——
`:apex` は graph scope == issuer DID を要求するので、**鍵の本数がそのまま Datalog で
結合できる範囲になる**（艦隊 1,197 actor が seed 1 本を共有しているのはこの理由）。

**いま止まっているのは能力ではなく鍵の所在**: `itonami-fleet-kotobase-seed` は
kagi に存在せず（`no such item`、2026-08-17 実測）、投入先は読み戻せない Worker
secret。**新しい seed は発行しない**——新 seed = 新 DID = 新 graph であり、
それはオーナーが 2026-07-30 に明示的に退けた分割そのものだから。

### 現状の D1 backend が塞いでいるもの（暫定の代償）

**この行は共有 kotobase datom 面に無い。** `repo-taxonomy` / `repo-maturity` と
repo path で join する query——「どの repo が確定欠陥を最も抱えているか」——は
**この deployment に対しては書けない**。

### 認証

`Authorization: Bearer <token>` を `PRINCIPALS` secret（token → principal の EDN map）で
引く。principal はここ（shell）で解決し、**クライアントが設定できないヘッダ**として
DO へ渡す（DO は公開アドレスを持たない）。secret が壊れていれば nil を返す——
誤設定は「誰も書けない」に落ちるべきで、「誰でも書ける」に落ちてはいけない。

**共有 secret 表はこれが持てる最弱の identity である。** 入力 1 つの関数 1 本にして
あるので、CACAO 検証で置き換えるときに他へ波及しない。

### routes を宣言していない

custom domain は**単一所有**で、最後に deploy した側が勝ち、**どちらにも conflict が
出ない**（`kotobase-protocols-worker` は 2026-07-17 にこれで `git.kotobase.net` を
失った）。だから v0.1 は workers.dev だけに出し、ホスト名は**副作用ではなく明示的に**
取りにいく。

### live 実測（2026-08-17）

```
/health                        principals-configured / bindings-present を申告
POST 未認証                    401 no-authenticated-principal
POST human, 良い evidence      201 filed
POST agent, 同じ欠陥・別の言葉  200 corroborated（reproduced-by #{"agent-7"}、issue は 1 本のまま）
POST 再現手順なし               422 no-repro-steps
POST 未知 severity              409 unknown-severity（422 とは別 status）
POST body が :reporter を名乗る 400 reporter-not-accepted-in-body（何も filed されない）
POST body が :reproduced-by     400 reproduced-by-not-accepted-in-body
POST review :approve            200 → :merged → confirmed
```

D1 を直接引いた結果（API 越しではなく DB で確認）: `finding 1 / issue 1 / proposal 1 /
review 1`、audit は順序どおり 5 件
（`issue:opened → propose → corroborated → review:approved → merge:merged`）。
**reject / undecidable は 1 行も残していない**——それが `GET /report` が submission 総数を
報告しない理由でもある。

## テスト

```bash
clojure -M:dev:test                            # base + HTTP（38 tests / 129 assertions）
clojure -M:dev:kotobase:test:kotobase-test     # + 永続 store（48 tests / 167 assertions）
clojure -M:test                                # standalone fork（:git/sha で解決）
clojure -M:lint                                # 4 つの source root すべて
```

**すべての検査は両方向を出す**——拒否する入力と受理する入力の両方を持つ
（`evidence_test.cljc` 冒頭の note）。`intake-behaves-identically-on-both-stores` は
同じシナリオを `mem-store` と kotobase の**両方**に流し、adapter が真の代替物で
あることを確かめる。

landing 前に 3 回壊して確かめた: admission の中心的主張（`:undecidable` が
`:admitted` に畳まれない）を壊すと 7 failure、retract-then-assert を外すと 4 error、
body が報告者を名乗れるようにすると 3 failure。

⚠ **`:kotobase-test` alias は `-d` を明示している。** `cognitect.test-runner` の
既定は `test` 1 つだけなので、`:extra-paths` に足すだけでは **classpath に載るが
1 本も走らず**、base suite の green が adapter を覆っているかのように見える。
`:lint` が 4 つの root を名指ししているのも同じ理由。

## まだ無いもの

- **custom domain。** live なのは workers.dev だけ。`kenbun.itonami.cloud` は
  未取得（上記のとおり意図的に、副作用で取らない）。
- **Worker が datom 面に載っていない（最優先）。** 上記のとおり、これは**能力の
  制約ではなく鍵の所在**である。`kotobase-client` は cljs で Worker で動き、
  hydrate→commit の橋はそのまま使える。止まっているのは
  `itonami-fleet-kotobase-seed` が kagi に無いこと。seed が戻れば次の順で進む:
  `[:db/add e a v]` が置換か蓄積かを probe → store 実装 → Worker 差し替え →
  live 検証 → D1 撤去。
  ⚠ この項目は 2026-08-17 まで「非同期契約が上流に無いので実在する gap」と
  書いていた。**それは誤りで、同じ README の deploy 節が既に訂正していたのに
  ここだけ古い論拠が残っていた**——訂正は文書の全箇所に当てる。
- **hydrate が毎リクエスト全件を読む。** dedupe が「on file のどれかと同じ欠陥か」を
  問うので v0.1 では全件。表が育てば通用しなくなる——隠れた TODO ではなく
  `d1-store` の docstring と ここに書いてある。
- **認証が共有 secret 表。** 持てる最弱の identity。CACAO 検証への差し替えが次。
- **durable provider の実測。** adapter は provider 非依存だが、検証は
  `storage.memory` に対してのみ行った。sqlite / s3 / postgres provider での実測は
  していない——**していないものを「動く」と書かない。**
- **credit の決済面。** 意図的に無い（上記）。
- **fleet gate 登録。** 未実施。
