# staticAnalyzer: 오버로드 메서드 판정 원리

대상 커밋: `816de66` (인자 타입 추정으로 오버로드 구분), `8df40c3` (.class에서 타입 읽기)

> 이 기능은 **오버라이드가 아니라 오버로드**를 가려낸다. 같은 이름의 메서드가 같은 인자 개수로 여러 개 있을 때 실제로 어느 것이 호출되는지 고르는 것이다.
> 오버라이드(하위 클래스가 같은 시그니처로 재정의)는 그 전부터 수신 객체 타입과 상속 계층을 따라가는 방식으로 처리하고 있었다.

## 배경: 그 전에는 왜 구분하지 못했나

이 도구는 JavaParser로 소스를 **문법 트리(AST)로만** 읽는다. 컴파일러나 symbol solver처럼 타입을 계산하지 않는다.
그래서 그 전에는 호출 `x.foo(a, b)`를 "이름이 `foo`이고 인자가 2개"라는 조건으로만 찾았다. 오버로드가 여러 개면 후보를 최대 3개까지 모두 출력했다.

어제 추가한 원리는 **컴파일러가 하는 오버로드 판정을 단순화해서 흉내 내는 것**이다. 세 단계로 이루어진다.

## ① 호출하는 쪽 인자의 타입을 "추정"한다

[`exprType()`](com/CallTreeExtractor.java)이 인자 식의 모양을 보고 타입을 정한다.

| 인자 식 | 추정 근거 |
|---|---|
| `"abc"`, `1`, `1L`, `true`, `null` | 리터럴 자체 → `String`, `int`, `long`, `boolean`, null |
| `(ProductData) x`, `new ArrayList<>()` | 캐스트 타입, `new` 타입 |
| `code` (지역 변수/파라미터) | 같은 메서드 안의 선언 타입 |
| `productFacade`, `this.xxx` (필드) | 필드 선언 타입 |
| `ProductOption.BASIC`, `Type.CONSTANT` | 그 클래스의 필드 타입, 또는 enum 자체 |
| `a + "x"` | 어느 한쪽이 String이면 String |
| `getFoo()` (메서드 호출 결과) | 호출되는 메서드의 **반환 타입**(재귀적으로 판정) |

어느 규칙에도 맞지 않으면 "모름(null)"으로 둔다. 억지로 추측하지 않는다.

## ② 후보 메서드의 파라미터 타입과 비교한다

[`compatible()`](com/CallTreeExtractor.java)은 인자 타입과 파라미터 타입을 비교해 세 가지 중 하나로 판정한다.

- **MATCH**: 다음 경우.
  - 타입이 같을 때
  - 기본형 확장이 될 때(`int`→`long`)
  - 박싱·언박싱이 될 때(`int`↔`Integer`)
  - 파라미터가 인자 타입의 상위 클래스나 인터페이스일 때
  - 파라미터가 `Object`일 때
- **NO**: 확실히 들어갈 수 없을 때. 예: `String`을 `int` 파라미터에 넘기는 경우.
- **UNKNOWN**: 인자 타입을 모르거나, 상속 계층을 끝까지 확인하지 못했을 때.

상위 타입은 다음 순서로 확인한다.

- 소스가 있는 클래스는 `extends`/`implements`를 따라 올라간다.
- `String`, `List`, `Map` 같은 JDK 타입은 코드에 직접 적어 둔 표(`JDK_SUPERS`)를 쓴다.
- 제네릭 타입 변수(`T`)와 가변 인자는 판정하지 않는다.

그다음 [`byArgTypes()`](com/CallTreeExtractor.java)가 후보를 좁힌다.

1. 인자 하나라도 NO가 나온 후보는 제외한다.
2. 남은 후보 중 모든 인자가 MATCH인 것이 **정확히 하나**면 그것으로 확정한다.
3. 아니면 남은 후보를 모두 출력하고 `[OVERLOAD-AMBIGUOUS n (이유)]`를 붙인다. 틀린 하나를 고르기보다 모호하다고 알리는 쪽을 택한 설계다.

## ③ 소스가 없는 클래스는 `.class` 파일에서 타입을 읽는다 (`8df40c3`)

SAP Commerce의 `ProductModel` 같은 model 클래스는 빌드 때 생성되므로 extension 안에 `.java`가 없다.
그래서 `product.getCode()`처럼 model getter를 인자로 넘기면 ①에서 반환 타입을 알 수 없었다.

해결 방법으로 `bin/platform/bootstrap/modelclasses`(없으면 `models.jar`)의 **바이트코드를 직접 읽는다**. 외부 라이브러리(ASM 등)는 쓰지 않는다. 원리는 이렇다.

- `.class` 파일은 JVM 명세로 형식이 고정되어 있다. 순서는 `0xCAFEBABE` → 상수 풀 → 자기 클래스, 상위 클래스, 인터페이스 → 필드 → 메서드.
- 메서드마다 **디스크립터** 문자열이 있고, 여기에 파라미터와 반환 타입이 그대로 적혀 있다.
  - 예: `(Ljava/lang/String;I)Ljava/util/List;` → `(String, int) → List`
- [`parseClass()`](com/CallTreeExtractor.java)는 상수 풀을 훑어 문자열을 모은 뒤 상위 타입, 필드 타입, 메서드 디스크립터**만** 읽는다. 메서드 본문(바이트코드)은 건너뛴다.
- [`descType()`](com/CallTreeExtractor.java)이 디스크립터를 `TRef`(타입 정보)로 바꾼다.
- 반환 타입이 제네릭 변수(`T`)인 메서드는 `Signature` 속성으로 알아내서 판정에서 뺀다.

이렇게 읽은 정보는 **타입 판정에만** 쓴다. model getter의 반환 타입, 소스가 없는 enum 상수, 상위 타입 확인이 여기에 해당한다. 호출 트리를 `.class` 안으로 펼치지는 않는다.

class path는 기본값 외에 `--class-path <dir|jar>` 또는 `CLASS_PATHS` 상수로 추가할 수 있다.

## 한계

컴파일러를 완전히 재현한 것은 아니라서 다음 경우에는 `AMBIGUOUS`가 남을 수 있다.

- 람다나 메서드 참조를 인자로 넘길 때
- 제네릭 추론이 필요할 때
- 삼항 연산자의 두 분기 타입이 서로 다를 때
- 가장 구체적인 메서드를 골라야 할 때(Java의 most specific 규칙은 구현하지 않음)

이런 경우에도 후보가 모두 출력되므로 누락은 생기지 않고, 사람이 확인하면 된다.
