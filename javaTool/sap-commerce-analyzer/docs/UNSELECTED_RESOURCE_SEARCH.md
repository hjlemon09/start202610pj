# 비선택 Extension에서 리소스가 발견된 사례와 보조 검색 개선

## 작업의 배경

JAR 검색 확장 후 개발 환경의 실행 결과는 다음과 같았다.

- 선택된 Extension 181개, NOT_SELECTED 441개, UNKNOWN 0개
- 일반 XML 1,613개 조사, Spring XML 784개 식별, import 선언 276개
- JAR 1,035개 검색, 내부 XML 항목 1,600개, JAR 인덱싱 오류 0개
- JAR 대상 결과 7행
- 문제 7건: NOT_FOUND_IN_SCOPE 2건, UNRESOLVED_PROPERTY 4건, XML_INSPECTION_ERROR 1건

수치는 사용자가 제공한 개발 환경 실행 화면을 근거로 기록한다.
로컬 검증 환경에서 실제 SAP 설치 파일을 확인한 결과가 아니다.

## 함께 조사해 확인한 두 사례

### personalization 추가 웹 설정

```text
resource:
classpath*:/personalizationwebservices/additional-web-spring-context.xml

사용자가 Eclipse 검색으로 확인한 위치:
hybris/bin/modules/personalization/personalizationcmsweb/resources/
    personalizationwebservices/additional-web-spring-context.xml

소유 Extension: personalizationcmsweb
selection_status: NOT_SELECTED
```

검색 결과에는 같은 이름의 다른 XML도 있었지만 resources 아래 경로까지 일치하는 것은
위 항목이었다. 분석기는 선택된 영역만 검색하므로 이 파일을 찾지 못했다.

### swagger 보안 설정

```text
resource:
classpath*:/swagger/swaggerintegration/web/spring/config/security-config-spring.xml

사용자가 확인한 위치:
hybris/bin/modules/web-services-commons/swaggerintegration/resources/
    swagger/swaggerintegration/web/spring/config/security-config-spring.xml

소유 Extension: swaggerintegration
selection_status: NOT_SELECTED
```

조사 도중 파일명을 security-spring.xml로 잘못 전달한 일이 있었다.
사용자의 지적으로 security-config-spring.xml로 정정했다.
앞선 잘못된 파일명 검색으로 내린 미발견 판단은 근거로 사용하지 않는다.

## 무엇이 문제였나

NOT_FOUND_IN_SCOPE는 분석 범위 안에서 못 찾았다는 뜻이었다. 전체 설치 경로에 파일이
없다는 뜻은 아니었다. 두 사례 모두 실제로 파일이 존재했고, 소유 Extension이 NOT_SELECTED여서
주 검색 범위에서 제외됐다는 사실까지 확인했다.

이는 아래 사실을 구분해야 함을 보여준다.

1. 파일이 디스크 또는 JAR에 존재한다.
2. 소유 Extension이 설정 기반 선택 대상이다.
3. 실행 시 해당 리소스가 클래스패스에서 보인다.
4. 해당 XML이 실제로 로딩된다.

이번에 확인한 것은 1과 2이다. 3과 4를 확인한 것은 아니다.
선택된 Extension의 모든 XML이 실행 시 로딩되는 것도 아니다.
따라서 파일을 찾았다는 이유로 소유 Extension을 활성 대상으로 변경하면 안 된다.

## 바꾼 동작

1. 선택된 일반 소스 XML 및 선택된 JAR/플랫폼 공통 JAR에서 먼저 검색한다.
2. 대상이 없고 경로를 해석할 수 있을 때만 NOT_SELECTED 소스/JAR 목록을 준비한다.
3. 동일 실행의 다음 보조 검색에서는 목록을 재사용한다.
4. 참조 경로와 일치하는 비선택 후보를 모두 기록한다.
5. FOUND_IN_UNSELECTED_EXTENSION 진단을 남기고 소유 Extension과 상태를 출력한다.
6. 활성 판정은 그대로 유지한다. 비선택 XML 본문 파싱과 후속 import 추적은 하지 않는다.

선택된 영역에서 이미 찾은 참조는 보조 검색으로 후보를 늘리지 않는다.
${...} 같은 미해석 표현식은 임의 경로로 바꾸지 않고 기존 UNRESOLVED_PROPERTY로 남긴다.
UNKNOWN은 NOT_SELECTED가 아니므로 이 보조 검색에 포함하지 않는다.

보조 검색은 존재 확인이 목적이다. 다른 웹 Extension의 후보도 발견 사실로 기록할 수 있지만,
현재 웹 Context에서 볼 수 있다는 의미는 아니다. 일반 선택 대상의 클래스패스 후보 검색과
구분해서 해석해야 한다.

## 결과를 읽는 방법

정방향/역방향 import CSV에 target_extension과 target_selection_status 열을 추가했다.

예상하는 첫 사례의 결과 형식(개발 환경 재실행 결과가 아님):

```text
resource                = classpath*:/personalizationwebservices/additional-web-spring-context.xml
resolution_status       = FOUND_IN_UNSELECTED_EXTENSION
target_kind             = SOURCE_FILE
target_extension        = personalizationcmsweb
target_selection_status = NOT_SELECTED
target_parse_status     = SOURCE_FILE_NOT_PARSED
runtime_classpath_status= NOT_EVALUATED
```

JAR에서 찾으면 target_kind=JAR_ENTRY이며 target_jar/target_entry도 기록한다.
target_parse_status는 JAR_ENTRY_NOT_PARSED이다.
spring-jar-files.csv에는 보조 검색 JAR도 추가하고 selection_scope=NOT_SELECTED로 구분한다.
비선택 일반 XML은 주 spring-xml-files.csv에 섞지 않고 발견한 대상 행으로 위치를 기록한다.

FOUND_IN_UNSELECTED_EXTENSION은 문제 CSV에서도 유지한다. 한 import 선언에서 여러 후보를
찾으면 정방향에는 후보별 여러 행, 문제 CSV에는 해당 선언의 진단 한 건을 남긴다.
따라서 문제 총수만 보고 개선 여부를 판단하면 안 된다.

예를 들어 기존 2건이 모두 비선택 파일에서 발견되면, 다른 조건이 같을 때 아래 변화가 가능하다.

```text
이전: NOT_FOUND_IN_SCOPE 2
이후: FOUND_IN_UNSELECTED_EXTENSION 2
```

총 진단 건수는 그대로일 수 있다. 더 정확한 원인으로 재분류한 것이며 실행 로딩 문제가
해결됐다는 의미는 아니다. 추가 접근/인덱싱 실패가 있으면 진단 수는 증가할 수도 있다.

## 콘솔과 CSV

CSV는 13개로 유지한다. 콘솔에는 아래 항목을 추가했다.

- Unselected search: 보조 검색 수행 여부
- Unselected XML candidates: 보조 검색용으로 수집한 XML 경로 수
- Unselected JAR files / index issues: 비선택 JAR 검색 수와 오류 수
- Unselected target rows: 비선택 영역에서 발견한 결과 행 수 (선언 수와 다를 수 있음)

기존 Inspected XML, Spring XML, Import declarations는 선택 대상 주 분석 기준을 유지한다.
기존 JAR files / JAR XML entries / JAR index issues는 주 검색 기준이며 보조 검색 수는 별도다.
JAR target rows는 주 검색과 보조 검색의 JAR 대상 결과 행을 합산한다.

## 검증과 개발 환경 확인

Java 21에서 다음 테스트를 검증했다.

- 사용자 확인 두 경로를 재현한 비선택 소스 검색
- 비선택 JAR 검색 및 손상 JAR 오류 기록
- 복수 비선택 후보 보존
- 주 검색 성공 시 보조 후보를 섞지 않음
- 비선택 XML 내부의 import를 따라가지 않음
- 선택 상태를 수정하지 않음
- UNKNOWN 제외, 필요할 때만 보조 검색 수행
- CSV 소유자/상태/역방향/보조 JAR 목록 기록
- 기존 JAR·Spring XML·활성 대상 판정 회귀 테스트

테스트: test/com/sapcommerce/analyzer/UnselectedResourceSearchTest.java.

개발 환경에서는 변경분 반영 후 ExtensionScanner를 인자 없이 실행한다.
두 resource의 resolution_status, target_extension, target_selection_status와
Unselected target rows 및 상태별 진단 수를 확인한다.
실제 개발 환경에서 두 사례가 자동 분류되는지는 재실행으로 검증해야 한다.
