# SAP Commerce Analyzer

첨부 문서의 STEP 10-2까지 구현한 독립 Java 분석 도구입니다.
Java 21 표준 API만 사용하며 Maven, Gradle, Spring Boot 및 외부 라이브러리는 필요하지 않습니다.
SAP Commerce의 소스와 설정은 읽기만 합니다.

## 구현 범위

- 지정한 루트 아래의 `extensioninfo.xml` 검색
- `node_modules`, `.git`, `temp` 디렉터리 제외 및 접근 실패 시 경고 후 계속 검색
- Extension 이름과 직접 `requires-extension` 목록 파싱
- 검색/파싱 성공/실패/이름 중복/접근 실패 수 출력
- 특정 Extension에서 시작하는 직접·간접 의존관계 트리 출력
- 순환 참조와 이미 방문한 공유 의존관계 구분
- XML 외부 엔티티, 외부 DTD 및 외부 스키마 접근 차단

`localextensions.xml` 활성화 판정, Spring XML import, ApplicationContext, Bean 분석,
시각화, CallTrace 및 CSV 출력은 이후 단계이며 이 버전에 포함하지 않습니다.

## Eclipse에서 가져오기

1. 프로젝트 폴더 전체를 개발 PC로 복사합니다. 위치는 자유롭게 바꿀 수 있습니다.
2. **File → Import → General → Existing Projects into Workspace**를 선택합니다.
3. 이 폴더를 Root directory로 지정하고 프로젝트를 가져옵니다.
4. **Preferences → Java → Installed JREs**에서 JDK 21을 등록합니다.
5. 프로젝트의 **Java Build Path → Libraries**에서 JavaSE-21 연결을 확인합니다.
6. `src/com/sapcommerce/analyzer/ExtensionScanner.java`를 선택하고
   **Run As → Java Application**으로 실행합니다.

프로젝트에 Java 21 소스/컴파일 대상과 UTF-8 인코딩 설정을 포함했습니다.
`module-info.java`는 사용하지 않습니다.

## 실행 인자

**Run → Run Configurations → Java Application → ExtensionScanner → Arguments → Program arguments**:

```text
"C:\hybris" mmmcore
```

`mmmcore`는 익명화된 예시입니다. 실제 `extensioninfo.xml`의 `extension name`으로 바꾸세요.
경로에 공백이 있으면 큰따옴표로 감쌉니다.

- 인자 없음: 기본 경로 `C:\hybris` 검색 및 요약만 출력
- 첫 번째 인자: 검색할 SAP Commerce 루트
- 두 번째 인자: 의존관계 트리의 시작 Extension 이름 (대소문자 구분)
- `--help`: 사용법 출력

예상 출력 형태:

```text
=== Analysis Summary ===
Found XML files : ...
Parsed          : ...
Failed          : 0
Unique names    : ...
Duplicate files : 0
Access failures : 0

=== Extension Dependency Analysis ===
=== Dependency Tree ===
mmmcore
  -> acceleratorcms
    -> ...
```

단일 XML만 확인하려면 `ExtensionInfoParser`를 실행하고 Program arguments에
`"C:\hybris\bin\custom\mmm\mmmcore\extensioninfo.xml"`을 입력합니다.

## 결과 해석

- 발견 개수는 XML 파일 수이며 활성 Extension 수가 아닙니다. 622개로 고정하지 않습니다.
- 트리는 XML에 선언된 Extension 의존관계이며 실제 메서드 호출이나 Bean 주입을 의미하지 않습니다.
- `[NOT FOUND]`: 검색·파싱된 Extension 목록에 없음. 검색 범위 밖, 제외 디렉터리,
  파싱 실패 등일 수 있으며 실제 Extension 부재를 단정하지 않습니다.
- `[ALREADY VISITED]`: 앞에서 분석한 Extension. 하위 트리의 중복 출력을 생략합니다.
- `[CYCLE]`: 현재 탐색 경로에 있는 Extension을 다시 참조합니다.
- 동일 이름의 XML이 여러 개면 경로를 출력하고 트리 분석을 중단합니다.
  복사본이나 템플릿이 있는지 확인하고 분석 루트를 좁히세요. 임의의 정의를 선택하지 않습니다.
- 파싱 또는 접근 실패가 있으면 결과가 불완전할 수 있습니다. 정상 종료 코드는 0,
  검색·파싱 오류/중복/시작 Extension 누락은 1입니다. 하위 의존관계의 `[NOT FOUND]`는
  표시만 하며 단독으로 종료 코드를 변경하지 않습니다.
- 심볼릭 링크를 따라가지 않습니다. `temp` 등 제외 경로의 정의는 검색하지 않습니다.
- DOCTYPE가 있는 XML은 이 버전의 파서에서 거부합니다.

## 소스 구조

```text
src/com/sapcommerce/analyzer/
├── ExtensionScanner.java
├── ExtensionInfoParser.java
├── ExtensionInfo.java
└── ExtensionDependencyAnalyzer.java
```

소스 및 Eclipse 설정만 제공하며 컴파일 산출물은 포함하지 않습니다.
실제 SAP Commerce 환경에서의 검증은 개발 PC에서 수행하세요.
