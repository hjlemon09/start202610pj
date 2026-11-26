# 배경지식 — 웹 모듈은 `extensioninfo.xml`의 `<webmodule>`로 선언한다

SAP Commerce에서는 extension이 웹 애플리케이션을 가지는지를 **각 extension의 `extensioninfo.xml` 안의 `<webmodule>` 요소**로 명시한다.

```xml
<!-- bin/custom/mystorefront/extensioninfo.xml -->
<extensioninfo>
  <extension name="mystorefront">
    <coremodule .../>
    <webmodule jspcompile="true" webroot="/mystorefront"/>
  </extension>
</extensioninfo>
```

## 요점

- `<webmodule>`이 선언된 extension만 웹 애플리케이션으로 배포된다.
- `webroot` 속성은 URL의 **컨텍스트 경로**(예: `/mystorefront`)다. 폴더 위치가 아니다.
- 웹 애플리케이션의 파일(`web.xml`, JSP 등)은 extension 폴더의 **`web/webroot/`** 아래에 있다.
  - 예: `bin/custom/mystorefront/web/webroot/WEB-INF/web.xml`
- 같은 방식으로 코어 모듈은 `<coremodule>`로 선언한다.

## extension-analyzer에서

`ExtensionInfoParser`가 `<coremodule>`, `<webmodule>`과 그 `webroot` 속성을 읽는다. 결과는 `extensions.csv`의 다음 열에 나온다.

|열|내용|
|---|---|
|`core_module`|`<coremodule>` 선언 여부 (`true`/`false`)|
|`web_module`|`<webmodule>` 선언 여부 (`true`/`false`)|
|`webroot`|`<webmodule>`의 `webroot` 속성 값. 없으면 빈칸|

`extensions.csv`는 선택된 extension만 담는다.

## 다른 도구에서의 사용 (주의)

- spring-bean-analyzer-from-xml은 현재 이 `web_module` 값을 **보지 않는다.**
- 대신 `web/webroot/WEB-INF/web.xml` 파일이 있으면 그 extension에 웹 컨텍스트가 있다고 판단한다(`SpringLoadingAnalyzer`).
- 따라서 `<webmodule>` 선언 없이 `web.xml`만 남아 있는 extension도 웹 컨텍스트로 분석될 수 있다.
- SAP의 배포 규칙에 맞추려면 `web_module=true`인 extension만 웹 컨텍스트로 보는 것이 맞다.

## 참고

- SAP Help — Extension Configurations (`extensioninfo.xml`의 구성 요소)
  https://help.sap.com/docs/SAP_COMMERCE/b490bb4e85bc42a7aa09d513d0bcb18e/8bbf6b5e86691014ad49fd1bc0172e5c.html
