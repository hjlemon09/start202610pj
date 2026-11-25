# SAP Commerce 2211 Accelerator에서 OCC API를 사용하기 위한 extension 구성

질문: 현행 시스템은 SAP Commerce 2211 + Accelerator(JSP 스토어프론트)이다. 이 시스템에서 OCC API를 사용하려면 어떤 extension을 활성화해야 하는가? extension 자체는 모두 확보한 상태라고 가정한다.

## 1. 결론

`localextensions.xml`에 **`commercewebservices`**를 추가하고, OAuth 토큰 발급 서버가 함께 로드되는지 확인한다. 토큰 서버를 제공하는 extension은 2211의 JDK 트랙에 따라 다르다.

| 구분 | extension | 역할 |
|---|---|---|
| 필수 | `commercewebservices` | OCC REST API 본체. `/occ/v2/{baseSiteId}/...` 엔드포인트(상품, 장바구니, 주문, 사용자 등)를 제공한다. |
| 필수 (2211 JDK 17 트랙) | `oauth2` | `/authorizationserver/oauth/token` 토큰 엔드포인트를 제공한다. 기본 webroot는 `authorizationserver`. |
| 필수 (2211-jdk21 트랙) | `authorizationserver`, `resourceserver`, `oauth2commons` | 2211-jdk21 업데이트 릴리스에서 `oauth2`를 대체한 신규 OAuth 구현. 토큰 엔드포인트 경로는 동일하게 `/authorizationserver/oauth/token`이다. |

```xml
<!-- 2211 (JDK 17) -->
<extension name="commercewebservices"/>
<extension name="oauth2"/>

<!-- 2211-jdk21 업데이트 릴리스를 사용하는 경우: oauth2 대신 -->
<extension name="authorizationserver"/>
<extension name="resourceserver"/>
<extension name="oauth2commons"/>
```

- `webservicescommons`, `commercewebservicescommons` 같은 의존 extension은 `extensioninfo.xml`의 `requires-extension`에 따라 자동으로 로드된다. OAuth 관련 extension도 의존 관계로 이미 들어와 있을 수 있지만, 명시해 두면 구성이 분명해진다.
- 2211-jdk21 마이그레이션 가이드는 신규 OAuth extension이 "part of the default configuration"이라고 설명한다. 실제로 어느 트랙인지는 설치본의 `bin/modules`에 `oauth2`와 `authorizationserver` 중 어느 쪽이 있는지로 확인한다.

## 2. 사용하지 말아야 할 방식

- **`ycommercewebservices` 템플릿으로 extgen하는 방식**: 2205부터 deprecated 상태다. Deprecation Status 표에는 삭제 예정 시점이 earliest Q3 2027로 적혀 있다. 아직 제거되지는 않았지만 신규 구축에는 쓰지 않는다.
- **AddOn 기반 OCC v2 확장**(예: `acceleratorwebservicesaddon`처럼 `ycommercewebservices`에 addon으로 붙이는 방식): SAP는 OCC extension 방식으로 이행하라고 안내한다.
- **권장 방식**: `commercewebservices`는 그대로 쓴다. 커스텀 엔드포인트는 `yocc` 템플릿으로 extgen한 `xxxocc` extension(이름이 `occ`로 끝나야 함)에 작성한다. 이렇게 만든 extension은 `commercewebservices`에 의존하며, 엔드포인트는 `/occ/v2/{baseSiteId}/...` 아래에 추가된다.

## 3. 기능별 선택 extension

표준 OCC 외의 기능을 OCC로 노출하려면 해당 모듈의 OCC/webservices extension을 추가한다. 아래 이름은 일반적인 예시이며, 이 문서를 작성하면서 공식 문서로 확인하지는 않았다. 설치본의 `bin/modules` 아래에서 `*occ*`, `*webservices*`로 실제 이름을 확인한다.

- CMS 페이지/컴포넌트: `cmsocc`
- ASM(Assisted Service): `assistedservicewebservices`
- 개인화: `personalizationwebservices`
- B2B Accelerator: `b2bocc` 등 B2B 전용 OCC extension. B2C와 B2B OCC를 함께 쓸 때는 `occ.rewrite.overlapping.paths.enabled` 설정도 확인한다.

## 4. 활성화 후 작업

1. **빌드와 시스템 업데이트**: `ant clean all`을 실행한 뒤 HAC → Platform → Update에서 추가한 extension의 essential/project data를 반영한다.
2. **OAuth 클라이언트 등록**: `OAuthClientDetails`를 ImpEx로 등록한다. 등록하지 않으면 토큰을 발급받을 수 없다. SAP 설치 가이드의 예시는 아래와 같고, `mobile_android`와 `secret`은 반드시 다른 값으로 바꾼다.

   ```impex
   INSERT_UPDATE OAuthClientDetails;clientId[unique=true];resourceIds;scope;authorizedGrantTypes;authorities;clientSecret;registeredRedirectUri
   ;mobile_android;hybris;basic;authorization_code,refresh_token,password,client_credentials;ROLE_CLIENT;secret;http://localhost:9001/authorizationserver/oauth2_callback;
   ```

   2211-jdk21 트랙에서는 `OAuthClientDetails`에 `authenticationMethods`, `requireProofKey`, `public` 같은 속성이 추가되었다. `resourceIds`는 deprecated되었고, redirect URI는 절대 URI여야 한다.
3. **토큰 발급 확인**:

   ```bash
   curl -k -d "client_id=mobile_android&client_secret=secret&grant_type=client_credentials" \
     -X POST https://localhost:9002/authorizationserver/oauth/token
   ```

4. **OCC 호출 확인**: 아래 주소가 응답하면 OCC가 동작하는 것이다.
   - Swagger: `https://localhost:9002/occ/v2/swagger-ui.html`
   - BaseStore 조회: `https://localhost:9002/occ/v2/{baseSiteId}/basestores/{baseStoreId}`
5. **CORS 설정**: 브라우저에서 다른 오리진(별도 프론트엔드)이 호출하는 경우 `local.properties`에 설정한다. 기본값은 `http://localhost:4200 https://localhost:4200`이다. 샘플 설정의 와일드카드(`*`)는 운영 환경에서 쓰지 않는다.

   ```properties
   corsfilter.commercewebservices.allowedOrigins=https://your-front.example.com
   ```

## 5. 기존 Accelerator 스토어프론트에 대한 영향

OCC는 같은 Facade/Service 레이어 위에 REST 계층을 하나 더 얹는 구조다. 기존 Accelerator(JSP) 스토어프론트는 그대로 함께 동작한다. 실제로 SAP의 Composable Storefront 설치 가이드도 `cx` 레시피로 Accelerator 스토어프론트(`yacceleratorstorefront`)와 OCC를 같은 서버에서 함께 띄운다.

## 참고 자료 (SAP 공식)

- [Installing SAP Commerce Cloud 2211 for use with Composable Storefront][sap-install] — OCC 사용 전제(`commercewebservices`), OAuth 클라이언트 ImpEx, 토큰 curl, Swagger URL, CORS 주의사항
- [OCC Extension-Based Architecture][sap-occ-arch] / [Creating an OCC Extension][sap-occ-create] — `yocc` 템플릿, `commercewebservices` 의존 구조
- [Guidelines for Migrating AddOn-Based OCC v2 Extensions to OCC Extensions][sap-occ-migrate] — AddOn 기반 OCC와 `ycommercewebservices`에서 이행
- [Deprecation Status][sap-deprecation] — `ycommercewebservices` deprecated(2205), 삭제 예정 시점
- [OAuth2 (oauth2 extension)][sap-oauth2] — `oauth2` extension과 `authorizationserver` webroot (2205 문서. 2211 JDK 17 트랙도 같은 구조)
- [Moving to the New OAuth Implementation][sap-oauth-new] — 2211-jdk21에서 `oauth2`를 `authorizationserver`/`resourceserver`/`oauth2commons`로 대체
- [Authorization Server][sap-authserver] — `/authorizationserver/oauth/token` 등 엔드포인트 목록 (JDK 21)
- [Cross-Origin Resource Sharing Support][sap-cors] — CORS 설정
- [Extensions and AddOns][sap-ext] — `commercewebservices` (2005부터 제공)

[sap-install]: https://help.sap.com/docs/SAP_COMMERCE_COMPOSABLE_STOREFRONT/cfcf687ce2544bba9799aa6c8314ecd0/6a04941777e242508bdd1dc395a15553.html
[sap-occ-arch]: https://help.sap.com/docs/SAP_COMMERCE_CLOUD_PUBLIC_CLOUD/3476714bba0b4cb9b3eb58c270e44439/d46d19516961438f8939718e87ed787b.html
[sap-occ-create]: https://help.sap.com/docs/SAP_COMMERCE_CLOUD_PUBLIC_CLOUD/3476714bba0b4cb9b3eb58c270e44439/8ef2d8d7baf1443dbed3affb49606adf.html
[sap-occ-migrate]: https://help.sap.com/docs/SAP_COMMERCE_CLOUD_PUBLIC_CLOUD/3476714bba0b4cb9b3eb58c270e44439/8642ddacc8e0494e8b4f5515722e0412.html
[sap-deprecation]: https://help.sap.com/docs/SAP_COMMERCE_CLOUD_PUBLIC_CLOUD/12be4ac419604b01aabb1adeb2c4c8a2/8bb15ed586691014a948d1553f4947cf.html
[sap-oauth2]: https://help.sap.com/docs/SAP_COMMERCE/d0224eca81e249cb821f2cdf45a82ace/3d3ea6a4d5fa486aa324ce278fa2afc3.html
[sap-oauth-new]: https://help.sap.com/docs/SAP_COMMERCE_CLOUD_PUBLIC_CLOUD/75d4c3895cb346008545900bffe851ce/d079f886cab647e5a0555e2cae8e4416.html
[sap-authserver]: https://help.sap.com/docs/SAP_COMMERCE_CLOUD_PUBLIC_CLOUD/aa417173fe4a4ba5a473c93eb730a417/c09682f2f14c4172932450f4d32a9e87.html
[sap-cors]: https://help.sap.com/docs/SAP_COMMERCE_CLOUD_PUBLIC_CLOUD/ddc1ef9fc7fa48cb90f2b57c061a4012/b27d995150a74be08869e60e3fbc7395.html
[sap-ext]: https://help.sap.com/docs/SAP_COMMERCE/c5613bd3cc9942efb74d017b40eb0892/8bc0c14886691014a0d4f274a720596f.html
