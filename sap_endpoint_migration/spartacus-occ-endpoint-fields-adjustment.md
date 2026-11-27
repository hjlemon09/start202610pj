# [S0] Composable Storefront(Spartacus)에서 OCC endpoint의 fields를 조정하는 방법

[S0-L1] 작성일: 2026-11-27

[S0-L2] 이 문서는 이행 패턴 검토 문서(원본·축약본)의 `S3.3-L1`에서 말한 "필요하면 endpoint `fields` 조정"을 구체적으로 설명한다. 대상은 표준 OCC API를 그대로 쓰되, 응답에 포함될 필드를 바꾸고 싶은 경우다.

[S0-L3] 설정 키와 기본값은 Spartacus 공개 소스(`develop` 브랜치)로 확인했다. 실제로 쓰는 Spartacus 버전의 기본 설정 파일로 다시 확인해야 한다.

---

## [S1] fields가 응답을 정하는 구조

[S1-L1] OCC API는 요청의 `fields` 파라미터에 따라 응답에 넣을 필드를 정한다. 지정 방법은 세 가지다.

[S1-T1] (표)

| 지정 방법 | 예 | 응답에 들어가는 필드 |
|---|---|---|
| 레벨 | `fields=DEFAULT` | 서버의 FieldSetLevelMapping에 정의된 그 레벨(BASIC/DEFAULT/FULL)의 필드 |
| 명시 목록 | `fields=code,name,price(formattedValue)` | 나열한 필드만. 중첩 객체는 괄호 안에 다시 지정 |
| 레벨 + 명시 | `fields=DEFAULT,averageRating,images(FULL)` | 레벨의 필드에 나열한 필드를 더함 |

- [S1-L2] `fields`를 지정하지 않으면 서버 기본값(일반적으로 `DEFAULT`)이 쓰인다.
- [S1-L3] Spartacus는 endpoint마다 `fields`가 들어간 URL 템플릿을 설정으로 가진다. 그래서 프런트엔드에서 필드를 바꾸는 작업은 **이 URL 템플릿의 `fields` 부분을 고치는 일**이다.

---

## [S2] 먼저 판단할 것: 프런트엔드만 바꾸면 되는가

[S2-T1] (표)

| 상황 | 할 일 |
|---|---|
| 필요한 필드가 이미 WsDTO에 있고, Spartacus가 그 필드를 요청하지 않을 뿐이다 | **프런트엔드의 fields 설정만 바꾼다**(아래 "설정을 넣는 위치와 방법" 이후) |
| WsDTO에 필드가 없다 | 백엔드에서 WsDTO 필드를 추가한 뒤 fields 설정을 바꾼다(이행 패턴 검토 문서의 응답 항목 추가 절) |
| endpoint가 레벨(DEFAULT 등)로만 요청하고, 그 레벨에 필드가 없다 | 프런트엔드에서 명시 필드를 덧붙이거나, 서버의 FieldSetLevelMapping에서 레벨에 필드를 추가한다 |

[S2-L1] 어느 쪽인지는 실제 응답으로 확인한다.
- [S2-L2] 브라우저 개발자 도구의 네트워크 탭에서 Spartacus가 보내는 요청 URL의 `fields`를 본다.
- [S2-L3] 같은 URL의 `fields`를 `FULL`로 바꿔 직접 호출해 보면, 그 필드가 WsDTO에 있는지 알 수 있다.

---

## [S3] 설정을 넣는 위치와 방법

### [S3.1] provideConfig로 endpoint를 덮어쓴다

[S3.1-L1] Spartacus 앱 모듈(예: 스키매틱이 만든 `spartacus-configuration.module.ts`)의 `providers`에 `provideConfig`로 `OccConfig`를 넣는다.

[S3.1-L2] (코드 블록)
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

### [S3.2] 주의: 문자열은 통째로 바뀐다

- [S3.2-L1] Spartacus 설정은 객체 단위로 합쳐지지만, endpoint 값인 **문자열은 덧붙여지지 않고 통째로 대체된다**.
- [S3.2-L2] 그래서 필드 하나를 추가하려 해도 **기본 문자열 전체를 복사한 뒤 끝에 추가**해야 한다. 위 예의 `details`는 기본값 끝에 `originCountry`만 더한 것이다.
- [S3.2-L3] 기본 문자열은 버전마다 다르다. 사용하는 버전의 기본 설정 파일에서 복사한다(아래 "기본 설정 파일 위치").
- [S3.2-L4] Spartacus를 업그레이드할 때 기본 문자열이 바뀌었는지 비교해야 한다. 덮어쓴 endpoint 목록을 관리해 두는 것을 권한다.

### [S3.3] URL 템플릿의 변수

[S3.3-L1] `${productCode}`, `${userId}`, `${cartId}` 같은 자리 표시자는 그대로 둔다. Spartacus가 호출할 때 값을 채운다.

---

## [S4] endpoint 종류별 조정 방법

### [S4.1] 상품: scope별로 나뉘어 있다

[S4.1-L1] 상품 endpoint는 `backend.occ.endpoints.product` 아래에 **scope별 문자열**로 나뉘어 있다.

[S4.1-T1] (표)

| scope | 용도 | 기본 fields 형태 |
|---|---|---|
| `default` | scope를 지정하지 않은 조회 | `DEFAULT,...` (레벨 + 명시) |
| `list` | 목록·카드 표시 | 명시 목록 |
| `details` | 상품 상세 | 명시 목록 |
| `attributes` | 분류 속성 | `classifications` |
| `price`, `stock`, `promotions`, `list_item` 등 | 부분 조회 | 명시 목록 |

- [S4.1-L2] 상품 상세 화면은 여러 scope를 함께 불러온다. 기본 설정에서는 `loadingScopes`로 `details`가 `list`, `variants`를 포함한다.
- [S4.1-L3] 새 필드를 **어느 화면에서 쓰는지**에 맞춰 scope를 고른다. 목록에서도 쓰면 `list`에, 상세에서만 쓰면 `details`에 넣는다.
- [S4.1-L4] 상품 데이터는 scope 단위로 캐시된다. 엉뚱한 scope에 넣으면 그 화면에서는 값이 비어 보인다.

[S4.1-L5] **새 scope를 만드는 방법** (기존 scope를 건드리지 않고 필드를 따로 받고 싶을 때)

[S4.1-L6] (코드 블록)
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

- [S4.1-L7] 컴포넌트에서는 `ProductService.get(productCode, 'origin')`으로 그 scope만 불러올 수 있다.
- [S4.1-L8] 위처럼 `details`의 `include`에 추가하면 상세 화면을 열 때 함께 불러온다. `include`도 배열이라 통째로 바뀌므로, 기본값(`list`, `variants`)을 함께 적는다.

### [S4.2] 상품 검색

[S4.2-L1] `productSearch.default` 등도 명시 목록이다. 상품 필드는 `products(...)` 괄호 **안에** 추가한다.

[S4.2-L2] (코드 블록)
```text
products/search?fields=products(code,name,...,baseProduct,badges),facets,breadcrumbs,...
                                                  ↑ 여기에 추가
```

[S4.2-L3] 검색 결과의 필드는 상품 상세와 다른 Converter로 채워진다. 백엔드의 검색 결과 쪽에도 값이 있는지 확인한다.

### [S4.3] 장바구니: 상위는 레벨, 항목은 명시 목록

[S4.3-L1] 장바구니 endpoint(`cart`, `carts`)의 기본값은 다음 형태다.

[S4.3-L2] (코드 블록)
```text
users/${userId}/carts/${cartId}?fields=DEFAULT,potentialProductPromotions,...,
  entries(totalPrice(formattedValue),product(images(FULL),stock(FULL)),basePrice(formattedValue,value),updateable),
  totalPrice(formattedValue),...
```

[S4.3-T1] (표)

| 추가할 필드의 위치 | 방법 |
|---|---|
| 장바구니 자체(`CartWsDTO`) | 서버에서 DEFAULT 레벨에 넣으면 프런트 설정 변경 없이 들어온다. 또는 문자열 끝에 명시 필드를 추가 |
| 장바구니 항목(`OrderEntryWsDTO`) | **`entries(...)` 괄호 안에 추가해야 한다.** entries는 명시 목록이라 서버의 DEFAULT 레벨에 넣어도 들어오지 않는다 |
| 항목 안의 상품(`ProductWsDTO`) | `entries(...product(...))`의 `product(...)` 괄호 안에 추가 |

- [S4.3-L3] 예: 항목에 `giftWrap`을 추가하면 `entries(totalPrice(formattedValue),...,updateable,giftWrap)`가 된다.
- [S4.3-L4] `cart`와 `carts`(목록)는 별도 문자열이다. 두 곳에서 모두 쓰면 둘 다 고친다.

### [S4.4] 주문·사용자

[S4.4-T1] (표)

| endpoint | 기본 fields | 조정 방법 |
|---|---|---|
| 주문 상세 `orderDetail`, 주문 확정 `placeOrder` | `FULL` | 서버의 FULL 레벨에 있으면 그대로 들어온다. 없으면 `FULL,추가필드`로 바꾼다 |
| 주문 내역 `orderHistory` | 지정 없음 | 서버 기본값(DEFAULT)에 따른다. 바꾸려면 `?fields=...`를 붙인 문자열로 덮어쓴다 |
| 사용자 `user` | 지정 없음 | 위와 같다 |

---

## [S5] 받은 필드를 화면에서 쓰기

### [S5.1] TypeScript 타입 확장

[S5.1-L1] 응답에 필드가 들어와도 Spartacus의 모델 타입에는 없으므로, 모듈 확장(module augmentation)으로 타입에 추가한다.

[S5.1-L2] (코드 블록)
```ts
// 상품
declare module '@spartacus/core' {
  interface Product {
    originCountry?: string;
  }
}

// 장바구니 항목
declare module '@spartacus/cart/base/root' {
  interface OrderEntry {
    giftWrap?: boolean;
  }
}
```

- [S5.1-L3] 인터페이스가 정의된 패키지 이름은 버전에 따라 다를 수 있다. 사용하는 버전에서 해당 인터페이스를 export하는 패키지를 확인한다.

### [S5.2] Normalizer는 보통 필요 없다

- [S5.2-L1] 표준 Converter는 OCC 응답의 필드를 대부분 그대로 넘긴다. 그래서 단순한 값은 타입만 확장하면 쓸 수 있다.
- [S5.2-L2] 값의 형태를 바꿔야 할 때(날짜 변환, 코드 → 표시명 등)만 커스텀 Normalizer를 추가한다.

### [S5.3] 표시

- [S5.3-L1] 표시할 컴포넌트를 커스터마이즈하거나(CMS 컴포넌트 매핑 교체), 기존 컴포넌트의 outlet에 끼워 넣는다.

---

## [S6] 작업 순서와 확인

[S6-L1] (코드 블록)
```text
1. 네트워크 탭에서 대상 화면이 호출하는 endpoint 키와 현재 fields를 확인
2. fields=FULL로 직접 호출해 필드가 WsDTO에 있는지 확인 (없으면 백엔드 작업이 먼저)
3. 사용하는 Spartacus 버전의 기본 설정 파일에서 해당 endpoint 문자열을 복사
4. 필드를 올바른 위치(최상위 / entries(...) / products(...) / product(...))에 추가
5. provideConfig로 덮어쓰기. 상품이면 scope 선택 또는 새 scope + loadingScopes
6. 타입 확장(module augmentation), 필요하면 Normalizer
7. 네트워크 탭에서 요청 fields와 응답에 필드가 들어왔는지 확인
8. 같은 endpoint를 쓰는 다른 화면에서 응답 크기·표시에 문제가 없는지 확인
```

### [S6.1] 기본 설정 파일 위치 (`develop` 브랜치 기준)

[S6.1-T1] (표)

| endpoint | 파일 |
|---|---|
| 상품, 상품 검색, 상품 레퍼런스 | `core-libs/core/src/occ/adapters/product/default-occ-product-config.ts` |
| 장바구니 | `feature-libs/cart/base/occ/config/default-occ-cart-config-factory.ts` |
| 주문 | `feature-libs/order/occ/config/default-occ-order-config.ts` |
| 사용자 계정·프로필 | `feature-libs/user/account/occ/adapters/config/`, `feature-libs/user/profile/occ/adapters/config/` |

[S6.1-L1] 설치된 앱에서는 `node_modules/@spartacus/...` 아래의 빌드된 파일에서 같은 기본값을 찾을 수 있다.

---

## [S7] 주의할 점

- [S7-L1] **응답 크기:** 상품 `list`처럼 자주 호출되는 scope나 장바구니에 무거운 필드를 넣으면 모든 화면의 응답이 커진다. 무거운 값은 별도 scope로 분리한다.
- [S7-L2] **권한:** 익명으로 호출되는 endpoint(상품 등)에 민감한 필드를 요청하지 않는다. 서버가 응답하면 누구나 볼 수 있다.
- [S7-L3] **존재하지 않는 필드:** WsDTO에 없는 필드를 fields에 적으면 응답에 나오지 않을 뿐이다. 오타를 알아차리기 어려우므로 작업 순서의 7번 확인을 꼭 한다.
- [S7-L4] **업그레이드:** 덮어쓴 endpoint는 Spartacus 기본값의 변경을 자동으로 따라가지 않는다. 업그레이드 때 기본 문자열을 다시 비교한다.

## [S8] 참고

- [S8-L1] [Spartacus: Loading Scopes](https://sap.github.io/spartacus-docs/loading-scopes/)
- [S8-L2] [Spartacus default-occ-product-config.ts](https://github.com/SAP/spartacus/blob/develop/core-libs/core/src/occ/adapters/product/default-occ-product-config.ts)
- [S8-L3] [Spartacus default-occ-cart-config-factory.ts](https://github.com/SAP/spartacus/blob/develop/feature-libs/cart/base/occ/config/default-occ-cart-config-factory.ts)
- [S8-L4] [Spartacus default-occ-order-config.ts](https://github.com/SAP/spartacus/blob/develop/feature-libs/order/occ/config/default-occ-order-config.ts)
