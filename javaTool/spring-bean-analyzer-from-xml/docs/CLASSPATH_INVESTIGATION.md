# 클래스패스 구성 근거 조사 — 1차

## 조사 계기

로딩 진입점 분석에서 application-context 속성 6건의 대상 경로를 찾지 못했다.
이 중 acceleratorstorefrontcommons-spring.xml은 사용자가 아래 위치에서 발견했다.

```text
hybris/bin/modules/core-accelerator/deprecated/acceleratorstorefrontcommons/
  resources/acceleratorstorefrontcommons/web/spring/acceleratorstorefrontcommons-spring.xml
```

현재 분석기의 보고서 경로는 classpath:acceleratorstorefrontcommons-spring.xml이었다.
분석기는 Extension의 resources를 기준으로 상대 경로를 비교하므로 두 경로를 같은 것으로
취급하지 않는다. 이는 분석기의 모델이며 대상 Hybris의 실제 클래스패스 구성을 확인한 사실은 아니다.
파일명만 같다고 하위 경로를 자동 연결하면 잘못된 근거를 만들 수 있어 자동 보정을 하지 않는다.

사용자와 합의한 조사 순서:

1. 빌드/시작 설정 및 클래스 로더 구현에서 폴더와 JAR 등록 근거를 찾는다.
2. resources 하위 폴더도 별도 기준으로 등록하는지 확인한다.
3. 근거를 분석기에 반영한다.
4. 필요한 경우에만 실행 환경의 조회 결과와 대조한다.

HAC/디버깅은 1단계의 필수 작업이 아니다. 우선 정적 설정과 구현을 조사한다.

## 로컬에서 확인한 범위

현재 작업 PC에는 C:/hybris 및 Git 저장소 내 Hybris 설치 소스가 없었다.
따라서 개발 환경의 실제 SAP 소스/설정 본문은 아직 확인하지 못했다.
이번 조사에서는 공식 문서/API로 담당 클래스와 메서드를 좁혔다.

## 공식 자료로 확인한 담당 구성

### 플랫폼 리소스 로더

```text
de.hybris.bootstrap.loader.PlatformInPlaceClassLoader
```

공식 API에서 확인한 메서드:

```text
getInPlaceURLs(PlatformConfig config, String additionalClasspath)
getInPlaceURLs(String platformHome, String additionalClasspath)
getInPlaceURLs()
```

생성자에도 PlatformConfig 및 additionalClasspath 인자가 있다.
메서드 본문에서 Extension의 어떤 리소스/클래스/JAR 경로를 URL로 등록하는지 확인해야 한다.
현재는 실제 대상 버전의 메서드 본문과 호출 관계를 확인하지 않았으므로 등록 경로 규칙을 확정하지 않는다.

### 클래스패스 구성 관련 클래스

공식 bootstrap.loader 패키지 목록에 HybrisClasspathBuilder가 있다.
PlatformInPlaceClassLoader가 URL 구성 작업을 위임하는지 실제 메서드 본문으로 확인한다.
클래스 이름만으로 해당 위임 관계까지 확정한 것은 아니다.

### 웹 로더 연결

```text
de.hybris.tomcat.HybrisWebappLoader
```

공식 API 설명은 PlatformInPlaceClassLoader를 표준 webapp class loader 체인에 연결하는 클래스라고 명시한다.
플랫폼 클래스패스와 웹 전용 클래스패스를 구분해야 한다.

### Extension 경로 정보

```text
de.hybris.bootstrap.config.ExtensionInfo.getResourcesDirectory()
```

공식 API에 리소스 디렉터리 조회 메서드가 있다. 이 메서드가 클래스패스 구성 과정에서
어떻게 사용되는지는 호출부와 구현으로 확인해야 한다.

공식 Extension Components 문서는 Extension의 lib와 web/webroot/WEB-INF/lib의
가시성 차이를 설명한다. 전자는 Core/ServiceLayer 범위, 후자는 해당 웹 애플리케이션 범위다.
이 사실만으로 resources 하위 폴더가 추가 클래스패스 루트인지까지 판단할 수는 없다.

## 개발 환경에서 다음으로 확인할 부분

Eclipse Ctrl+Shift+T로 PlatformInPlaceClassLoader를 열고
getInPlaceURLs(PlatformConfig, String) 메서드 본문을 확인한다.
다른 구성 클래스에 위임한다면 해당 호출과 메서드를 이어서 본다.

확인할 사항:

- Extension 목록을 어디서 받는가
- resources 경로를 어떤 방식으로 추가하는가
- resources 하위 디렉터리를 각각 추가하는가, 루트만 추가하는가
- 클래스 디렉터리와 lib JAR는 어떤 방식으로 추가하는가
- additionalClasspath는 어디서 전달되는가

우선 해당 메서드 본문만 공유받아 실제 코드 근거를 확인한다.
소스가 제공되지 않고 class 파일만 열리면 그 상태를 확인한 뒤 JAR 위치/소스 첨부 여부를 조사한다.
원본 Hybris 설정이나 분석기 검색 규칙은 이 조사 단계에서 변경하지 않았다.

## 공식 근거

- PlatformInPlaceClassLoader API: https://help.sap.com/doc/02d5152884b34821a06408495ba0b771/1905/en-us/de/hybris/bootstrap/loader/PlatformInPlaceClassLoader.html
- bootstrap.loader 패키지: https://help.sap.com/doc/9fef7037b3304324b8891e84f19f2bf3/2211/en-us/de/hybris/bootstrap/loader/package-summary.html
- HybrisWebappLoader API: https://help.sap.com/doc/02d5152884b34821a06408495ba0b771/1905/en-us/de/hybris/tomcat/HybrisWebappLoader.html
- ExtensionInfo API: https://help.sap.com/doc/5981aa9b2c424a6cabec5906d38c7875/2211.8/en-US/de/hybris/bootstrap/config/ExtensionInfo.html
- Extension Components: https://help.sap.com/docs/SAP_COMMERCE/b490bb4e85bc42a7aa09d513d0bcb18e/3a3b92d4900b4b3685157b806a73eab2.html

자료의 버전은 서로 다를 수 있다. 현재 설치 버전의 실제 코드가 최종 검증 근거이며,
API 목록은 조사 위치를 좁히는 데 사용했다.
