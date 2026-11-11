# JAR Code Search

지정한 폴더의 일반 파일과 JAR 내부 CLASS를 함께 검색하는 Java 21 도구입니다.
기존 sap-commerce-analyzer와 독립된 프로젝트입니다. 검색 대상 클래스는 실행하지 않습니다.

## 실행

JDK 21 이상이 필요합니다. `java`와 `javac`를 PATH에서 실행할 수 있어야 합니다.
`lib/cfr-0.152.jar`를 함께 제공하므로 빌드와 검색에 인터넷이나 Maven은 필요하지 않습니다.

Windows에서 `run.cmd`를 실행하면 검색 화면이 열립니다.
검색 폴더에 예를 들어 `C:\hybris`를 입력하거나 폴더 선택 버튼으로 선택하고,
검색어에 `new PlatformInPlaceClassLoader`를 입력한 뒤 검색을 누릅니다.
진행 로그는 영어로 표시됩니다. 최초 100개 일치 항목만 화면에 표시하고 CSV에는 전부 기록합니다.

명령 프롬프트에서는 다음처럼 실행합니다.

```bat
run.cmd "C:\hybris" "new PlatformInPlaceClassLoader"
run.cmd "C:\hybris" "platformInPlaceClassLoader.libPatches.classpath"
run.cmd "C:\hybris" "PlatformInPlaceClassLoader" --ignore-case
run.cmd "C:\hybris" "new\s+(?:[\w$]+\.)*PlatformInPlaceClassLoader\b" --regex
run.cmd "C:\hybris" "검색어" --charset=MS949
```

Eclipse에서는 Existing Projects into Workspace로 가져오고 JavaSE-21을 지정한 후
`com.javatool.search.JarCodeSearch`를 Java Application으로 실행합니다.
실행 인자가 없으면 화면이 열립니다. 기존 분석기의 ExtensionScanner는 실행하지 않습니다.

## 검색 방법

1. 폴더를 재귀적으로 탐색합니다. 일반 파일은 확장자로 제한하지 않고 텍스트 해독을 시도합니다.
2. `.jar`, `.zip`, `.war`, `.ear`는 ZipFile로 엔트리를 읽습니다.
3. 일반 `.class`와 아카이브 내부 `.class`는 CFR API로 Java 형태의 문자열을 복원해 검색합니다.
4. 아카이브 안의 XML, properties 등 텍스트 엔트리도 검색합니다.
5. JAR 내부 클래스는 InputStream으로 메모리에 읽습니다. 대상 JAR를 디스크에 풀거나 변경하지 않습니다.
6. 기본 검색은 대소문자를 구분하는 부분 문자열 검색입니다. 검색어의 연속 공백은
   하나 이상의 공백/탭/줄바꿈과 일치하므로 `new` 다음 줄에 클래스명이 있어도 찾습니다.
   정규식을 선택하면 Java 정규식을 사용합니다.

생성자 호출 검색을 위해 단순히 CLASS의 바이너리 바이트에서 `new 클래스명`을 찾는 것은 아닙니다.
그 문자열이 원래 바이트코드에 그대로 저장되지 않기 때문에 디컴파일 과정을 거칩니다.
개별 클래스를 차례로 처리하므로 같은 이름의 클래스가 다른 JAR에 있어도 각각 검색합니다.
외부 클래스와 내부 클래스는 별도로 시도하며 익명 클래스 생성 코드도 검색 대상입니다.

## 결과

프로젝트 실행 위치의 `reports/날짜시간_고유값/`에 세 파일을 만듭니다.

| 파일 | 내용 |
| --- | --- |
| matches.csv | 종류, 원본 파일/JAR 절대 경로, 내부 엔트리, 줄 번호, 일치 문자열, 해당 코드 |
| issues.csv | 읽기/디컴파일 실패와 검색에서 제외된 파일 또는 엔트리 |
| summary.txt | 파일 수, CLASS 시도 수, 일치 수, 이슈 및 제외 수 |

CSV는 UTF-8 BOM을 포함합니다. CLASS의 줄 번호는 **복원한 코드의 줄 번호**이며 원본 Java의 줄 번호가 아닙니다.
CSV에서 `file_or_jar`와 `entry`를 확인한 후 IDE에서 해당 JAR와 클래스를 열어 코드를 검토하세요.
CSV의 검색 문자열이 스프레드시트 수식으로 해석되지 않도록 위험한 시작 문자에는 작은따옴표를 붙입니다.

## 범위와 한계

- 검색어가 없다는 결과와 모든 파일 검색이 성공했다는 결과는 다릅니다. 반드시 issues.csv도 확인하세요.
- `.git`, 이번 결과 출력 폴더와 심볼릭 링크 대상은 탐색하지 않습니다.
- NUL을 포함한 바이너리는 텍스트 검색에서 제외합니다. PDF, Word, 이미지 등의 내용을 추출하는 도구는 아닙니다.
- 기본 텍스트 인코딩은 UTF-8이고 BOM이 있는 UTF-8/UTF-16은 자동 감지합니다.
  다른 인코딩은 화면의 텍스트 인코딩 또는 --charset으로 선택하세요. 해독 실패는 기록합니다.
- 파일/아카이브 엔트리당 최대 32 MiB까지 읽습니다. 초과하면 기록하고 건너뜁니다.
- JAR 안에 다시 들어 있는 JAR 등 중첩 아카이브는 기록 후 제외합니다.
  필요하면 중첩 아카이브를 별도 폴더에 풀어서 검색하세요.
- 디컴파일은 원본 소스의 완벽한 복원이 아닙니다. 클래스 이름이 완전한 패키지명으로 출력되거나
  컴파일러 최적화/난독화/지원하지 않는 바이트코드 때문에 표현이 달라질 수 있습니다.
  구체적인 검색이 실패하면 `PlatformInPlaceClassLoader`처럼 클래스명만으로도 검색하세요.
- 디컴파일 오류와 일부 메서드 복원 실패를 기록하지만, 모든 복원 부정확성을 자동 검출할 수는 없습니다.
- 현재 검색 중인 JAR 외부의 애플리케이션 의존성을 자동 연결하지 않습니다. 누락된 타입에 관한 CFR 경고가 나올 수 있습니다.
- 대규모 폴더는 모든 CLASS를 디컴파일하므로 오래 걸립니다. 먼저 관심 있는 modules/platform 폴더로 좁혀 사용하세요.
- 프로그램이 메모리 부족 등으로 종료되면 CSV가 일부만 작성됐을 수 있습니다. summary.txt 생성과 완료 메시지를 확인하세요.

## 소스 구조와 검증

- `JarCodeSearch.java`: 폴더 탐색, ZipFile 엔트리 읽기, CFR 입력/출력 연결, 검색, CSV 작성
- `SearchWindow.java`: 폴더/검색어 입력 화면과 백그라운드 검색
- `test.cmd`: Java 21 클래스를 직접 컴파일하고 JAR를 만들어 실제 생성자 호출 검색 검증

검증에는 일반 파일, JAR 내부 CLASS, 독립 CLASS, 익명 클래스 생성자, 줄바꿈 검색,
아카이브 텍스트, UTF-16, 정규식/대소문자 무시, 손상 JAR/CLASS, 바이너리 제외, 중첩 아카이브 기록을 포함합니다.

## 외부 라이브러리

CFR 0.152 (MIT), 배포본과 라이선스는 lib 폴더에 포함합니다.
- 공식 배포처: https://www.benf.org/other/cfr/
- API: https://www.benf.org/other/cfr/api/index.html
- 소스: https://github.com/leibnitz27/cfr

`new` 검색은 디컴파일한 텍스트에 대한 검색이지 런타임 호출이나 실제 실행 여부의 증명은 아닙니다.
