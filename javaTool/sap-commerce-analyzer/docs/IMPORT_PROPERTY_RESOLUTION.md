# import 속성 표현식 해석 작업 기록

## 작업 전 확인한 상태

사용자의 최신 개발 환경 실행 화면에서 import 선언 276개, 문제 7건이었다.
구성은 FOUND_IN_UNSELECTED_EXTENSION 2건, UNRESOLVED_PROPERTY 4건, XML_INSPECTION_ERROR 1건이었다.
미발견 2건은 보조 검색으로 personalizationcmsweb 및 swaggerintegration에서 확인됐지만,
소유 Extension의 NOT_SELECTED 상태와 실행 가시성 미확인 상태는 유지했다.

이번 작업의 대상은 아래처럼 속성값에 따라 import 경로를 바꾸는 4건이다.

```text
${adaptivesearchwebservices.security.config:config/security-spring.xml}
${permissionswebservices.security.config:config/security-spring.xml}
${personalizationwebservices.security.config:config/security-spring.xml}
${previewwebservices.security.config:config/security-spring.xml}
```

원문은 사용자가 공유한 실행 화면에서 확인한 형식이다. 실제 설정값을 로컬에서 읽은 것은 아니다.

## 기존 문제

이전 분석기는 resource 안에 `${` 또는 `#{`가 있으면 경로 검색을 시도하지 않고
UNRESOLVED_PROPERTY로 처리했다. `${키:기본값}`을 해석하지 않아 기본 경로로 찾을 수 있는
파일도 검색하지 못했다. 반대로 기본 경로만 무조건 사용하면 local.properties의 실제
재정의 값을 무시할 수 있으므로 정확한 보완이 아니다.

## 해석한 규칙

`${key:default}`는 key가 있으면 정의값, 읽은 설정 범위에서 key가 없으면 default를 사용한다.
`${key}`는 key가 없으면 미해결이다. 빈 값과 키 부재는 다르게 취급한다.

정적 모델의 우선순위(높은 순):

1. config/local.properties
2. 선택된 Extension 루트의 project.properties
3. bin/platform/project.properties

여러 선택 Extension에서 같은 키가 다른 값이면 임의로 이름순 승자를 고르지 않는다.
의존/빌드 순서까지 재현하지 않은 상태이므로 충돌로 남긴다. 기본값이 있어도 충돌을
키 부재로 간주하지 않는다. local.properties에 같은 키가 있으면 해당 값으로 우선 결정한다.

동일 값이 여러 파일에 있으면 값은 같으므로 해석하되 정의 파일들을 모두 근거로 남긴다.
비선택 Extension의 project.properties는 import 속성값 선택에 사용하지 않는다.
비선택 리소스를 보조 검색한다는 이유로 설정값까지 활성 값에 병합하면 안 되기 때문이다.

설정값 내부의 `${...}`, 여러 표현식, 중첩 기본값을 해석한다. 순환과 32단계 초과 중첩은
미해결로 남긴다. SpEL은 실행하지 않는다. 읽지 못한 설정 파일이 있으면 기본값을 임의 선택하지 않는다.
선택 판정이 불완전하면 local.properties에서 결정되지 않은 키는 하위 설정/기본값 선택을 보류한다.

## 변경된 처리 흐름

```text
원문 resource 보존
 → 속성값/기본값 해석 및 근거 기록
 → 해석 성공 시 경로 검색
 → 선택된 소스/JAR 검색
 → 미발견 시 비선택 영역의 존재 확인
 → 원문과 해석 결과를 함께 CSV 저장
```

예시 A: 읽은 설정에 키가 없는 경우

```text
resource          = ${example.security.config:config/security-spring.xml}
resolved_resource = config/security-spring.xml
property_status   = DEFAULT
property_evidence = example.security.config <- XML_DEFAULT
```

예시 B: local.properties에서 재정의한 경우

```text
resource          = ${example.security.config:config/security-spring.xml}
resolved_resource = config/custom-security.xml
property_status   = PROPERTY
property_evidence = example.security.config <- C:/hybris/config/local.properties
```

예시들은 설명용이며 개발 환경 4건의 실제 값이 아니다.
속성 해석 성공은 경로를 결정했다는 뜻이고, 해당 파일을 찾거나 실행에서 로딩했다고 확인한 뜻은 아니다.

## 출력 변경

spring-property-resolutions.csv를 추가해 CSV 총 14개를 출력한다.
정방향/역방향 결과에는 resolved_resource, property_status, property_evidence를 추가한다.
기존 resource는 원문 그대로 유지해 선언과 비교할 수 있게 한다.

| 상태 | 의미 |
| --- | --- |
| LITERAL | 표현식이 없는 기존 경로 |
| PROPERTY | 정의된 설정값 사용 |
| DEFAULT | XML 표현식 기본값 사용 |
| PROPERTY_AND_DEFAULT | 중첩/여러 표현식에서 설정값과 기본값을 함께 사용 |
| UNRESOLVED | 키 미정의·충돌·순환·읽기 문제·미지원 표현식 등 |

새 CSV에는 표현식이 있거나 해석에 실패한 선언만 출력한다. 참조하지 않은 properties
키/값을 일괄 출력하지 않는다. 해석 실패는 detail에 사유를 기록하고 기존 문제 CSV의
UNRESOLVED_PROPERTY에도 상세 사유를 남긴다. 설정 파일 읽기 문제는 PROPERTY_FILE_ERROR이다.

## 적용 범위와 제한

설정 파일은 Java 표준 Properties.load(InputStream) 규칙으로 읽는다. 줄 연속, 주석,
Unicode escape 등을 처리하지만 UTF-8 직접 문자 파일을 자동 판별하지 않는다.
분석 PC의 JVM 시스템 속성/환경변수를 개발 서버의 실행값이라고 가정하지 않는다.

이번 단계는 기본 파일 3계층의 정적 모델이다. 서버 실행 중 변경, 커스텀 Spring
PropertySource, HYBRIS_RUNTIME_PROPERTIES, optional config의 추가 파일 및 실제
Extension 로딩 순서 전체를 재현하지 않는다. 따라서 DEFAULT는 모델에 읽은 파일에서
키를 못 찾았다는 근거이며, 실제 서버의 모든 PropertySource에서 키가 없음을 증명하지 않는다.

필요한 경우 analyzer.localproperties JVM 옵션으로 별도의 분석용 파일을 지정할 수 있다.
명시한 파일이 없으면 입력 오류를 기록하며 기본값으로 조용히 넘어가지 않는다.
기존 SAP 설정 파일은 수정하지 않는다.

## 검증

Java 21에서 검증용 properties 및 XML로 다음을 확인했다.

- local > Extension > platform 우선순위
- Extension 동일 값 허용/다른 값 충돌 보류
- 로컬 값으로 Extension 충돌 덮어쓰기
- 중첩 기본값/중첩 키/반복 키/설정값 재귀 해석
- 기본값의 classpath: 같은 콜론 유지
- 빈 값과 키 부재 구분, 순환·미닫힌 표현식·SpEL 차단
- Java properties Unicode escape/줄 연속
- 비선택 properties 제외
- 4개 import가 해석 후 실제 파일 검색까지 이어짐
- 원문 보존, 근거 CSV, 무관한 properties 일괄 출력 방지
- 불완전 선택·설정 읽기 오류의 보수적 처리
- 기존 Extension/Spring XML/JAR/비선택 검색 회귀 테스트

검증 코드: test/com/sapcommerce/analyzer/ImportPropertyResolverTest.java.
사용자의 실제 4건이 해소되는지는 개발 환경에 반영한 뒤 재실행해서 확인해야 한다.

## 재실행 확인 사항

ExtensionScanner를 인자 없이 실행한다. CSV 14개와 아래 Console 수치를 확인한다.

```text
Resolved property imports
Unresolved property imports
```

새 CSV의 4개 원문, resolved_resource, property_status, property_evidence 및 detail을
확인한다. DEFAULT/PROPERTY로 해석돼도 파일을 못 찾으면 NOT_FOUND_IN_SCOPE 등 다른
진단이 나올 수 있으므로 import 결과와 함께 비교한다.
기존 XML 파싱 오류 1건과 비선택 대상의 실제 로딩 여부는 이번 변경의 해결 범위가 아니다.

## 규칙 참고

- SAP: https://help.sap.com/docs/SAP_COMMERCE/b490bb4e85bc42a7aa09d513d0bcb18e/8b8e13c9866910149d40b151a9196543.html
- Spring: https://docs.spring.io/spring-framework/docs/5.1.x/spring-framework-reference/pdf/core.pdf
