# [S0] AcceleratorのエンドポイントをOCCエンドポイントへ移行する際の移行パターン検討（要約版）

---

## [S2] 移行作業で現れるパターンの全体像

### [S2.1] Acceleratorエンドポイントの種類と移行単位

[S2.1-T1] (表)

| Accelerator側の種類 | OCC側の移行単位 |
|---|---|
| ページコントローラー（JSPビューを返す） | **1:N**。CMSページAPI＋複数のOCC API＋フロントエンド処理に分解 |
| AJAX/JSONコントローラー（`@ResponseBody`） | **1:1に近い**。対応するOCC APIを探すか、新しく作る |
| フォームPOST＋リダイレクト | 処理はOCCのPOST/PUT/PATCH、画面遷移はフロントエンドのルーティング |

### [S2.2] パターン一覧

[S2.2-L1] エンドポイントを業務機能（照会1つ、更新1つ）の単位に分けたうえで、機能ごとに以下のパターンから1つ以上を選ぶ。

[S2.2-T1] (表)

| パターン | 概要 | 主な作業 |
|---|---|---|
| A. 標準APIで置き換え | 標準OCC＋Spartacus標準機能 | なし（必要なOCC extensionを有効化） |
| B. 標準APIの組み合わせ | 複数の標準APIをフロントエンドで組み合わせる | フロントエンドのみ |
| C. レスポンスデータの拡張 | 同じURLのレスポンスにフィールドを追加 | WsDTO＋fieldsマッピング。Data・Populatorはたいてい既にある |
| D. 下位業務ロジックの拡張 | 同じ契約で、処理ルールだけを変更 | Service・Strategy・Hook |
| E. リクエスト契約の拡張 | 任意パラメーター、リクエストDTOのフィールド追加 | WsDTO、コントローラーの再定義 |
| F. 同じURLの再定義 | `@RequestMappingOverride`で処理を差し替え | カスタムOCCコントローラー |
| G. 新規エンドポイント | 新しいURL、新しい契約 | カスタムOCCコントローラー＋既存facadeの呼び出し |
| H. APIが不要 | CMS、フロントエンド、設定 | CMSコンポーネントのマッピング |

[S2.2-L2] facade・serviceを再利用する移行では、**Gが最も多くなる可能性が高い。** 標準OCCにない機能は、ほとんどが「既存facadeを呼ぶ新しいOCCコントローラー」になる。その次がCとFである。Dは業務ルール自体が変わる場合に使う。

---

## [S3] 既存のOCC APIで完全に置き換えられる場合

### [S3.1] 置き換え可能の判定条件（すべて満たす）

1. [S3.1-L1] **入力**: Acceleratorが受け取っていたパラメーターを、OCC APIのパス・クエリ・本文で表現できる。
2. [S3.1-L2] **処理**: 呼び出されるfacade/serviceが同じか、業務上同じ結果を返す。副作用（イベント、メール、監査ログ）も含める。
   - [S3.1-L3] facadeをそのまま再利用するので、**同じfacadeを呼んでいるか**をコールグラフで比較すると早く判定できる。
3. [S3.1-L4] **出力**: 画面に必要な値がOCCのレスポンス（fields指定を含む）にすべてある。
4. [S3.1-L5] **条件**: 権限、ログイン有無、サイト・通貨・言語のコンテキストが同じ方法で適用される。

### [S3.2] 注意すべき違い

- [S3.2-L1] **コントローラー内のロジック:** facade呼び出しの前後に処理（値の補正、条件分岐、追加のfacade呼び出し）があれば、「同じfacadeを呼ぶ」だけでは置き換えにならない。
- [S3.2-L2] **セッション依存:** AcceleratorのコントローラーがHTTPセッションに入れていたカスタム値は、OCCにはない。
- [S3.2-L3] **fieldsの違い:** 「値がない」と判定する前に、`FULL`または明示指定で再確認する（occ-response-analyzerの`occ-fieldset-mappings.csv`）。
- [S3.2-L4] **OCC extensionが未有効:** B2Bなどは別のOCC extension（例: `b2bocc`）を有効にしないとAPIができない。

### [S3.3] 移行作業

[S3.3-L1] バックエンドはなし、またはOCC extensionの有効化。フロントエンドはSpartacusの標準機能＋必要ならendpointの`fields`を調整する。

---

## [S4] 既存のOCC APIを一部修正する場合

### [S4.1] 同じエンドポイントのレスポンスに項目を追加する

#### [S4.1.1] 実装手順（既存のData・Populatorを再利用する場合）

[S4.1.1-L1] (コードブロック)
```text
[たいてい既にある]
1. *Data Beanのカスタム属性       ← Accelerator画面用に定義済み
2. Populator                      ← Accelerator画面用に登録済み

[新しく行う作業]
3. *WsDTO Beanに同じ名前の属性を追加（カスタムOCC extensionのbeans.xml）
     → DataMapper(Orika)は名前が同じなら自動でマッピングする。名前・型が違う場合はマッピング設定が必要
     → カスタムData型なら、対応するWsDTO型も新しく定義する
4. FieldSetLevelMappingにフィールドを追加（BASIC / DEFAULT / FULLのどれに入れるかを決める）
5. Spartacus: モデル型の拡張＋endpointのfields設定＋表示コンポーネント
```

- [S4.1.1-L2] 1・2が**OCCが呼ぶConverterにも登録されているか**を確認する。
  - [S4.1.1-L3] Acceleratorのコントローラーは、別のfacadeメソッドや別のProductOptionで同じDataを埋めていた可能性がある。
  - [S4.1.1-L4] 例: 商品は`getProductForCodeAndOptions(code, options)`に渡す`ProductOption`によって実行されるPopulatorが変わる。OCCの商品APIがどのオプションを渡すかは**[未確認]**である。
- [S4.1.1-L5] 1・2が**ストアフロントやaddonのextensionに定義されている場合**、そのextensionを無効化すると一緒になくなる（ストアフロントのbeans.xml・spring.xmlなど）。この場合はcore・facadesのextensionへ移す。
- [S4.1.1-L6] 4を行わなくても、`fields=...,originCountry`のように明示指定すればレスポンスに出る。レベルに入れるのは、「fieldsをレベルだけで指定する呼び出し元にも既定で見せるか」を決めることである。
- [S4.1.1-L7] FieldSetLevelMapping Bean（`parent="fieldSetLevelMapping"`、`dtoClass`、`levelMapping`）は**OCCのWebコンテキストに定義する必要がある**。`DefaultFieldSetBuilder`が同じコンテキストから読み込むためである。
- [S4.1.1-L8] 同じ`dtoClass`にBeanをもう1つ定義した場合に、既存の定義とマージされるか上書きされるかは**[未確認]**である。`DefaultFieldSetLevelHelper.addToLevelMap`の動作をソースで確認する。occ-response-analyzerはこのケースを`MULTIPLE_MAPPINGS`として報告する。

#### [S4.1.2] レスポンス項目の追加を検討できる場合

[S4.1.2-L1] Accelerator画面用のDataが既にあるので判断しやすい。判断基準は2つである。

- [S4.1.2-L2] **「Accelerator画面がどのDataのカスタム属性を表示しているか、そのDataが標準OCCレスポンスの元と同じDataか」**
- [S4.1.2-L3] そして、その値が**そのリソースの属性**か

[S4.1.2-T1] (表)

| 条件 | 説明 |
|---|---|
| 同じリソース | 追加する値が、商品・カート・注文・ユーザーなどレスポンス対象のリソースに属する |
| 同じタイミング | そのリソースを照会するときに一緒に必要である |
| 同じ権限 | そのAPIを呼べる人が見てもよい値である |
| 軽い計算 | 同じリクエストの中で大きなコストなしに求められる |
| 画面に依存しない | 特定画面専用の加工値ではない |

[S4.1.2-L4] 例（Accelerator JSPが`${product.xxx}`、`${cartData.xxx}`で使っていたカスタム属性）:

[S4.1.2-T2] (表)

| Acceleratorで表示していたもの | 既にあるはずのData属性 | OCC側で追加するもの |
|---|---|---|
| 商品詳細の原産地・素材・認証マーク | `ProductData.originCountry`など | `ProductWsDTO`に同じ名前のフィールド |
| 商品一覧の「新商品」「限定」バッジ | `ProductData.badges` | `ProductWsDTO`にフィールド。**検索結果側のPopulatorにもあるか**を確認 |
| カート明細ごとのギフト包装有無・配送予定日 | `OrderEntryData.giftWrap`など | `OrderEntryWsDTO`にフィールド。Spartacusは`entries(...)`を明示リストで要求するので、**cart endpointの設定にも追加**しないと表示されない |
| 注文履歴のカスタムステータス名 | `OrderHistoryData.customStatusLabel` | `OrderHistoryWsDTO`にフィールド |
| マイページの会員ランク・ポイント | `CustomerData.memberGrade` | `UserWsDTO`にフィールド |

[S4.1.2-L5] 逆に、次のものはこのパターンに合わない。

- [S4.1.2-L6] **コントローラーがModelに別途入れていた値**（例: `model.addAttribute("relatedBrands", brandFacade.get...)`）
  - [S4.1.2-L7] Dataの属性ではなく、別のfacade呼び出しの結果である。
  - [S4.1.2-L8] そのため、別API（新規エンドポイント）または標準APIの組み合わせになる。
  - [S4.1.2-L9] controller-model-analyzerの結果で、「Data属性のパス」か「別のModel属性」かを区別できる。
- [S4.1.2-L10] 他のリソースの一覧、特定画面の集計、重い外部照会、入力によって変わる計算 → 別API。

### [S4.2] レスポンス項目の追加以外の修正パターン

[S4.2-T1] (表)

| パターン | いつ | 実装方法 | 影響 |
|---|---|---|---|
| D. 下位業務ロジックの拡張 | 契約は同じで、処理ルールだけが違う | Service・Strategy Beanの差し替え（alias）、Hookの追加、facadeの拡張（対象APIにどんな拡張ポイントがあるかは**[未確認]**） | 同じ層を使うすべてのチャネル（バッチ、メール、バックオフィス）に適用される。業務ルール自体の変更なら、ここが適切な場所である。OCCリクエストにだけ必要な処理なら、OCCコントローラー側（FまたはG）で行う |
| D'. Validatorの差し替え・追加 | 入力検証ルールだけが違う | OCCコントローラーが注入されるValidator BeanをOCCのWebコンテキストで差し替え・追加 | OCCリクエストにだけ適用。AcceleratorのフォームValidatorは再利用できず、Accelerator extensionの無効化でなくなるので、ルールを移して書き直す |
| E. リクエスト契約の拡張 | 任意パラメーター、リクエスト本文のフィールド追加 | リクエストWsDTOに属性を追加 → Dataへマッピング。クエリパラメーターはFが必要 | 必須にすると既存の呼び出し元が壊れる。必ず任意項目にする |
| F. 同じURLの再定義 | コントローラーレベルの処理変更 | カスタムOCC extensionに**完全に同じ**マッピングのメソッド＋`@RequestMappingOverride`（詳細は同じフォルダーの`occ-request-mapping-override_jp.md`） | 元のメソッドは呼ばれなくなる。アップグレード時は標準メソッドの変更を自分で追従する必要がある |
| レスポンスレベルだけの調整 | フィールドはあるが既定のレベルにない | FieldSetLevelMappingの修正 | fieldsをレベル（DEFAULT/FULL）で指定する呼び出し元のレスポンスにフィールドが増える |
| HTTP共通処理 | ヘッダー、ロギング、共通チェック | OCCのWebコンテキストのFilter・Interceptor | すべてのOCCリクエスト |
| エラーレスポンスの変換 | カスタム例外を特定のエラーコードにする | 例外 → エラーDTOの変換設定 | フロントエンドのエラー処理に影響 |

[S4.2-L13] **推奨する検討順序**
- [S4.2-L14] まず**C（レスポンスデータ）**で済むかを見る。
- [S4.2-L15] 残った違いは性質で分ける。
  - [S4.2-L16] **業務ルール自体**（検証・計算・後処理）の違い → **D（共有層）**。ルールはfacade・serviceに1つだけ置くほうが二重管理を避けられる。
  - [S4.2-L17] **OCCリクエストの処理方法**（パラメーターの解釈、レスポンスの構成）の違い → **F・G・E（OCCのWeb層）**。
- [S4.2-L18] Dを使う場合は、他のチャネル（バッチ、メール、バックオフィス）への影響を確認する。

---

## [S5] まったく新しいOCCエンドポイントを作成する

### [S5.1] 構成要素

[S5.1-L1] (コードブロック)
```text
[バックエンド: カスタムOCC extension]
  ant extgenのyoccテンプレートで生成する。名前はoccで終わる必要がある（例: xyzocc）
  2211からOCCはaddonではなくextension方式である
  Controller（BaseControllerを継承、<ext>.controllersパッケージ、@RequestMapping("/{baseSiteId}/...")）
    - 既存facadeを注入して呼ぶ  ← ポイント。新しいfacadeは本当に必要なときだけ
    - セキュリティ: @Secured({"ROLE_CUSTOMERGROUP", "ROLE_TRUSTED_CLIENT", ...})など、匿名アクセスの可否
    - ドキュメント: @ApiBaseSiteIdParam, @ApiFieldsParam
    - DataMapper.map(data, XxxWsDTO.class, fields)
  WsDTO＋FieldSetLevelMapping
  Validator（リクエスト本文の検証）
  例外処理とエラーレスポンス

[フロントエンド: Composable Storefront]
  endpoint設定 → Adapter → Connector → Facade/Service（状態管理） → コンポーネント
```

### [S5.2] AcceleratorコントローラーをOCCコントローラーへ移す際の変換表

[S5.2-L1] 移行作業の大部分（約90%）がここに当たる。

[S5.2-T1] (表)

| Acceleratorコントローラーの要素 | OCC側の変換 |
|---|---|
| `@RequestMapping`のURL（`/cart/add`、`/my-account/...`） | RESTのリソースパスとして設計し直す（`/{baseSiteId}/users/{userId}/carts/{cartId}/...`） |
| Formクラス＋`@Valid`＋フォームValidator | リクエストWsDTO＋OCC Validator。Validatorはストアフロントのwebコンテキストにあり、Accelerator extensionの無効化でなくなるので、OCC extensionに書き直す |
| ストアフロント専用のヘルパー・strategy・util | Accelerator extensionの無効化でなくなる。OCCに必要なものはOCC extensionへ移す |
| `model.addAttribute(...)` | レスポンスWsDTOのフィールド。複数あれば1つのレスポンスDTOにまとめるか、APIを分ける |
| 返すビュー名、`storeCmsPageInModel` | なし。画面構成はCMS API＋フロントエンド |
| `redirect:`、`GlobalMessages` | HTTPステータスコード＋エラーDTO。メッセージ表示はフロントエンド |
| `HttpSession`属性の使用 | リクエストパラメーター、リソースの状態（カートの属性など）、フロントエンドの状態で置き換える |
| ログイン確認（`@RequireHardLogIn`など） | OAuthトークン＋`@Secured`・アクセス設定 |
| CSRFトークン | 不要（トークン認証）。代わりにOAuthクライアント・CORSの設定 |
| コントローラー内の分岐・補正ロジック | OCCコントローラーへ移す。複数のコントローラーで繰り返されるならfacadeの追加を検討 |
| セッションカート（`cartFacade.getSessionCart()`） | そのまま使える。OCCの`CartMatchingFilter`がパスの`cartId`でセッションカートを設定する |
| カスタムMVCインターセプター | ストアフロントのインターセプターは移らない。2211では`commercewebservices`のWebコンテキストを使うので、インターセプターはOCC extensionのweb-spring.xmlで登録し直す |

### [S5.3] URL設計の基準

- [S5.3-L1] **既存リソースの下位リソースとして置く**（例: `/{baseSiteId}/users/{userId}/carts/{cartId}/giftwrap`）。標準のパスと重ならないようにし、標準の規則（`current`・`anonymous`、`fields`、`currentPage`・`pageSize`・`sort`）に従う。

---

## [S6] 既存APIの拡張と新規エンドポイント作成の選択基準

### [S6.1] 判断フロー

[S6.1-L1] (コードブロック)
```text
Q1. 同じリソース・同じ業務動作か？                  ── いいえ → 新規エンドポイント
     │ はい
Q2. 違いが「レスポンスデータの不足」だけで、値がDataに既にあるか？
                                                 ── はい → レスポンスデータの拡張 (C)
     │ いいえ
Q3. 違いは業務ルール（検証・計算・後処理）そのものか？
     ├ はい、他のチャネル（バッチ・メール・バックオフィス）にも同じルールが合う → D 下位ロジックの拡張
     ├ はい、OCCリクエストにだけ合う                → F 再定義、またはfacade・serviceの追加を検討
     │ いいえ（リクエスト処理方法の違い）
Q4. OCCのWeb層（コントローラー）で吸収できるか？
     ├ 既存の契約のまま、処理だけが違う             → F 再定義
     ├ 任意パラメーターの追加で表現できる           → E リクエスト拡張 (+F)
     └ 契約そのものが違う                           → 新規エンドポイント
```

### [S6.2] 判断要素ごとの比較

[S6.2-T1] (表)

| 判断要素 | 既存APIの拡張が有利 | 新規エンドポイントが有利 |
|---|---|---|
| リソース | 既存リソースの属性・動作 | 新しいリソース、複数リソースの組み合わせ |
| 契約 | 既存の契約に**追加するだけ**でよい | 既存の契約を変えるか、削る必要がある |
| 適用範囲 | すべてのOCC呼び出し元に適用されてもよい | 特定の画面にだけ必要 |
| コスト | 追加コストが小さい | 重いので、必要なときだけ呼ぶ必要がある |
| 権限 | 既存APIの権限と同じ | 別の権限が必要 |
| 元データ | 値が標準APIの使うDataに既にある | コントローラーが別のfacadeを呼んでModelに入れていた値 |
| アップグレード | SAPが公開している拡張ポイント（fields、WsDTO、Populator）だけを使う | 標準メソッドを再定義（F）する必要があるなら、新しいURLのほうがよい |

### [S6.3] 実務上の基準

- [S6.3-L1] **「既存APIの意味が変わるか」で判断する。** 意味がそのままで情報だけが増えるなら既存APIの拡張、意味が変わるか新しい意味が必要なら新規エンドポイントである。
- [S6.3-L2] Fは既存APIの拡張と新規エンドポイントの中間である。標準メソッドを丸ごとコピーすることになる場合や、マッピングを完全に合わせるのが難しい場合は、新規エンドポイント（新しいURL）を先に検討する。
- [S6.3-L3] 業務ルールは共有層、OCCリクエストの処理方法はOCCのWeb層に分ける。

---

## [S7] その他の移行方法

### [S7.1] 複数の標準OCC APIを組み合わせる

[S7.1-L1] 組み合わせは可能である。どこで組み合わせるかによって2つの方法がある。

[S7.1-T1] (表)

| 方式 | 内容 | 適する | 適さない |
|---|---|---|---|
| **フロントエンドでの組み合わせ** | Spartacusで標準APIを複数回呼んで1つの画面を構成する。Spartacusの標準画面もこの方式である | **照会**、各呼び出しが独立、一部が失敗しても表示できる | **原子性が必要な更新**、呼び出し回数が多くて性能に問題が出る |
| **バックエンドでの組み合わせ** | 新規OCCエンドポイントで既存facadeを複数呼ぶ | 順序・トランザクションの保証、呼び出し回数の削減 | 結局は新規エンドポイントの作成である。ただし既存facadeを再利用すればコストは小さい |

- [S7.1-L2] 例: 「カートに追加した後に配送方法を設定する」のように、途中で失敗しても元に戻す必要がなければ、フロントエンドから順に呼ぶだけで十分である。
- [S7.1-L3] 例: 「ポイント使用と注文確定」のように、片方だけ成功しては困る場合はバックエンドで処理する必要がある。
- [S7.1-L4] **Acceleratorのページコントローラーが複数のfacadeを呼んでModelを埋めていた場合**が、組み合わせの代表的な候補である。各facadeに対応する標準APIがあればフロントエンドで組み合わせ、なければその部分だけ新規エンドポイントにする。
