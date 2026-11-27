# [S0] OCC標準APIの同じURLの再定義（@RequestMappingOverride）

[S0-L1] 作成日: 2026-11-27

[S0-L2] この文書は、移行パターン検討文書（要約版）の「F. 同じURLの再定義」パターンを詳しく説明する。内容は公開サンプルとSAPコミュニティの報告に基づく。2211のソースでは確認していない。

---

## [S1] いつ使うか

- [S1-L1] 標準OCC APIのURLとリクエスト・レスポンスの契約はそのままにして、**コントローラーレベルの処理だけを変えたいとき**に使う。
- [S1-L2] カスタムOCC extensionに標準と**完全に同じマッピング**のメソッドを作り`@RequestMappingOverride`を付けると、そのURLへのリクエストは標準メソッドではなくカスタムメソッドに届く。
- [S1-L3] 変えたいものがすべてのチャネルに必要な業務ルールであれば、再定義よりも共有層（facade・service、Hookなど）の拡張のほうが適している。

---

## [S2] 具体例

[S2-L1] Acceleratorのカート追加コントローラーにあったカスタムチェック（購入数量の制限）を、標準OCCのカート明細追加API（`POST /{baseSiteId}/users/{userId}/carts/{cartId}/entries`）に入れる場合である。コードは形を示すための要約である。標準メソッドの実際のシグネチャと処理は2211のソースで確認する。

### [S2.1] Accelerator（現行）

[S2.1-L1] コントローラーの中でチェックしてから標準のfacadeを呼ぶ。

[S2.1-L2] (コードブロック)
```java
@RequestMapping(value = "/cart/add", method = RequestMethod.POST)
public String addToCart(@RequestParam("productCodePost") String code, Model model,
                        @Valid AddToCartForm form, BindingResult bindingErrors) {
    if (!purchaseLimitFacade.canAdd(code, form.getQty())) {   // カスタムチェック
        GlobalMessages.addErrorMessage(model, "basket.error.limit");
        return ControllerConstants.Views.Fragments.Cart.AddToCartPopup;
    }
    CartModificationData modification = cartFacade.addToCart(code, form.getQty());
    model.addAttribute("modifications", List.of(modification));
    return ControllerConstants.Views.Fragments.Cart.AddToCartPopup;
}
```

### [S2.2] OCC（再定義）

[S2.2-L1] カスタムOCC extensionに標準と**完全に同じマッピング**のメソッドを作り、`@RequestMappingOverride`を付ける。

[S2.2-L2] (コードブロック)
```java
@Controller
@RequestMapping(value = "/{baseSiteId}/users/{userId}/carts")
public class CustomCartEntriesController extends BaseController {
    @RequestMappingOverride
    @RequestMapping(value = "/{cartId}/entries", method = RequestMethod.POST)
    @ResponseBody
    public CartModificationWsDTO createCartEntry(@PathVariable String baseSiteId, @PathVariable String userId,
            @PathVariable String cartId, @RequestBody OrderEntryWsDTO entry,
            @RequestParam(defaultValue = "DEFAULT") String fields) {
        if (!purchaseLimitFacade.canAdd(entry.getProduct().getCode(), entry.getQuantity())) {   // 移してきたチェック
            throw new CartException("purchase limit exceeded");   // エラーレスポンスに変換
        }
        // 以下、標準メソッドが行っていた処理をここで実装し直す
        CartModificationData modification = cartFacade.addToCart(entry.getProduct().getCode(), entry.getQuantity());
        return getDataMapper().map(modification, CartModificationWsDTO.class, fields);
    }
}
```

[S2.2-L3] この例のチェックがすべてのチャネルに必要な業務ルールであれば、再定義よりも共有層の拡張（カート追加のHookなど）のほうが適している。

---

## [S3] 再定義すると元のメソッドは使えない

- [S3-L1] 同じマッピングへのリクエストはすべて再定義したメソッドに届き、標準メソッドは呼ばれなくなる。
- [S3-L2] 継承ではなくマッピングを差し替える方式なので、`super`で標準の処理を呼ぶこともできない。
- [S3-L3] そのため、標準メソッドが行っていた処理（入力検証、店舗受け取りの処理、レスポンスの構成など）は、再定義したメソッドの中で同じfacadeを呼んで**実装し直す**必要がある。
- [S3-L4] アップグレードで標準メソッドが変わった場合は、その変更も自分で反映する必要がある。

---

## [S4] 作成ルールと注意点

- [S4-L1] アノテーション: `de.hybris.platform.commerceservices.request.mapping.annotation.RequestMappingOverride`。
- [S4-L2] マッピングは**完全に同じ**である必要がある。パス変数の名前が違うだけでも（`{productCode}`と`{productId}`）別のマッピングとして扱われる。この場合、起動時ではなく**リクエスト時**に「Ambiguous handler methods」エラーになる。元の`PUT, POST`マッピングのうち`PUT`だけを再定義することもできない。
- [S4-L3] 優先度は`priorityProperty`で指定したプロパティ、または`requestMappingOverride.<クラスFQN>.<メソッド名>.priority`で決まる。値がなければ0で、**最も高い値**のものが使われる。
- [S4-L4] ASMモードなど特定の経路で再定義が呼ばれなかったという報告もある。再定義したAPIは利用経路ごとにテストする。
- [S4-L5] 標準メソッドを丸ごとコピーすることになる場合や、マッピングを完全に合わせるのが難しい場合は、再定義ではなく新しいURLの新規エンドポイントを先に検討する。

---

## [S5] 参考

- [S5-L1] [公開サンプル: 2211のOCC extensionと@RequestMappingOverride（gist）](https://gist.github.com/sperez2-seidor/327f5db98d2d8368e3bb099e6a800929)
- [S5-L2] [SAP Community: 2211のOCCコントローラーの再定義](https://community.sap.com/t5/crm-and-cx-q-a/2211-overriding-occ-a-controller-using-the-extension/qaq-p/12813498)
- [S5-L3] [SAP Community: ASMモードでカスタムマッピングが呼ばれない](https://community.sap.com/t5/crm-and-cx-q-a/asm-mode-not-calling-custom-request-mapping-for-ccv2-2211-42/qaq-p/14267735)
