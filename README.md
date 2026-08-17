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

## テスト

```bash
clojure -M:dev:test     # workspace 内（sibling checkout を :local/root で解決）
clojure -M:test         # standalone fork（:git/sha で kotoba-issue を取る）
```

20 tests / 70 assertions。**すべての検査は両方向を出す**——拒否する入力と
受理する入力の両方を持つ（`evidence_test.cljc` 冒頭の note を参照）。
landing 前に、admission の中心的主張（`:undecidable` が `:admitted` に畳まれない）を
実際に壊して 7 failure を確認し、戻してある。

## まだ無いもの

- **appview / HTTP 面。** いまは決定ランタイムだけ。deploy 面を足すときは
  `kotobase-protocol-issues` の `/issues/*`（`git.kotobase.net`）に載せる。
- **永続 store adapter。** `store/mem-store` 以外は未実装。契約は 4 関数なので
  kotobase 側 adapter は薄い。
- **credit の決済面。** 意図的に無い（上記）。
