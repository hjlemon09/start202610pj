# JAR 내부 XML 검색 확장 기록

## 배경과 확인한 사실

개발 환경에서 XML 1,613개를 조사했고, Spring XML 784개와 import 선언 276개를 식별했다.
최초 문제는 26건이었다. 선행 `/` 경로 처리 11건과 DOCTYPE 제한 1건을 보완한 뒤
재실행에서 문제는 14건으로 줄었다. 당시 결과는 아래와 같았다.

| 분류 | 건수 | 당시 의미 |
| --- | ---: | --- |
| NOT_FOUND_IN_SCOPE | 9 | 일반 소스 XML 검색 범위에서 대상 미발견 |
| UNRESOLVED_PROPERTY | 4 | 속성 표현식을 아직 해석하지 않음 |
| XML_INSPECTION_ERROR | 1 | widgets XML 16행 부근에서 예상치 못한 파일 끝 오류 |

이는 사용자가 제공한 개발 환경 실행 화면에 근거한다. 해당 환경의 소스와 JAR를
현재 로컬 PC에서 직접 확인한 것은 아니다. 특히 NOT_FOUND_IN_SCOPE 9건의 대상이
실제로 JAR에 들어 있다는 사실은 이 문서를 작성한 시점에는 확인되지 않았다.

## source_xml과 resource를 구분하기

예를 들어 다음 행이 있다고 하자.

```text
source_xml = .../backoffice-test-spring.xml
line       = 8
resource   = classpath*:META-INF/cockpitcore-spring.xml
```

source_xml은 분석기가 이미 읽은 출발 파일이다. 그 파일의 8행에서 resource를
불러오도록 선언했는데, 대상 후보를 못 찾았다는 의미다. 출발 파일을 못 찾았다는 뜻이 아니다.

## 기존 검색의 한계

기존 분석기는 선택된 Extension의 resources와 web/webroot 아래에 일반 파일로 존재하는
XML만 검색했다. 다음 JAR에 들어 있는 항목은 검색하지 않았다.

```text
some-library.jar
└── META-INF/
    └── cockpitcore-spring.xml
```

따라서 미발견은 아래 세 가지 중 어느 경우인지 구분할 수 없는 상태였다.

1. 대상이 JAR 내부에 있다.
2. 대상이 검색 범위 밖의 폴더나 라이브러리에 있다.
3. 대상 리소스가 실제로 없거나 참조 경로가 다르다.

이번 변경은 첫 번째 가능성을 확인할 수 있게 만드는 작업이다. 문제를 모두 해결했다고
가정하거나, 참조 대상이 없다고 단정하는 작업이 아니다.

## 구현한 처리 순서

1. 기존 방식으로 localextensions.xml 및 의존관계 기반 선택 대상을 판정한다.
2. 선택된 Extension 폴더 전체에서 JAR를 찾는다.
3. 추가로 플랫폼 공통 bin/platform/lib와 bin/platform/bootstrap/bin을 검색한다.
4. 각 JAR를 Java 표준 ZipFile로 열어 `.xml` 항목의 이름을 인덱싱한다.
5. 출발 소스 XML의 classpath/classpath* import를 일반 XML과 JAR 항목에 함께 대조한다.
6. 일치한 각 후보의 JAR 경로와 내부 경로를 CSV에 남긴다.
7. JAR가 깨졌거나 접근할 수 없으면 실패 경로와 사유를 기록하고 나머지 검색을 계속한다.

파일을 압축 해제하거나 JAR 안의 클래스를 실행하지 않는다. 원본 SAP Commerce의
소스, 설정 및 JAR를 변경하지 않는다. 네트워크에서 리소스를 다운로드하지 않는다.

## 검색 범위와 한계

- 선택되지 않은 Extension 폴더는 검색하지 않는다.
- `.git`, node_modules, temp 및 심볼릭 링크 대상은 제외한다.
- 다른 Storefront의 web/webroot 아래 JAR는 해당 출발 Extension의 후보에 섞지 않는다.
- JAR 내부의 중첩 JAR, WAR/EAR 압축파일, 외부 저장소의 라이브러리는 검색하지 않는다.
- JAR 내부 XML의 **이름/경로 존재만 확인**한다. 본문 파싱, 스키마 검증, 후속 import 추적은 하지 않는다.
- 실제 로딩 순서, 서버 배포 클래스패스, Core/Web Context 가시성 및 실행 tenant 상태를 재현하지 않는다.
- Extension 폴더 안의 백업·테스트 JAR도 포함될 수 있다. 발견 사실과 런타임 사용 여부는 별개다.

## CSV에 기록하는 근거

기존 12개 CSV에 spring-jar-files.csv를 추가하여 총 13개를 생성한다.

spring-imports.csv 및 spring-reverse-imports.csv의 신규 열:

| 열 | 의미 |
| --- | --- |
| target_kind | SOURCE_FILE 또는 JAR_ENTRY |
| target_jar | 발견한 JAR의 절대 경로 |
| target_entry | JAR 내부 XML의 정확한 경로 |
| runtime_classpath_status | NOT_EVALUATED: 실행 클래스패스 포함 여부 미확인 |

예시(실제 개발 환경의 발견 결과가 아님):

```text
resource                 = classpath*:META-INF/cockpitcore-spring.xml
resolution_status        = CLASSPATH_ALL_CANDIDATE
target_kind              = JAR_ENTRY
target_jar               = C:/hybris/.../lib/example.jar
target_entry             = META-INF/cockpitcore-spring.xml
target_parse_status      = JAR_ENTRY_NOT_PARSED
target_xml               = jar:file:/C:/hybris/.../lib/example.jar!/META-INF/cockpitcore-spring.xml
runtime_classpath_status = NOT_EVALUATED
```

JAR_ENTRY_NOT_PARSED는 오류나 XML 파싱 성공이 아니다. 항목이 실제로 있다는 사실만 확인했다는 뜻이다.
기존 일반 소스 파일의 PARSED와 구분해야 한다.

spring-jar-files.csv에는 JAR 경로, 소유 Extension, 검색 범위, INDEXED/INDEX_ERROR 상태,
XML 항목 수를 기록한다. 찾지 못한 참조를 검토할 때 원하는 라이브러리가 실제로 검색됐는지
이 목록을 먼저 확인할 수 있다. 접근하지 못한 디렉터리 등은 spring-import-issues.csv에 남긴다.

## 중복과 미발견의 해석

classpath*는 조사 범위의 모든 후보를 남긴다. classpath는 하나의 리소스를 선택하는
실제 클래스패스 순서를 알 수 없으므로 후보가 여러 개면 AMBIGUOUS_CLASSPATH로 표시한다.
소스 XML과 JAR의 복사본이 동시에 발견돼도 하나를 임의로 버리지 않는다.

따라서 새 버전 실행 시 아래 변화가 가능하다.

- NOT_FOUND_IN_SCOPE가 줄어든다: 기존 미발견 대상을 JAR에서 찾았을 가능성.
- Import rows가 늘어난다: 하나의 선언에서 여러 후보를 찾았을 가능성.
- AMBIGUOUS_CLASSPATH가 늘어난다: 중복된 리소스가 새로 발견됐을 가능성.
- JAR_INDEX_ERROR가 생긴다: 손상·권한 등의 이유로 특정 JAR를 읽지 못함.

미발견이 남으면 그 파일이 없다고 단정하지 않는다. 인덱싱 실패, 외부 라이브러리,
선택 대상과 배포 클래스패스의 차이를 추가로 확인해야 한다.
이번 변경은 속성 미해석 4건과 원본 XML 파싱 문제 1건을 해결하는 변경이 아니다.

## 검증 기록

Java 21에서 소스와 외부 라이브러리가 없는 테스트 클래스를 컴파일해 검증했다.
운영 JAR 대신 테스트가 생성한 ZIP/JAR를 사용했다.

- JAR 내부 XML 항목 검색과 정확한 내부 경로 확인
- 소스와 JAR에 같은 경로가 있을 때 중복 후보 유지
- classpath*의 여러 JAR 매칭과 Ant 패턴 검색
- 비선택 Extension, 제외 폴더 및 다른 Storefront 웹 라이브러리 제외
- 플랫폼 공통 라이브러리 경로 검색
- 깨진 JAR를 기록한 뒤 나머지 분석 계속
- 공백/#/%가 있는 JAR 경로의 URI 인코딩
- 실제 압축 해제 파일이 생성되지 않음
- JAR 항목을 PARSED로 잘못 표시하지 않음
- 정방향/역방향/JAR 목록 CSV 생성
- 기존 Spring XML 분석 및 Extension 선택 분석 회귀 테스트

테스트 코드: test/com/sapcommerce/analyzer/JarResourceIndexTest.java,
SpringXmlAnalyzerTest.java, ActiveExtensionAnalyzerTest.java.
실제 개발 환경의 미발견 9건 해소 여부는 아직 검증하지 않았다.

## 개발 환경에서 재실행할 방법

1. 변경된 소스와 새 JarResourceIndex.java를 Git으로 옮긴다.
2. 기존처럼 ExtensionScanner.java를 Run As → Java Application으로 실행한다.
3. Program arguments는 비운다. Extension이나 JAR를 수동 선택할 필요는 없다.
4. JAR files, JAR XML entries, JAR index issues, JAR target rows를 확인한다.
5. CSV 13개 생성 여부와 spring-jar-files.csv의 검색 목록/실패를 확인한다.
6. 기존 resource 9개가 spring-imports.csv에서 어떤 상태인지 확인한다.
7. 찾은 항목은 target_jar와 target_entry를 기록한다. 여전히 못 찾은 항목은 별도로 남긴다.

재실행 결과는 본 기록의 검증과 구분해서 추가한다. 9건이 모두 해소된다고 미리 기록하지 않는다.
