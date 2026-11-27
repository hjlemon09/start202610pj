# [S0] Composable Storefront（Spartacus）でOCC endpointのfieldsを調整する方法

[S0-L1] 作成日: 2026-11-27

[S0-L2] この文書は、移行パターン検討文書（原本・要約版）の`S3.3-L1`にある「必要ならendpointの`fields`を調整する」を具体的に説明する。対象は、標準OCC APIをそのまま使いつつ、レスポンスに含まれるフィールドを変えたい場合である。

[S0-L3] 設定キーと既定値はSpartacusの公開ソース（`develop`ブランチ）で確認した。実際に使うSpartacusのバージョンの既定設定ファイルで改めて確認する必要がある。

---

## [S1] fieldsがレスポンスを決める仕組み

[S1-L1] OCC APIは、リクエストの`fields`パラメーターに従ってレスポンスに入れるフィールドを決める。指定方法は3つある。

[S1-T1] (表)

| 指定方法 | 例 | レスポンスに入るフィールド |
|---|---|---|
| レベル | `fields=DEFAULT` | サーバーのFieldSetLevelMappingで定義された、そのレベル（BASIC/DEFAULT/FULL）のフィールド |
| 明示リスト | `fields=code,name,price(formattedValue)` | 列挙したフィールドだけ。入れ子のオブジェクトは括弧の中でさらに指定 |
| レベル＋明示 | `fields=DEFAULT,averageRating,images(FULL)` | レベルのフィールドに、列挙したフィールドを加える |

- [S1-L2] `fields`を指定しない場合は、サーバーの既定値（通常は`DEFAULT`）が使われる。
- [S1-L3] Spartacusはendpointごとに、`fields`を含むURLテンプレートを設定として持っている。そのため、フロントエンドでフィールドを変える作業は**このURLテンプレートの`fields`部分を直すこと**である。

---

## [S2] 最初に判断すること: フロントエンドだけを変えればよいか

[S2-T1] (表)

| 状況 | やること |
|---|---|
| 必要なフィールドは既にWsDTOにあり、Spartacusがそのフィールドを要求していないだけ | **フロントエンドのfields設定だけを変える**（下の「設定を入れる場所と方法」以降） |
| WsDTOにフィールドがない | バックエンドでWsDTOのフィールドを追加してから、fields設定を変える（移行パターン検討文書のレスポンス項目追加の節） |
| endpointがレベル（DEFAULTなど）だけで要求していて、そのレベルにフィールドがない | フロントエンドで明示フィールドを付け足すか、サーバーのFieldSetLevelMappingでレベルにフィールドを追加する |

[S2-L1] どちらに当たるかは、実際のレスポンスで確認する。
- [S2-L2] ブラウザーの開発者ツールのネットワークタブで、Spartacusが送るリクエストURLの`fields`を見る。
- [S2-L3] 同じURLの`fields`を`FULL`に変えて直接呼び出すと、そのフィールドがWsDTOにあるかどうかがわかる。

---

## [S3] 設定を入れる場所と方法

### [S3.1] provideConfigでendpointを上書きする

[S3.1-L1] Spartacusのアプリモジュール（例: スキーマティクスが作った`spartacus-configuration.module.ts`）の`providers`に、`provideConfig`で`OccConfig`を入れる。

[S3.1-L2] (コードブロック)
```ts
import { provideConfig, OccConfig } from '@spartacus/core';

@NgModule({
  providers: [
    provideConfig(<OccConfig>{
      backend: {
        occ: {
          endpoints: {
            product: {
              details:
                'products/${productCode}?fields=averageRating,stock(DEFAULT),description,availableForPickup,code,url,price(DEFAULT),numberOfReviews,manufacturer,categories(FULL),priceRange,multidimensional,tags,images(FULL),originCountry',
            },
          },
        },
      },
    }),
  ],
})
export class SpartacusConfigurationModule {}
```

### [S3.2] 注意: 文字列は丸ごと置き換わる

- [S3.2-L1] Spartacusの設定はオブジェクト単位でマージされるが、endpointの値である**文字列は付け足されず、丸ごと置き換わる**。
- [S3.2-L2] そのため、フィールドを1つ追加したいだけでも、**既定の文字列全体をコピーしてから末尾に追加**する必要がある。上の例の`details`は、既定値の末尾に`originCountry`だけを加えたものである。
- [S3.2-L3] 既定の文字列はバージョンごとに違う。使うバージョンの既定設定ファイルからコピーする（下の「既定設定ファイルの場所」）。
- [S3.2-L4] Spartacusをアップグレードするときは、既定の文字列が変わっていないかを比較する必要がある。上書きしたendpointの一覧を管理しておくことを勧める。

### [S3.3] URLテンプレートの変数

[S3.3-L1] `${productCode}`、`${userId}`、`${cartId}`のようなプレースホルダーはそのまま残す。Spartacusが呼び出すときに値を埋める。

---

## [S4] endpointの種類ごとの調整方法

### [S4.1] 商品: scopeごとに分かれている

[S4.1-L1] 商品のendpointは、`backend.occ.endpoints.product`の下に**scopeごとの文字列**として分かれている。

[S4.1-T1] (表)

| scope | 用途 | 既定のfieldsの形 |
|---|---|---|
| `default` | scopeを指定しない照会 | `DEFAULT,...`（レベル＋明示） |
| `list` | 一覧・カード表示 | 明示リスト |
| `details` | 商品詳細 | 明示リスト |
| `attributes` | 分類属性 | `classifications` |
| `price`、`stock`、`promotions`、`list_item`など | 部分的な照会 | 明示リスト |

- [S4.1-L2] 商品詳細画面は複数のscopeをまとめて読み込む。既定の設定では、`loadingScopes`で`details`が`list`と`variants`を含む。
- [S4.1-L3] 新しいフィールドを**どの画面で使うか**に合わせてscopeを選ぶ。一覧でも使うなら`list`に、詳細だけで使うなら`details`に入れる。
- [S4.1-L4] 商品データはscope単位でキャッシュされる。違うscopeに入れると、その画面では値が空に見える。

[S4.1-L5] **新しいscopeを作る方法**（既存のscopeに手を付けず、フィールドを別に受け取りたいとき）

[S4.1-L6] (コードブロック)
```ts
provideConfig(<OccConfig>{
  backend: {
    occ: {
      endpoints: {
        product: {
          origin: 'products/${productCode}?fields=code,originCountry,certifications',
        },
      },
    },
    loadingScopes: {
      product: {
        details: { include: ['list', 'variants', 'origin'] },
      },
    },
  },
}),
```

- [S4.1-L7] コンポーネントでは`ProductService.get(productCode, 'origin')`で、そのscopeだけを読み込める。
- [S4.1-L8] 上のように`details`の`include`に追加すると、詳細画面を開いたときに一緒に読み込まれる。`include`も配列なので丸ごと置き換わる。既定値（`list`、`variants`）も一緒に書く。

### [S4.2] 商品検索

[S4.2-L1] `productSearch.default`なども明示リストである。商品のフィールドは`products(...)`の括弧の**中に**追加する。

[S4.2-L2] (コードブロック)
```text
products/search?fields=products(code,name,...,baseProduct,badges),facets,breadcrumbs,...
                                                  ↑ ここに追加
```

[S4.2-L3] 検索結果のフィールドは、商品詳細とは別のConverterで埋められる。バックエンドの検索結果側にも値があるかを確認する。

### [S4.3] カート: 上位はレベル、明細は明示リスト

[S4.3-L1] カートのendpoint（`cart`、`carts`）の既定値は次の形である。

[S4.3-L2] (コードブロック)
```text
users/${userId}/carts/${cartId}?fields=DEFAULT,potentialProductPromotions,...,
  entries(totalPrice(formattedValue),product(images(FULL),stock(FULL)),basePrice(formattedValue,value),updateable),
  totalPrice(formattedValue),...
```

[S4.3-T1] (表)

| 追加するフィールドの場所 | 方法 |
|---|---|
| カート自体（`CartWsDTO`） | サーバーでDEFAULTレベルに入れれば、フロントの設定を変えなくても入る。または文字列の末尾に明示フィールドを追加 |
| カート明細（`OrderEntryWsDTO`） | **`entries(...)`の括弧の中に追加する必要がある。** entriesは明示リストなので、サーバーのDEFAULTレベルに入れても入らない |
| 明細の中の商品（`ProductWsDTO`） | `entries(...product(...))`の`product(...)`の括弧の中に追加 |

- [S4.3-L3] 例: 明細に`giftWrap`を追加すると`entries(totalPrice(formattedValue),...,updateable,giftWrap)`になる。
- [S4.3-L4] `cart`と`carts`（一覧）は別々の文字列である。両方で使うなら両方を直す。

### [S4.4] 注文・ユーザー

[S4.4-T1] (表)

| endpoint | 既定のfields | 調整方法 |
|---|---|---|
| 注文詳細`orderDetail`、注文確定`placeOrder` | `FULL` | サーバーのFULLレベルにあればそのまま入る。なければ`FULL,追加フィールド`に変える |
| 注文履歴`orderHistory` | 指定なし | サーバーの既定値（DEFAULT）に従う。変えるには`?fields=...`を付けた文字列で上書きする |
| ユーザー`user` | 指定なし | 上と同じ |

---

## [S5] 受け取ったフィールドを画面で使う

### [S5.1] TypeScriptの型の拡張

[S5.1-L1] レスポンスにフィールドが入っても、Spartacusのモデル型にはないため、モジュール拡張（module augmentation）で型に追加する。

[S5.1-L2] (コードブロック)
```ts
// 商品
declare module '@spartacus/core' {
  interface Product {
    originCountry?: string;
  }
}

// カート明細
declare module '@spartacus/cart/base/root' {
  interface OrderEntry {
    giftWrap?: boolean;
  }
}
```

- [S5.1-L3] インターフェースが定義されているパッケージ名はバージョンによって違う場合がある。使うバージョンで、そのインターフェースをexportしているパッケージを確認する。

### [S5.2] Normalizerは通常は不要

- [S5.2-L1] 標準のConverterは、OCCレスポンスのフィールドをほとんどそのまま渡す。そのため、単純な値は型を拡張するだけで使える。
- [S5.2-L2] 値の形を変える必要があるとき（日付の変換、コード → 表示名など）だけ、カスタムNormalizerを追加する。

### [S5.3] 表示

- [S5.3-L1] 表示するコンポーネントをカスタマイズするか（CMSコンポーネントのマッピングを差し替える）、既存コンポーネントのoutletに差し込む。

---

## [S6] 作業手順と確認

[S6-L1] (コードブロック)
```text
1. ネットワークタブで、対象画面が呼んでいるendpointのキーと現在のfieldsを確認
2. fields=FULLで直接呼び出し、フィールドがWsDTOにあるかを確認（なければバックエンドの作業が先）
3. 使っているSpartacusのバージョンの既定設定ファイルから、そのendpointの文字列をコピー
4. フィールドを正しい場所（最上位 / entries(...) / products(...) / product(...)）に追加
5. provideConfigで上書き。商品ならscopeを選ぶか、新しいscope＋loadingScopes
6. 型の拡張（module augmentation）、必要ならNormalizer
7. ネットワークタブで、リクエストのfieldsとレスポンスにフィールドが入ったかを確認
8. 同じendpointを使う他の画面で、レスポンスのサイズや表示に問題がないかを確認
```

### [S6.1] 既定設定ファイルの場所（`develop`ブランチ基準）

[S6.1-T1] (表)

| endpoint | ファイル |
|---|---|
| 商品、商品検索、商品リファレンス | `core-libs/core/src/occ/adapters/product/default-occ-product-config.ts` |
| カート | `feature-libs/cart/base/occ/config/default-occ-cart-config-factory.ts` |
| 注文 | `feature-libs/order/occ/config/default-occ-order-config.ts` |
| ユーザーアカウント・プロフィール | `feature-libs/user/account/occ/adapters/config/`、`feature-libs/user/profile/occ/adapters/config/` |

[S6.1-L1] インストール済みのアプリでは、`node_modules/@spartacus/...`の下にあるビルド済みファイルで同じ既定値を見つけられる。

---

## [S7] 注意点

- [S7-L1] **レスポンスのサイズ:** 商品の`list`のように頻繁に呼ばれるscopeやカートに重いフィールドを入れると、すべての画面のレスポンスが大きくなる。重い値は別のscopeに分ける。
- [S7-L2] **権限:** 匿名で呼ばれるendpoint（商品など）で、機密性のあるフィールドを要求しない。サーバーが返せば誰でも見られる。
- [S7-L3] **存在しないフィールド:** WsDTOにないフィールドをfieldsに書いても、レスポンスに出ないだけである。タイプミスに気づきにくいので、作業手順の7番の確認を必ず行う。
- [S7-L4] **アップグレード:** 上書きしたendpointは、Spartacusの既定値の変更に自動では追従しない。アップグレードのときに既定の文字列を改めて比較する。

## [S8] 参考

- [S8-L1] [Spartacus: Loading Scopes](https://sap.github.io/spartacus-docs/loading-scopes/)
- [S8-L2] [Spartacus default-occ-product-config.ts](https://github.com/SAP/spartacus/blob/develop/core-libs/core/src/occ/adapters/product/default-occ-product-config.ts)
- [S8-L3] [Spartacus default-occ-cart-config-factory.ts](https://github.com/SAP/spartacus/blob/develop/feature-libs/cart/base/occ/config/default-occ-cart-config-factory.ts)
- [S8-L4] [Spartacus default-occ-order-config.ts](https://github.com/SAP/spartacus/blob/develop/feature-libs/order/occ/config/default-occ-order-config.ts)
