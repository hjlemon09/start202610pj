# JAR 내부 XML 본문 및 후속 import 추적

## 시작 시점과 목적

사용자의 직전 실행에서 소스 XML 1,613개, Spring XML 784개, import 선언 276개,
선택 영역 JAR 1,035개 및 내부 XML 항목 1,600개를 확인했다. JAR 대상 참조는 7행이었다.
속성 미해석은 0건이며, 비선택 Extension 발견 진단 2건과 루트 요소 없는 파일 제외 1건을 유지했다.
이 수치는 개발 환경 실행 화면의 기록이다.

기존 JAR 검색은 파일 이름과 내부 경로만 수집했다. 예를 들어 소스 A.xml이 JAR 안의 B.xml을
참조할 때 B의 존재까지만 알 수 있었다. B가 C를 import하는지는 알 수 없었다.
이번 변경은 선택 영역에서 도달한 B의 본문을 읽고 그 후속 import도 같은 그래프에 연결한다.

```text
소스 A.xml → JAR 1 / B.xml → JAR 1 / C.xml
                           → JAR 2 / D.xml
                           → 소스 E.xml
```

## 어떻게 읽는가

ZipFile로 아카이브를 열고 getEntry로 대상 항목을 찾은 뒤 getInputStream으로 압축 해제 스트림을 읽는다.
이 스트림을 StAX XML 파서에 전달한다. 디스크에 압축 해제 파일을 만들지 않고 JAR 클래스도 실행하지 않는다.
리더·스트림·ZipFile은 사용 후 닫는다.

Spring beans 루트와 namespace를 확인하고 beans/import의 resource와 줄 번호를 추출한다.
파싱을 끝까지 성공한 뒤에만 추출한 선언을 분석 대기열에 추가한다.
깨진 XML에서 일부만 읽은 import를 정상 결과로 사용하지 않는다.
Spring이 아닌 XML은 NOT_SPRING으로 기록하고 내부를 Spring 설정처럼 추적하지 않는다.

## import 해석

- 상대 경로: 같은 JAR의 출발 entry 디렉터리를 기준으로 계산한다.
- ../: JAR 내부 부모 디렉터리로 이동하되 JAR 루트 밖으로 탈출하면 진단을 남긴다.
- 선행 /: 기존 상대 리소스 모델과 같이 제거하고 출발 entry 기준으로 찾는다.
- classpath/classpath*: 선택된 소스 및 인덱싱한 JAR 후보를 함께 검색한다.
- 속성 표현식: 기존 정적 properties 해석기를 사용하고 원문/해석값/근거를 보존한다.
- 선택 영역 미발견: 클래스패스 참조에 대해서는 비선택 보조 검색을 수행한다.
- 비선택 후보: 존재 확인만 유지하며 후속 import를 추적하거나 활성 대상으로 바꾸지 않는다.

## 순환·중복과 분석 문맥

대기열을 순서대로 처리하고 새로 발견한 import를 끝에 추가한다. 그래프를 재귀 호출로
계속 내려가지 않는다. 각 JAR 경로+entry 경로+분석 문맥 Extension 조합의 파싱 결과를 캐시한다.
그래서 B→C→B가 있어도 B를 무한히 다시 읽지 않는다. 순환 간선은 CSV에 남지만
Spring import 순환 그룹이나 모든 경로를 펼친 트리는 이번 변경에서 별도 출력하지 않는다.

같은 JAR XML이 서로 다른 Extension에서 참조될 때 각 웹 라이브러리 후보가 달라질 수 있다.
따라서 최초 출발 소스의 Extension을 후속 JAR 분석에 전파하고 결과에 source_context_extension을 남긴다.
이는 정적 후보 가시성을 위한 기준이며 실제 Core/Web ApplicationContext를 확인한 것은 아니다.
소스 XML은 기존 수집 단계에서 소유 Extension 기준으로 분석하므로 서버 Context 전체를 재현하지 않는다.

중복 classpath 후보는 모두 유지하며 어떤 XML이 실제로 선택되는지 임의로 확정하지 않는다.
참조되지 않은 JAR XML을 전부 파싱하지는 않는다. 인덱싱 1,600항목과 본문 조사 수는 다르다.

## 출력 변경

spring-jar-xml-files.csv를 추가하여 CSV 총 15개를 생성한다.
각 행은 JAR 경로, entry 경로, 소유 Extension, 분석 문맥, Spring 여부, 파싱 상태 및 import 수이다.

선택 JAR 대상의 target_parse_status는 이제 실제 본문 조사 결과를 나타낸다.

- PARSED: Spring XML 본문을 읽음. 스키마/실행 검증을 뜻하지 않음.
- PARSED_DTD_SKIPPED: 외부 DTD를 읽지 않고 본문을 조사함.
- NOT_SPRING: beans 루트가 아님.
- PARSE_ERROR: 파싱 실패 또는 읽기/크기 제한 등으로 본문 조사를 완료하지 못함.
- JAR_ENTRY_NOT_PARSED: 비선택 보조 후보 등 존재 확인만 한 경우.

기존 spring-imports.csv와 역방향 CSV에 JAR에서 출발한 import 행도 추가한다.
source_xml과 target_xml은 JAR 내부이면 jar:file:/...!/entry.xml 형식이다.
source_context_extension으로 문맥별 결과를 구분한다. 속성 해석 CSV도 같은 문맥을 기록한다.

Console과 summary.csv에 다음을 추가한다.

- Source import declarations: 기존 소스 XML의 선언 수
- JAR import declarations: 도달한 JAR XML의 선언 수(문맥별)
- Import declarations: 위 두 값의 합
- JAR XML parsed contexts: 성공/실패/비Spring 포함 문맥별 조사 수
- JAR XML unique resources: 문맥을 제외한 고유 JAR XML 수
- JAR XML parse errors: 파싱 실패 문맥 수

기존 Inspected XML과 Spring XML은 소스 기준을 유지한다.
분석 범위가 넓어졌으므로 import 선언/결과 행/진단 수가 증가할 수 있다.
기존 276과 달라졌다는 이유만으로 오류라고 판단하면 안 된다.

## 실패와 제한

외부 DTD 접근, 엔티티 확장, 네트워크 로딩을 차단한다. JAR entry 하나당 읽기 한도는
압축 해제 후 16MiB다. ZIP 메타데이터 크기 확인과 읽은 바이트 수 제한을 함께 적용한다.
초과 또는 XML 파싱 문제는 JAR_XML_INSPECTION_ERROR로 남기고 다른 항목 분석을 계속한다.
JAR XML의 루트 없음은 현재 일반 JAR 파싱 오류로 남긴다. 소스의 주석 전용 파일 제외 처리와 구분된다.

비선택 파일의 활성 승격, 중첩 JAR/WAR/EAR, 런타임 클래스패스 순서, Spring 활성 프로파일,
실제 Bean 등록 및 Context 소속은 이번 범위에 포함하지 않는다.
실제 runtime_loading_status는 계속 NOT_EVALUATED이다.

## 검증

Java 21의 검증용 JAR로 다음을 확인했다.

- 소스→JAR→같은 JAR/다른 JAR/소스의 후속 import
- JAR 내부 ../ 및 선행 / 상대 경로
- 순환 간선 보존과 종료, 리소스/문맥별 파싱 중복 방지
- 같은 JAR를 두 출발 Extension에서 참조하는 경우
- JAR 속성 표현식 해석과 비선택 보조 후보의 존재 확인만 유지
- 외부 DTD 미로딩, 엔티티/깨진 XML/16MiB 초과 진단
- 참조되지 않은 JAR XML을 파싱하지 않음
- 디스크 압축 해제 없이 조사
- JAR 목록 및 정방향/역방향/속성 CSV 생성
- 기존 활성 판정/소스 XML/보조 검색/속성 해석/루트 없는 파일 분류 회귀 검증

테스트 소스는 test/com/sapcommerce/analyzer/JarXmlTraversalTest.java이다.
실제 개발 환경에서 JAR 내부 내용이 어떤 후속 참조를 갖는지는 아직 확인하지 않았다.

## 개발 환경 재실행

기존처럼 ExtensionScanner를 Program arguments 없이 실행한다.
CSV 15개와 새 JAR XML 조사 수/오류 수, 소스 및 JAR import 선언 수를 확인한다.
JAR_XML_INSPECTION_ERROR 또는 새 미발견이 있으면 해당 행을 검토한다.
새로운 문제를 숨기거나 이전과 같은 3건이 나오도록 강제하지 않는다.
