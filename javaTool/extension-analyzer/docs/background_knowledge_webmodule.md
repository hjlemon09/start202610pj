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

## 다른 도구에서의 사용

spring-bean-analyzer-from-xml은 `extensions.csv`의 `web_module`을 읽어, **`web_module=true`인 extension만** 웹 쪽을 분석한다.

|대상|`web_module=true`|`web_module=false`|
|---|---|---|
|웹 컨텍스트(`web.xml`의 `contextConfigLocation` 등)|분석한다|분석하지 않는다|
|`web/webroot` 아래의 Spring XML 목록|포함한다|포함하지 않는다|
|`web/webroot` 아래의 JAR(`WEB_LIBRARY`)|색인한다|색인하지 않는다|

- `<webmodule>` 없이 `web.xml`만 남아 있으면 `spring-loading-issues.csv`에 `WEB_XML_WITHOUT_WEBMODULE`로 기록한다.
- `<webmodule>`은 있지만 `web.xml`이 없으면 `WEBMODULE_WITHOUT_WEB_XML`로 기록한다.
- 선택되지 않은 extension을 찾는 보조 검색(비선택 extension 검색)은 참고용이라, `web/webroot`의 JAR도 그대로 찾는다.

## 참고

- SAP Help — Extension Configurations (`extensioninfo.xml`의 구성 요소)
  https://help.sap.com/docs/SAP_COMMERCE/b490bb4e85bc42a7aa09d513d0bcb18e/8bbf6b5e86691014ad49fd1bc0172e5c.html
