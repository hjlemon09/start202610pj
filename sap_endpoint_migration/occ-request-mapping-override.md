# [S0] OCC 표준 API의 같은 URL 재정의(@RequestMappingOverride)

[S0-L1] 작성일: 2026-11-27

[S0-L2] 이 문서는 이행 패턴 검토 문서(축약본)의 "F. 같은 URL 재정의" 패턴을 자세히 설명한다. 내용은 공개 예제와 SAP 커뮤니티의 보고를 기준으로 했다. 2211 소스로는 확인하지 않았다.

---

## [S1] 언제 쓰는가

- [S1-L1] 표준 OCC API의 URL과 요청·응답 계약은 그대로 두고, **컨트롤러 수준의 처리만 바꾸고 싶을 때** 쓴다.
- [S1-L2] 커스텀 OCC extension에 표준과 **완전히 같은 매핑**의 메서드를 만들고 `@RequestMappingOverride`를 붙이면, 그 URL의 요청이 표준 메서드 대신 커스텀 메서드로 간다.
- [S1-L3] 바꾸려는 것이 모든 채널에 필요한 업무 규칙이라면, 재정의보다 공유 계층(facade·service, Hook 등)의 확장이 더 맞다.

---

## [S2] 구체적 예시

[S2-L1] Accelerator의 장바구니 담기 컨트롤러에 있던 커스텀 확인(구매 수량 제한)을, 표준 OCC의 장바구니 항목 추가 API(`POST /{baseSiteId}/users/{userId}/carts/{cartId}/entries`)에 넣는 경우다. 코드는 형태를 보이기 위한 요약이다. 표준 메서드의 실제 시그니처와 처리는 2211 소스에서 확인한다.

### [S2.1] Accelerator(현행)

[S2.1-L1] 컨트롤러 안에서 확인한 뒤 표준 facade를 부른다.

[S2.1-L2] (코드 블록)
```java
@RequestMapping(value = "/cart/add", method = RequestMethod.POST)
public String addToCart(@RequestParam("productCodePost") String code, Model model,
                        @Valid AddToCartForm form, BindingResult bindingErrors) {
    if (!purchaseLimitFacade.canAdd(code, form.getQty())) {   // 커스텀 확인
        GlobalMessages.addErrorMessage(model, "basket.error.limit");
        return ControllerConstants.Views.Fragments.Cart.AddToCartPopup;
    }
    CartModificationData modification = cartFacade.addToCart(code, form.getQty());
    model.addAttribute("modifications", List.of(modification));
    return ControllerConstants.Views.Fragments.Cart.AddToCartPopup;
}
```

### [S2.2] OCC(재정의)

[S2.2-L1] 커스텀 OCC extension에 표준과 **완전히 같은 매핑**의 메서드를 만들고 `@RequestMappingOverride`를 붙인다.

[S2.2-L2] (코드 블록)
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
        if (!purchaseLimitFacade.canAdd(entry.getProduct().getCode(), entry.getQuantity())) {   // 옮겨 온 확인
            throw new CartException("purchase limit exceeded");   // 오류 응답으로 변환
        }
        // 이하 표준 메서드가 하던 처리를 여기서 다시 구현한다
        CartModificationData modification = cartFacade.addToCart(entry.getProduct().getCode(), entry.getQuantity());
        return getDataMapper().map(modification, CartModificationWsDTO.class, fields);
    }
}
```

[S2.2-L3] 이 예의 확인이 모든 채널에 필요한 업무 규칙이라면, 재정의보다 공유 계층의 확장(장바구니 담기 Hook 등)이 더 맞다.

---

## [S3] 재정의하면 원래 메서드는 쓸 수 없다

- [S3-L1] 같은 매핑의 요청은 모두 재정의한 메서드로 가고, 표준 메서드는 더 이상 호출되지 않는다.
- [S3-L2] 상속이 아니라 매핑을 바꿔 끼우는 방식이라 `super`로 표준 처리를 부를 수도 없다.
- [S3-L3] 그래서 표준 메서드가 하던 처리(입력 검증, 픽업 매장 처리, 응답 구성 등)는 재정의한 메서드 안에서 같은 facade를 불러 **다시 구현**해야 한다.
- [S3-L4] 업그레이드로 표준 메서드가 바뀌면 그 변경도 직접 반영해야 한다.

---

## [S4] 작성 규칙과 주의점

- [S4-L1] 어노테이션: `de.hybris.platform.commerceservices.request.mapping.annotation.RequestMappingOverride`.
- [S4-L2] 매핑이 **완전히 같아야** 한다. 경로 변수 이름만 달라도(`{productCode}`와 `{productId}`) 다른 매핑으로 취급된다. 이때는 기동 시가 아니라 **요청 시** "Ambiguous handler methods" 오류가 난다. 원래 `PUT, POST` 매핑의 `PUT`만 재정의하는 것도 안 된다.
- [S4-L3] 우선순위는 `priorityProperty`로 지정한 프로퍼티나 `requestMappingOverride.<클래스 FQN>.<메서드명>.priority`로 정한다. 값이 없으면 0이고, **가장 높은 값**이 쓰인다.
- [S4-L4] ASM 모드 등 특정 경로에서 재정의가 호출되지 않았다는 보고도 있다. 재정의한 API는 사용 경로별로 테스트한다.
- [S4-L5] 표준 메서드를 통째로 복사하게 되거나 매핑을 완전히 맞추기 어렵다면, 재정의 대신 새 URL의 신규 엔드포인트를 먼저 검토한다.

---

## [S5] 참고

- [S5-L1] [공개 예제: 2211 OCC extension과 @RequestMappingOverride (gist)](https://gist.github.com/sperez2-seidor/327f5db98d2d8368e3bb099e6a800929)
- [S5-L2] [SAP Community: 2211 OCC 컨트롤러 재정의](https://community.sap.com/t5/crm-and-cx-q-a/2211-overriding-occ-a-controller-using-the-extension/qaq-p/12813498)
- [S5-L3] [SAP Community: ASM 모드에서 커스텀 매핑 미호출](https://community.sap.com/t5/crm-and-cx-q-a/asm-mode-not-calling-custom-request-mapping-for-ccv2-2211-42/qaq-p/14267735)
