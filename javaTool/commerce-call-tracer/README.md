# SAP Commerce 구간 메서드 호출 추출기 — 최소 구현

IntelliJ가 대상 JVM을 디버깅하고, Eclipse에서 실행한 별도 Java 화면이 START/STOP을 보냅니다. 실행 시 로딩한 Java Agent가 선택한 패키지의 메서드 진입·정상 종료·예외 종료를 CSV로 기록합니다. SAP Commerce 비즈니스 소스를 수정하지 않습니다.

현재 컴퓨터의 `C:\hybris`는 구조만 있는 로컬 사본입니다. 사내 SAP Commerce의 JDK, 서버 실행, PDP, 실제 성능은 검증하지 않았습니다. 작은 Java 대상에서 실제 JDWP 라인 브레이크포인트와 별도 제어 JVM으로 검증했습니다. Swing 제어 창 실행은 확인했습니다. 화면 자동 조작은 앱 접근 승인 시간 초과로 수행하지 못했고, IntelliJ/Eclipse UI를 직접 클릭한 검증도 아직 하지 않았습니다.

## 1. 구성과 빌드

- `src/agent`: Byte Buddy 기반 premain Agent. 시작 시 로딩되는 클래스에 계측합니다.
- `src/runtime`: bootstrap loader에 올리는 기록기·루프백 제어 서버.
- `src/controller`: Eclipse에서 실행할 Swing 화면과 CLI 제어기.
- `src/demo`: 동기 호출·예외·재귀·다른 스레드가 있는 작은 대상.
- `src/test`: 실제 브레이크포인트 통합 검증 및 경계/버퍼/클래스로더 검증.
- `dist`: 실행 JAR 4개와 `lib/byte-buddy.jar`. 이 폴더 구조를 함께 이동해야 합니다.
- `logs`: 세션별 CSV, 열린 호출 CSV, 측정 요약 TXT.

빌드·자동 검증은 JDK 21에서 진행했습니다. Agent/Runtime/Controller/Demo는 Java 8 바이트코드로 빌드합니다. 사내 JDK와의 호환성은 실제 버전을 확인한 뒤 재검증해야 합니다. 동적 Attach는 구현하지 않았습니다.

```powershell
cd C:\Users\krist\javaTool\commerce-call-tracer
.\build.ps1
.\verify.ps1
```

Byte Buddy 1.17.8을 `lib/byte-buddy.jar`에 포함했습니다. Maven/Gradle 설치나 빌드 중 다운로드 없이 JDK 도구로 빌드합니다. Eclipse 프로젝트 파일도 포함했습니다.

## 2. IntelliJ + Eclipse 수동 데모

1. Eclipse: **File → Import → General → Existing Projects into Workspace**로 이 폴더를 가져옵니다. JRE를 JDK 21로 설정합니다.
2. `Controller.launch`를 **Run As → Controller**로 실행합니다. 또는 `trace.control.ControlWindow`를 Java Application으로 실행하고 프로그램 인수에 `config/demo.properties`의 전체 경로를 넣습니다. 작업 디렉터리는 프로젝트 폴더로 지정합니다. 배치 실행은 `run-controller.bat`입니다.
3. IntelliJ에서 같은 폴더의 `src/demo`를 Java 소스로 열고 `demo.shop.Demo` 실행 구성을 만듭니다. JDK 21을 선택하고 작업 디렉터리를 프로젝트 폴더로 설정합니다. VM 옵션:

   ```text
   -javaagent:C:\Users\krist\javaTool\commerce-call-tracer\dist\trace-agent.jar=C:\Users\krist\javaTool\commerce-call-tracer\config\demo.properties
   ```

4. `Demo.java`의 `START_BREAKPOINT`와 `STOP_BREAKPOINT` 주석이 붙은 **호출 라인**에 라인 브레이크포인트를 겁니다. 두 개 모두 **Suspend: Thread**로 지정합니다. IntelliJ **Debug**로 실행합니다.
5. 시작 브레이크포인트에 멈추면 Eclipse 화면에서 STATUS를 눌러 응답을 확인합니다. Thread ID가 0이면 전체 스레드를 기록합니다. 요청 스레드만 기록하려면 IntelliJ Evaluate에서 `Thread.currentThread().getId()`를 확인해 입력합니다. IntelliJ/JDI의 객체 식별 번호와 Java Thread ID를 혼동하지 마세요.
6. START → `OK START` 확인 → IntelliJ Resume.
7. 종료 브레이크포인트에서 STOP → `OK STOP`과 파일 경로·측정 요약 확인 → IntelliJ Resume.
8. 데모는 두 번 반복합니다. 두 번째 시작점에서 다시 START/Resume/STOP/Resume을 수행합니다.

대신 `run-demo-debug.bat`로 대상 JVM을 실행하고 IntelliJ Remote JVM Debug로 `localhost:5005`에 연결할 수도 있습니다. 이 경우 별도 JDWP 옵션을 IntelliJ 실행 구성에 중복해서 넣지 않습니다.

Suspend: All이면 제어 서버도 정지할 수 있습니다. START/STOP이 응답하지 않으면 IntelliJ에서 Resume한 다음 브레이크포인트 정지 범위를 확인합니다. 기록기 자체 코드나 JDK 공통 메서드에 브레이크포인트를 걸지 말고 업무 코드 라인에 설정합니다. 정지 범위 설명은 [IntelliJ 공식 문서](https://www.jetbrains.com/help/idea/using-breakpoints.html)를 참고하세요.

## 3. 사내 SAP Commerce 적용

1. 사내 IntelliJ Commerce 실행 구성의 실제 JRE, 서버 실행 스크립트와 VM 옵션을 확인합니다. 이 컴퓨터의 JDK 버전을 사내 JVM 버전으로 간주하지 않습니다.
2. `dist` 전체와 설정 파일을 사내 컴퓨터로 복사합니다. `commerce.properties.example`을 `commerce.properties`로 복사합니다. 실제 커스텀 패키지, 포트, 임의 토큰, 쓰기 가능한 **절대 출력 경로**를 입력합니다. Properties 경로에는 `/`를 쓰세요. UTF-8 텍스트입니다.
3. 필요한 SAP 패키지만 추가합니다. 예: `de.hybris.platform.acceleratorstorefrontcommons.controllers.cms`. 실제 클래스 패키지는 사내 소스로 확인합니다. `de.hybris.platform` 전체를 첫 실험에 넣지 않습니다. 패키지 설정은 시작 시 고정되므로 바꾼 뒤 서버를 재시작합니다.
4. 실제 Commerce JVM에 `-javaagent:<절대경로>/dist/trace-agent.jar=<절대경로>/commerce.properties`를 추가하고 Debug로 시작합니다. 경로에 공백이 있으면 VM 옵션 전체를 따옴표로 감쌉니다. 서버 콘솔의 `[call-tracer] READY`를 확인합니다.
5. Eclipse 제어기의 프로그램 인수도 같은 `commerce.properties`로 바꿉니다. STATUS의 `transform_errors=0`을 확인합니다. `transformed`는 실제로 계측된 클래스 수이며, 대상 클래스를 처음 실행하기 전에는 0일 수 있습니다.
6. `AbstractCMSComponentController.handleComponent`의 `getView` 호출 직전/직후에 첫 후보 브레이크포인트를 설정합니다. 실제 소스의 실행 가능한 라인인지 확인합니다. 같은 요청·같은 스레드에서 두 지점에 멈추는지 먼저 확인합니다.
7. START/Resume/STOP/Resume 흐름으로 PDP 요청을 기록합니다. 해당 구간에서 Facade/Service 호출이 없으면 결과에도 나오지 않습니다. 필요한 데이터가 앞에서 이미 계산됐다면 시작점을 앞당겨야 합니다.

제어 채널은 IPv4 루프백 `127.0.0.1`에만 바인딩합니다. 설정 파일의 토큰으로 명령을 확인합니다. 여러 서버 JVM을 동시에 사용하면 포트와 출력 폴더를 분리합니다. 같은 포트의 다른 Agent를 제어하지 않도록 설정을 맞춥니다.

## 4. 결과 해석

CSV 열: `session, thread_id, thread_name, sequence, call_id, depth, event, relative_ns, method`.

- `ENTER`와 `EXIT`/`THROW`: 같은 세션·같은 `call_id`로 대응합니다. 메서드명에는 JVM 인자/반환값 descriptor가 포함됩니다.
- `sequence`: 스레드별 이벤트 순번입니다. 누락이 생기면 CSV 순번에 빈 구간이 보일 수 있습니다.
- `depth`: 계측된 프레임 기준 깊이이며, 전체 JVM 콜스택 깊이는 아닙니다.
- `PARTIAL_EXIT`/`PARTIAL_THROW`: START 전 진입해 기록 중 종료된 메서드입니다. 진입/깊이는 알 수 없어 `call_id=0`, `depth=-1`입니다.
- `*.open.csv`: STOP 시점까지 종료되지 않은 호출을 `OPEN_AT_STOP`으로 별도 표시합니다. CSV ENTER 자체가 누락됐더라도 이 파일에 열린 호출이 나타날 수 있습니다.
- 이전 세션에서 진입한 호출이 다음 세션 중 종료되면 새 세션의 EXIT로 기록하지 않습니다.
- `relative_ns`는 START 이후 상대 시간입니다. 브레이크포인트에서 기다린 시간도 포함하므로 순수 실행 시간으로 해석하지 않습니다.
- `events = enters + exits + partial_exits`. `exits`에는 THROW가 포함됩니다. 정상적인 저장 완료 시 `events = consumed + dropped`, 파일 저장 모드에서는 `consumed = saved`입니다.
- `saved`와 `bytes`는 이벤트 CSV 기준입니다. 헤더·열린 호출 sidecar·요약 TXT는 이벤트 저장 건수에 포함되지 않습니다. `stop_ms`는 STOP 처리 시작부터 CSV writer 종료까지이며 sidecar 기록을 포함합니다. 최종 요약 TXT 기록 시간·제어 응답 전송 시간은 제외됩니다.

STOP은 신규 수집을 닫고 버퍼를 비운 뒤 완료를 응답합니다. 파일 오류가 나면 `ERROR`와 `writer_error`를 표시하므로 해당 결과를 정상 기록으로 취급하지 않습니다. 대상 JVM을 강제 종료하면 STOP/정상 flush를 보장하지 않습니다.

반환값·인자·JSP 모델 데이터는 수집하지 않습니다. 생성자, 클래스 초기화, 추상/native/synthetic 메서드는 제외합니다. 비동기 요청의 인과관계 연결, START 시 기존 콜스택 스냅샷, GUI에서 패키지 변경, 동적 Attach는 아직 없습니다. 스레드 필터는 스레드를 재사용하는 다른 요청을 의미적으로 구분하지 못합니다. 범위를 좁게 잡고 동기 구간부터 사용하세요.

## 5. 실제 성능 측정 절차

동일 상품·동작·JDK·디버그 설정에서 워밍업 후 여러 번 반복합니다. A/B는 기록 CSV가 없으므로 구간 시간만 별도로 측정합니다.

|조건|설정|목적|
|---|---|---|
|A|javaagent 옵션 제거|기준 구간 실행 시간|
|B|Agent 로딩, START하지 않음|계측 대기 비용|
|C|Agent, START, 화면의 Discard 선택|동일 이벤트 생성/큐 처리, CSV 저장 제외|
|D|Agent, START, Discard 해제|실제 기록 비용|

IntelliJ Resume 직전부터 종료 브레이크포인트 이벤트 확인까지 같은 방법으로 측정합니다. 디버거 전송/표시 지연도 포함되는 측정임을 명시합니다. 버튼을 누르기까지의 대기, STOP 후 저장 시간, 전체 세션 시간과 구분합니다. 세션별 실행 시간·진입/종료/누락/저장 건수·파일 크기·STOP 시간을 남깁니다. C/D는 같은 이벤트 생성·직렬화·큐 경로를 사용하며 writer의 파일 쓰기만 달라집니다.

데모 자동 검증의 시간은 JDI 이벤트 전달 또는 제어용 JVM 시작 비용을 포함한 값입니다. **SAP Commerce의 로그량/성능이나 A/B/C/D 비교 결과가 아닙니다.**

## 6. 검증 기록

`test-results/breakpoint-check.txt`: 실제 JDWP 라인 브레이크포인트, Thread 정지, 외부 JVM의 START/STOP, 반복 세션, 구간 밖 제외, 예외/재귀/스레드 필터, CSV 대응/순번.

`test-results/runtime-check.txt`: Recorder API에 대한 구간 경계, 열린 호출, 이전 세션 EXIT 제외, 잘못된 토큰, 버퍼 포화 및 Discard 검사. 이 검증은 실제 업무 메서드 계측을 대신하는 성능 시험이 아닙니다.

`test-results/loader-check-default.txt`: 시스템 로더가 부모가 아닌 격리된 `URLClassLoader`에서도 bootstrap 기록기에 접근하고 호출을 저장하는지 검사합니다. 실제 SAP Commerce 클래스 로더 구조는 미검증입니다.

Byte Buddy 사용 방식은 [공식 튜토리얼](https://bytebuddy.net/partial/tutorial.partial.html)을 참고했습니다. 라이선스는 `THIRD-PARTY.md`와 `licenses`를 확인하세요.

로컬 Temurin 8u492에서도 격리 클래스 로더 대상에 Agent를 로딩하여 진입 3건·종료 3건(6건) 저장을 확인했습니다. 기록은 test-results/loader-check-alternate.txt에 있습니다. 사내 JDK 및 SAP Commerce 동작 검증은 별도로 필요합니다.

재빌드할 때는 제어 화면과 Agent를 로딩한 대상 JVM을 먼저 종료하세요. Windows에서 실행 중인 JAR는 덮어쓸 수 없습니다.

