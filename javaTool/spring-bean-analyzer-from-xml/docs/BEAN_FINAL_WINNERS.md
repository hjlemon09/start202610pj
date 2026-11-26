# 빈 최종 승자 결정

## 목적

같은 빈 이름이나 alias가 여러 XML에 정의되어 있을 때, 컨텍스트마다 **실제로 남는 정의(최종 승자)**를 정한다.
SAP Commerce의 커스터마이즈는 대부분 "같은 id로 다시 정의" 또는 "같은 alias를 다른 빈에 다시 연결"하는 방식이라,
이 결정 없이는 어떤 클래스가 실제로 쓰이는지 알 수 없다.

판정 규칙은 저장소 루트의 [sap-commerce-spring-context-analyzer](../../../sap-commerce-spring-context-analyzer/) 절차서의
[2.1절](../../../sap-commerce-spring-context-analyzer/02-배포에-포함된-extension-집합-확인.md)(덮어쓰기 판단),
[7장](../../../sap-commerce-spring-context-analyzer/07-extension-사이의-로드-순서-확인.md)(extension 로드 순서),
[9.5~9.6절](../../../sap-commerce-spring-context-analyzer/09-XML-내부-profile과-import-처리-흐름-확인.md)(import 처리 순서)을 따른다.

## 1. 등록 순서 재생

Spring의 XML 리더가 빈을 등록하는 순서를 컨텍스트별로 재생한다.

1. **진입점 순서**: `spring-loading-entrypoints.csv`의 진입점을 선언 순서대로 처리한다.
2. **문서 순서**: 한 XML 안에서는 최상위 `<bean>`, `<alias>`, `<import>`, `util:*` 선언을 문서 순서대로 처리한다.
   - 내부 빈은 이름으로 등록되지 않으므로 제외한다.
   - `name` 속성의 나머지 이름은 그 빈의 alias로 같은 위치에 등록한다.
   - id와 name이 모두 없는 빈은 Spring이 이름을 생성하므로 이름 경쟁에서 제외한다.
3. **import 위치**: `<import>`를 만나면 그 자리에서 대상 문서를 끝까지 처리한 뒤 부모 문서의 다음 요소로 돌아간다.
   - 같은 파일을 다시 import하면 다시 등록한다(나중 위치가 된다).
   - 현재 처리 중인 파일로 다시 들어가는 import는 `IMPORT_CYCLE`로 기록하고 따라가지 않는다. 실제 Spring은 이 경우 오류를 낸다.
   - 해석하지 못한 import(속성식, 범위 밖 등)는 `IMPORT_NOT_FOLLOWED`로 기록한다.

같은 이름이 다시 등록되면 나중 등록이 앞의 등록을 덮어쓴다.

## 2. 순서를 "보장된 것"만 사용한다

두 등록 중 어느 쪽이 나중인지는 다음 규칙으로만 정한다.

| 두 등록의 관계 | 판단 |
| --- | --- |
| 같은 진입점, 같은 파일 경로 안 | 재생 순서 |
| 같은 진입점이지만 `classpath*:` 등 여러 후보 파일로 갈라짐 | **미확정**. 후보 파일 사이의 클래스로더 순서는 알 수 없음 |
| CORE/GLOBAL, 같은 extension의 다른 진입점 | 진입점 선언 순서(`application-context` 토큰 순서) |
| CORE/GLOBAL, 다른 extension | extension 부분 순서(아래) |
| WEB/SERVLET, `web.xml`의 위치끼리 | 선언 순서 |
| WEB, `web.xml` 위치 vs `additionalWebSpringConfigs` | `additionalWebSpringConfigs`가 나중(addon이 storefront 빈을 덮어쓰는 구조) |
| WEB, 서로 다른 `additionalWebSpringConfigs` 속성끼리 | **미확정** |

extension 부분 순서는 extension-analyzer가 계산해 `extension-load-order.csv`로 내보낸 것을 읽는다(`ExtensionOrder`). 그 파일은 SAP Help 「Extension Dependencies」가 보장하는 관계만 담는다.

- B가 `requires-extension` A → A가 먼저 (전이적으로)
- A가 `bin/platform/ext` 아래이고 B는 아님 → A가 먼저 (플랫폼 extension에 대한 암묵적 의존)
- A의 `<meta key="load-prior-to" value="B"/>` → A가 먼저
- 그 밖의 쌍은 **순서 미확정**이다. 이름순이나 폴더순으로 임의로 정하지 않는다.
- requires/load-prior-to가 순환하면 그 extension들 사이는 미확정이고 `EXTENSION_ORDER_CYCLE`로 기록한다.

## 3. 승자 판정

같은 컨텍스트·같은 이름의 등록들 중, **다른 어떤 등록보다도 앞이라고 확정되지 않은 등록**들(최대 원소)을 구한다.

| 상태 | 조건 |
| --- | --- |
| `CONFIRMED` | 최대 원소가 모두 같은 정의(같은 declaration key, alias는 같은 대상) |
| `UNDETERMINED_ORDER` | 최대 원소가 서로 다른 정의. 승자 열은 비우고 후보 열에 모두 적는다 |
| `PROFILE_DEPENDENT` | 승자가 `<beans profile>` 안의 정의. profile이 꺼져 있을 때의 승자를 `profile_fallback_declaration_key`에 적는다 |
| `RESOURCE_CHOICE_UNCERTAIN` | 승자가 `AMBIGUOUS_CLASSPATH`(같은 이름의 `classpath:` 후보가 여럿) 경로로 등록됨 |

활성 profile은 평가하지 않는다.

## 4. 최종 참조 연결과 부모 컨텍스트

`spring-bean-final-references.csv`는 `ref`, `parent`, `depends-on` 등의 명시 참조를 Spring의 조회 방식대로 따라간다.

1. 현재 컨텍스트의 alias 승자를 먼저 따라간다(alias chain).
2. 그 이름의 빈 승자가 현재 컨텍스트에 있으면 연결한다.
3. 없으면 **원래 이름으로** 부모 컨텍스트에서 다시 찾는다.

부모 관계는 SAP 표준 구성을 가정한 모델이다(실행 중 확인한 값이 아님).

```text
SERVLET:ext:name -> WEB:ext -> CORE -> GLOBAL
```

`owner_status`는 참조를 가진 정의가 그 컨텍스트의 승자인지(`WINNER`), 덮어써졌는지(`OVERRIDDEN`) 보여 준다.
덮어써진 정의의 참조는 실제로는 쓰이지 않는다.

같은 이름이 alias이면서 빈 이름이기도 하면 `NAME_IS_ALIAS_AND_BEAN`으로 기록하고 alias를 먼저 따른다.
Spring 버전에 따라 등록 시점에 alias를 지우는 동작이 있어 결과가 달라질 수 있다.

## 5. 실효 클래스

`spring-bean-winners.csv`의 `effective_class`는 승자 정의의 `class`이다.
`class`가 없으면 `parent` 이름을 같은 방식(alias, 부모 컨텍스트 포함)으로 찾아 상속된 클래스를 적는다(`INHERITED via ...`).
`factory-method`가 있으면 반환 타입을 알 수 없으므로 `FACTORY_METHOD`로 둔다.

## 한계

- 코드로 등록하는 빈(`BeanDefinitionRegistryPostProcessor`, `@Configuration`, component-scan)은 포함하지 않는다. 소스 분석 도구의 범위다.
- 진입점·import 해석의 한계(`spring-loading-issues.csv`, `spring-import-issues.csv`)가 그대로 승자 판정의 한계가 된다.
- 여러 테넌트, 실제 클래스로더 순서, 런타임 속성 치환은 재현하지 않는다.
- 정확도는 실제 서버의 빈 목록(HAC 스크립팅 콘솔 등)과 대조해 확인해야 한다.

## 검증

`test/com/sapcommerce/analyzer/SpringBeanWinnerResolverTest.java`
- 순서표에 있는 쌍과 없는 쌍(미확정)의 구분. 순서 계산 자체는 extension-analyzer의 `ExtensionAnalyzerTest`
- import 위치에 따른 덮어쓰기, `classpath*:` 후보 사이의 미확정
- profile 승자와 fallback, alias 재연결, parent 상속 클래스
- addon(`additionalWebSpringConfigs`)이 web.xml 정의를 덮어쓰는 경우
- web 컨텍스트에서 CORE로의 부모 조회, 덮어써진 정의의 참조 표시
