# 스프링 컨텍스트(Spring Context)

작성일: 2026-11-26

스프링 컨텍스트는 **애플리케이션을 구성하는 객체의 생성, 의존관계 연결, 생명주기를 관리하고 실행에 필요한 기반 기능을 제공하는 Spring Framework의 컨테이너**다. 일반적으로 `org.springframework.context.ApplicationContext` 인터페이스와 그 구현 객체를 가리킨다.

처음에는 “필요한 객체를 만들어 연결해 주는 관리자”로 이해할 수 있다. 더 정확히는 설정 정보를 해석하여 객체 그래프를 구성하고, 객체의 초기화·종료와 여러 기반 기능을 조정하는 실행 기반이다. 스프링이 관리하는 객체를 **빈(Bean)**이라고 한다. [공식 설명: IoC 컨테이너와 빈](https://docs.spring.io/spring-framework/reference/core/beans/introduction.html)

이 문서는 Spring Framework의 공통 개념을 설명한다. Spring Boot 전용 기능은 별도로 구분한다. 예제의 `demo` 패키지와 클래스명은 설명용이며, 프로젝트마다 설정 파일명·컨텍스트 계층·사용 버전이 달라질 수 있다.

## 1. 핵심 용어와 서로의 관계

| 용어 | 의미 | 예 |
| --- | --- | --- |
| 클래스(Class) | 객체의 구조와 동작을 정의한 Java 타입 | `OrderService` |
| 객체·인스턴스(Object·Instance) | 실행 중 메모리에 존재하는 실체 | `new OrderService(repository)`의 결과 |
| 빈(Bean) | 스프링 컨테이너에 등록되어 관리되는 객체 | 등록된 `orderService` |
| 빈 정의(BeanDefinition) | 빈을 어떤 방식으로 생성하고 관리할지 나타내는 메타데이터 | 생성 클래스, 스코프, 생성자 인자 등 |
| IoC 컨테이너 | 객체 구성과 관리의 제어를 담당하는 기반 | `ApplicationContext` 구현체 |
| 의존성(Dependency) | 한 객체가 동작할 때 필요로 하는 다른 객체 | 서비스가 사용하는 저장소 |
| DI(Dependency Injection) | 필요한 의존성을 외부에서 전달하는 방식 | 생성자로 저장소 전달 |
| IoC(Inversion of Control) | 객체 구성 등의 제어를 외부 기반으로 옮기는 원리 | 컨테이너가 생성과 연결을 결정 |

여기서 **클래스, 빈 정의, 빈 인스턴스는 서로 다른 것**이다. 클래스가 하나여도 서로 다른 이름과 설정으로 빈 정의를 여러 개 등록할 수 있다. 하나의 빈 정의로 몇 개의 인스턴스를 만들지는 스코프에 따라 달라진다. [공식 설명: 빈 정의](https://docs.spring.io/spring-framework/reference/core/beans/definition.html)

## 2. 스프링 컨텍스트가 해결하는 문제

### 2.1 객체를 직접 조립하는 경우

서비스가 저장소를 사용한다고 하자. 컨테이너 없이도 다음과 같이 객체를 만들고 연결할 수 있다.

```java
OrderRepository repository = new OrderRepository();
OrderService service = new OrderService(repository);
service.placeOrder("ORDER-001");
```

이 경우 객체 생성과 연결을 결정하는 주체는 이 코드를 작성한 개발자다. 작은 프로그램에서는 충분하지만, 객체가 많아지면 구현체 선택, 설정값 전달, 생성 순서, 공통 자원 공유와 종료 처리를 함께 관리해야 한다.

스프링을 사용하면 개발자는 구성 규칙을 선언하고, 컨테이너가 그 규칙에 따라 객체를 조립한다. 업무 코드는 필요한 협력 객체를 생성자 등으로 전달받는다.

### 2.2 IoC와 DI의 차이

**IoC는 설계 원리이고 DI는 이를 구현하는 대표적인 방식**이다. DI 자체는 스프링 없이도 할 수 있다. 위 코드에서 생성자로 `repository`를 전달하는 것도 DI다.

스프링의 역할은 그 전달과 조립을 체계적으로 수행하는 것이다. 컨텍스트를 사용한다고 모든 업무 객체가 `ApplicationContext`를 직접 알아야 하는 것은 아니다. 보통 서비스는 필요한 다른 서비스나 저장소를 생성자로 받으며, 컨텍스트의 존재를 알 필요가 없다. [공식 설명: 의존성 주입](https://docs.spring.io/spring-framework/reference/core/beans/dependencies/factory-collaborators.html)

## 3. BeanFactory와 ApplicationContext

`BeanFactory`는 빈 조회와 관리의 기본 계약을 제공한다. `ApplicationContext`는 `BeanFactory` 계열 인터페이스를 확장하며 애플리케이션에 필요한 기능을 더한다.

```text
BeanFactory 계열
    └─ ApplicationContext
         ├─ 빈 조회와 의존성 연결
         ├─ 후처리기 자동 탐지·적용
         ├─ 환경 설정과 프로파일 접근
         ├─ 리소스 접근
         ├─ 메시지 국제화
         ├─ 애플리케이션 이벤트
         └─ 부모·자식 컨텍스트
```

위 그림은 기능 관계를 단순화한 것으로 Java 인터페이스의 전체 상속도를 뜻하지 않는다.

| 항목 | BeanFactory 수준 | 일반적인 ApplicationContext 구현 |
| --- | --- | --- |
| 빈 생성과 의존성 연결 | 기본 기능 제공 | 기본 기능 포함 |
| 후처리기 | 순수 팩터리 사용 시 직접 등록·호출 등의 구성이 필요 | 등록된 후처리기를 관례에 따라 탐지하고 적용 |
| 이벤트·국제화·환경 접근 | 기본 `BeanFactory` 계약의 범위 밖 | 관련 인터페이스와 기반 기능 제공 |
| 주된 사용 위치 | 프레임워크 내부나 세밀한 컨테이너 구성 | 일반 애플리케이션의 컨테이너 진입점 |

실제 `ApplicationContext` 구현은 내부의 빈 팩터리에 빈 관리 작업을 위임하기도 한다. 따라서 컨텍스트가 빈 생성의 모든 내부 동작을 직접 구현한다고 이해할 필요는 없다. [공식 설명: BeanFactory API](https://docs.spring.io/spring-framework/reference/core/beans/beanfactory.html)

## 4. 빈을 등록하는 방법

### 4.1 Java 설정: @Configuration과 @Bean

`@Configuration`은 구성 클래스를 나타내고, `@Bean` 메서드는 컨테이너가 관리할 객체를 제공한다.

```java
@Configuration
class AppConfig {
    @Bean
    OrderRepository orderRepository() {
        return new OrderRepository();
    }

    @Bean
    OrderService orderService(OrderRepository repository) {
        return new OrderService(repository);
    }
}
```

기본 빈 이름은 `@Bean` 메서드명이다. 위 설정에서는 `orderRepository`와 `orderService`가 된다. 이름은 `@Bean("mainOrderService")`처럼 지정할 수도 있다.

`new`를 썼다는 이유로 빈이 아닌 것은 아니다. 위처럼 컨테이너가 호출하는 `@Bean` 메서드에서 생성해 반환한 객체는 빈으로 관리된다. 반대로 일반 업무 코드에서 임의로 `new`한 객체는 그 사실만으로 자동 등록되지 않는다. [공식 설명: @Bean과 @Configuration](https://docs.spring.io/spring-framework/reference/core/beans/java/basic-concepts.html)

### 4.2 컴포넌트 탐색

스프링은 지정한 패키지에서 컴포넌트 후보를 탐색하여 빈 정의를 등록할 수 있다.

| 애노테이션 | 주로 표현하는 역할 |
| --- | --- |
| `@Component` | 일반 컴포넌트 |
| `@Service` | 업무 서비스 |
| `@Repository` | 저장소·데이터 접근 계층 |
| `@Controller` | Spring MVC 컨트롤러 |
| `@RestController` | 응답 본문 처리를 기본으로 하는 MVC 컨트롤러 |

```java
@Repository
class OrderRepository {
    // 저장소 기능
}

@Service
class OrderService {
    private final OrderRepository repository;

    OrderService(OrderRepository repository) {
        this.repository = repository;
    }
}

@Configuration
@ComponentScan("demo.order")
class AppConfig {
}
```

클래스에 애노테이션을 붙여도 **탐색 범위에 포함되지 않으면 등록되지 않는다**. 위 코드는 관련 클래스가 `demo.order` 또는 그 하위 패키지에 있다는 전제다. 생성자가 하나라면 일반적인 애노테이션 기반 구성에서 `@Autowired`를 생략할 수 있다. [공식 설명: 컴포넌트 탐색](https://docs.spring.io/spring-framework/reference/core/beans/classpath-scanning.html), [@Autowired](https://docs.spring.io/spring-framework/reference/core/beans/annotation-config/autowired.html)

### 4.3 XML 설정

XML에서도 동일한 의존관계를 선언할 수 있다. 다음은 `demo.order.OrderRepository`와 `OrderRepository`를 받는 생성자가 있는 `demo.order.OrderService`를 전제로 한 설정이다.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<beans xmlns="http://www.springframework.org/schema/beans"
       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
       xsi:schemaLocation="http://www.springframework.org/schema/beans
                           https://www.springframework.org/schema/beans/spring-beans.xsd">

    <bean id="orderRepository" class="demo.order.OrderRepository"/>

    <bean id="orderService" class="demo.order.OrderService">
        <constructor-arg ref="orderRepository"/>
    </bean>
</beans>
```

`ref`는 다른 빈을 참조한다는 뜻이다. 문자열 값인 `value`와 다르다. XML에 `<bean>`을 썼다면 해당 클래스에 반드시 `@Service`나 `@Component`가 있어야 하는 것은 아니다.

XML, Java 설정, 컴포넌트 탐색은 함께 사용할 수도 있다. 다만 같은 클래스를 여러 경로로 등록하면 의도치 않게 여러 빈이 생길 수 있으므로 등록 경로를 확인해야 한다. [공식 설명: 컨테이너 구성과 설정 메타데이터](https://docs.spring.io/spring-framework/reference/core/beans/basics.html)

## 5. 컨텍스트를 직접 실행하는 전체 예제

다음 예제는 Spring Boot 없이 `ApplicationContext`를 실행한다. 앞 절의 코드 조각과 별개의 독립 예제이며, `spring-context`와 그 전이 의존성이 클래스패스에 있어야 한다.

파일명은 `ContextDemo.java`다.

```java
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

public class ContextDemo {
    public static void main(String[] args) {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(AppConfig.class)) {

            OrderService first = context.getBean(OrderService.class);
            OrderService second = context.getBean(OrderService.class);

            first.placeOrder("ORDER-001");
            System.out.println("same instance: " + (first == second));
        }
    }

    @Configuration
    public static class AppConfig {
        @Bean
        public OrderRepository orderRepository() {
            return new OrderRepository();
        }

        @Bean
        public OrderService orderService(OrderRepository repository) {
            return new OrderService(repository);
        }
    }

    public static class OrderRepository {
        public void save(String orderId) {
            System.out.println("saved: " + orderId);
        }
    }

    public static class OrderService {
        private final OrderRepository repository;

        public OrderService(OrderRepository repository) {
            this.repository = repository;
        }

        public void placeOrder(String orderId) {
            repository.save(orderId);
        }
    }
}
```

표준 출력의 예상 결과는 다음과 같다. 로깅 구성에 따라 스프링 로그가 추가될 수 있다.

```text
saved: ORDER-001
same instance: true
```

이 예제에서는 컨텍스트 생성자가 설정을 등록하고 초기화까지 수행한다. 두 번 조회한 `OrderService`가 같은 이유는 기본 스코프가 싱글턴이기 때문이다. `try` 블록을 벗어나면 컨텍스트가 닫힌다. 일반 업무 코드에서는 이런 직접 조회보다 생성자 주입으로 협력 객체를 전달받는 구성을 주로 사용한다. [공식 설명: 컨테이너 사용](https://docs.spring.io/spring-framework/reference/core/beans/basics.html), [빈 스코프](https://docs.spring.io/spring-framework/reference/core/beans/factory-scopes.html)

## 6. 의존성을 연결하는 과정

컨테이너가 `OrderService`를 생성할 때는 생성자 매개변수의 타입 등을 보고 적합한 빈을 찾는다. 후보가 여럿이면 이름, 한정자와 우선순위 같은 조건으로 대상을 결정한다.

| 방식 | 형태 | 특징 |
| --- | --- | --- |
| 생성자 주입 | `OrderService(OrderRepository repository)` | 생성 시 필수 의존성을 명시하고 `final` 필드를 사용할 수 있음 |
| 세터 주입 | `setRepository(repository)` | 생성 후 속성을 설정하며 선택적 구성 등에 사용 가능 |
| 필드 주입 | `@Autowired private OrderRepository repository;` | 컨테이너가 필드에 직접 설정하며 일반 Java 코드에서의 명시적 조립이 어려워짐 |

동일 타입의 빈이 여러 개라면 대표적으로 다음 방법을 사용한다.

- `@Primary`: 단일 값 주입에서 우선할 후보를 지정한다.
- `@Qualifier`: 주입 지점의 조건에 맞는 후보를 좁힌다.
- `List<PaymentGateway>` 같은 컬렉션 주입: 여러 후보를 함께 받는다.

`@Qualifier`를 단순히 “빈 이름으로만 찾는 기능”으로 이해하면 범위가 좁다. 타입에 맞는 후보를 의미 있는 조건으로 한정하는 기능이며, 빈 이름이 그 조건에 사용될 수도 있다. [공식 설명: 자동 주입](https://docs.spring.io/spring-framework/reference/core/beans/annotation-config/autowired.html), [한정자](https://docs.spring.io/spring-framework/reference/core/beans/annotation-config/autowired-qualifiers.html)

## 7. 빈 정의를 읽는 것과 객체를 만드는 것

빈 정의 등록은 객체 생성의 준비 단계다. XML을 읽었다고 그 안의 모든 빈 인스턴스가 그 순간 생성되었다고 단정할 수 없다.

```text
구성 입력
  XML / Java 설정 / 컴포넌트 탐색 / 프로그램 등록
    ↓
빈 정의 등록
  이름, 생성 방법, 스코프, 의존성 등
    ↓
후처리와 의존성 해석
    ↓
필요한 빈 생성·초기화
    ↓
업무 코드에 주입하거나 조회하여 사용
```

일반적인 `ApplicationContext`는 초기화 과정에서 지연 설정이 없는 싱글턴 빈을 미리 생성한다. 그래서 잘못된 의존성이나 설정값이 기동 중 발견될 수 있다.

`@Lazy` 또는 XML의 `lazy-init="true"`를 사용하면 필요한 시점까지 생성을 늦출 수 있다. 다만 즉시 생성되는 다른 빈이 그 빈을 직접 필요로 하면 의존성을 해결하기 위해 기동 중 생성될 수 있다. **스코프와 지연 생성은 서로 다른 설정**이다. [공식 설명: 지연 초기화](https://docs.spring.io/spring-framework/reference/core/beans/dependencies/factory-lazy-init.html)

## 8. 빈의 생명주기와 후처리기

### 8.1 일반적인 생성·초기화 흐름

일반적인 빈 생성 과정을 개념적으로 나타내면 다음과 같다.

```text
빈 정의 준비
    ↓
의존성 해결과 인스턴스 생성
    ↓
필요한 속성·의존성 설정
    ↓
Aware 콜백 등 컨테이너 연동 처리
    ↓
초기화 전 후처리
    ↓
초기화 콜백
    ↓
초기화 후 후처리·필요 시 프록시 적용
    ↓
사용 가능한 빈 제공
```

생성자 주입은 인스턴스 생성과 결합되어 있다. 또한 특수 후처리기나 순환 참조 처리에서는 경로가 달라질 수 있으므로, 위 그림을 모든 내부 호출의 엄밀한 순서도로 읽으면 안 된다.

대표적인 초기화 콜백은 `@PostConstruct`, `InitializingBean.afterPropertiesSet()`, 사용자 지정 초기화 메서드다. 서로 다른 메서드로 모두 구성하면 일반적으로 이 순서로 호출된다. `@PostConstruct` 자체도 관련 후처리기가 실행한다. 해당 애노테이션의 패키지는 사용 버전에 따라 확인해야 한다. 예를 들어 Spring Framework 6 이상에서는 `jakarta.annotation.PostConstruct`를 사용한다. [공식 설명: 생명주기 콜백](https://docs.spring.io/spring-framework/reference/core/beans/factory-nature.html), [@PostConstruct와 @PreDestroy](https://docs.spring.io/spring-framework/reference/core/beans/annotation-config/postconstruct-and-predestroy-annotations.html)

### 8.2 두 후처리기의 차이

| 확장 지점 | 주로 다루는 대상 | 역할 |
| --- | --- | --- |
| `BeanFactoryPostProcessor` | 빈 정의·빈 팩터리 설정 | 일반 빈 생성 전에 설정 메타데이터 등을 조정 |
| `BeanPostProcessor` | 생성 중인 빈 인스턴스 | 초기화 전후 처리, 인스턴스 장식·대체 등에 관여 |

빈 정의 등록 단계에 개입하는 확장으로는 `BeanDefinitionRegistryPostProcessor`도 있다. 애노테이션 주입 처리와 자동 프록시 생성 등은 이러한 확장 기반과 연결된다. 후처리기는 단순히 “빈이 완성된 다음 한 번 실행되는 기능”이 아니다. [공식 설명: 컨테이너 확장 지점](https://docs.spring.io/spring-framework/reference/core/beans/factory-extension.html)

### 8.3 종료

컨텍스트가 정상 종료될 때 관리 대상 빈의 `@PreDestroy`, `DisposableBean.destroy()`, 사용자 지정 종료 메서드 등이 실행될 수 있다. 예를 들면 연결 풀과 작업 실행기의 자원을 정리하는 데 사용한다. `close()`와 JVM 종료 훅은 정상적인 종료 경로를 구성하는 수단이며, 프로세스 강제 종료까지 콜백 실행을 보장하지는 않는다.

프로토타입 빈은 생성·주입·초기화 이후의 전체 생명주기를 컨테이너가 추적하지 않으므로 일반적으로 종료 콜백이 자동 실행되지 않는다. [공식 설명: 종료 콜백](https://docs.spring.io/spring-framework/reference/core/beans/factory-nature.html), [프로토타입 스코프](https://docs.spring.io/spring-framework/reference/core/beans/factory-scopes.html)

## 9. 스코프: 빈을 몇 개 만들고 얼마나 공유하는가

| 스코프 | 인스턴스의 범위 | 주의점 |
| --- | --- | --- |
| `singleton` | 컨테이너의 해당 빈 정의에 대해 공유 인스턴스 하나 | 기본값. JVM 전체에서 클래스별 하나라는 뜻은 아님 |
| `prototype` | 컨테이너에 해당 빈 생성·조회를 요청할 때마다 새 인스턴스 | 업무 메서드 호출마다 자동 재생성되는 것은 아님 |
| `request` | HTTP 요청별 | 해당 웹 스코프를 지원하는 환경 필요 |
| `session` | HTTP 세션별 | 같은 세션에서 공유 |
| `application` | ServletContext별 | Spring 싱글턴과 범위가 다름 |
| `websocket` | WebSocket 세션별 | 관련 웹 메시징 환경 필요 |

같은 클래스를 서로 다른 두 빈 정의로 등록하면 싱글턴이라도 각각의 인스턴스가 존재할 수 있다. 서로 다른 컨텍스트에 각각 등록한 경우도 마찬가지다. 별칭(alias)만 추가하는 것은 새로운 빈 정의를 만드는 것과 다르다.

싱글턴 빈에 프로토타입 빈을 생성자로 주입하면 그 의존성은 보통 싱글턴 생성 시 한 번 해결된다. 이후 매 업무 호출에서 새 객체가 필요하다면 `ObjectProvider`를 통해 필요할 때 요청하거나 적절한 스코프 프록시 등을 구성해야 한다. [공식 설명: 빈 스코프와 서로 다른 스코프의 의존성](https://docs.spring.io/spring-framework/reference/core/beans/factory-scopes.html)

### 싱글턴과 스레드 안전성

싱글턴은 공유 범위에 관한 설정이며 동기화 기능이 아니다. 웹 서버에서 여러 요청이 같은 서비스 빈을 동시에 사용할 수 있다. 요청마다 달라지는 사용자 ID나 주문 번호를 공유 서비스의 변경 가능한 필드에 저장하면 요청 사이에 값이 섞일 수 있다. 보통 요청별 값은 메서드 인자와 지역 변수로 전달하고, 공유 상태는 그에 맞는 동시성 설계를 적용한다.

## 10. 부모·자식 컨텍스트와 빈의 가시성

한 애플리케이션에 컨텍스트가 반드시 하나만 존재하는 것은 아니다. 컨텍스트 사이에 부모·자식 관계를 구성할 수 있다.

```text
부모 컨텍스트: 공통 서비스와 저장소
    ├─ 자식 A: 웹 화면용 컨트롤러와 웹 설정
    └─ 자식 B: API용 컨트롤러와 웹 설정
```

일반적인 이름 기반 조회의 가시성은 다음과 같다.

| 조회 주체 | 조회 대상 | 기본 관계 |
| --- | --- | --- |
| 자식 | 자기 컨텍스트의 빈 | 조회 가능 |
| 자식 | 부모의 빈 | 로컬에 대상이 없으면 부모에서 조회 가능 |
| 부모 | 자식의 빈 | 부모 관계만으로 자식까지 찾아 내려가지 않음 |
| 자식 A | 형제인 자식 B의 빈 | 부모를 공유한다는 이유만으로 조회되지 않음 |

자식에 부모와 같은 이름의 빈이 있으면 자식에서 그 이름을 조회할 때 자식 빈이 부모 빈을 가릴 수 있다. 이는 부모의 빈 정의를 삭제하거나 부모 객체를 교체하는 것과 다르다. **부모 빈에 이미 주입된 의존성이 자식의 같은 이름 빈으로 자동 교체되는 것도 아니다.**

또한 모든 조회 API가 부모의 빈 목록까지 합치는 것은 아니다. 예를 들어 `getBeanDefinitionNames()`는 해당 팩터리의 로컬 정의 목록을 확인하는 용도이므로 부모까지 조사하려면 계층을 함께 확인해야 한다. [공식 API: ApplicationContext](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/context/ApplicationContext.html), [공식 API: ListableBeanFactory](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/beans/factory/ListableBeanFactory.html)

### Servlet 기반 웹 애플리케이션의 전통적인 구성

Spring MVC에서는 루트 `WebApplicationContext`에 공통 서비스·저장소를 두고, 각 `DispatcherServlet`의 자식 컨텍스트에 컨트롤러·웹 설정을 두는 구성이 가능하다. `ContextLoaderListener`가 루트 컨텍스트를 초기화하고 `DispatcherServlet`이 서블릿 컨텍스트를 초기화하는 방식이 대표적이다.

다만 모든 웹 애플리케이션이 반드시 루트와 자식 컨텍스트를 나누는 것은 아니다. 단일 컨텍스트 구성도 가능하다. [공식 설명: Spring MVC의 컨텍스트 계층](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-servlet/context-hierarchy.html)

## 11. XML 파일과 컨텍스트는 일대일 관계가 아니다

하나의 컨텍스트가 여러 XML을 읽을 수 있다. 반대로 같은 XML을 서로 다른 컨텍스트에서 읽으면 각 컨텍스트에 빈 정의가 등록될 수 있다.

```text
공통 컨텍스트
  ├─ services.xml
  ├─ repositories.xml
  └─ infrastructure.xml

웹 컨텍스트
  ├─ controllers.xml
  └─ web-settings.xml
```

XML의 `<import resource="services.xml"/>`는 설정 리소스를 추가로 읽는 기능이며 그 자체로 새 자식 컨텍스트를 만드는 선언은 아니다. 파일 이름이 `*-spring.xml` 또는 `*-servlet.xml`이라고 해서 항상 자동으로 로드되는 것도 아니다. 실제로 어떤 부트스트랩 코드나 로더가 어떤 위치·패턴의 설정을 읽는지 확인해야 한다. [공식 설명: 설정 파일 조합과 import](https://docs.spring.io/spring-framework/reference/core/beans/basics.html)

같은 컨텍스트에 같은 이름의 빈 정의가 여러 번 등록되면 덮어쓰기 허용 여부와 등록 순서가 문제가 된다. 허용되지 않으면 오류가 나고, 허용되는 구성에서는 뒤의 정의가 앞의 정의를 대체할 수 있다. 정책은 사용하는 프레임워크와 설정에 따라 확인해야 한다. **“동일 이름이면 언제나 마지막 XML이 이긴다”라고 일반화할 수 없다.** [공식 설명: 빈 이름과 정의 덮어쓰기](https://docs.spring.io/spring-framework/reference/core/beans/definition.html)

XML의 `<bean parent="baseService">`는 빈 정의 설정을 상속하는 기능이다. 컨텍스트의 부모·자식 관계와는 별개의 개념이다. [공식 설명: 빈 정의 상속](https://docs.spring.io/spring-framework/reference/core/beans/child-bean-definitions.html)

## 12. 빈 관리 이외에 제공하는 기능

| 기능 | 관련 요소 | 용도 |
| --- | --- | --- |
| 외부 설정 접근 | `Environment`, `PropertySource` | 환경 변수·시스템 속성·등록된 설정 소스에서 값 조회 |
| 프로파일 | `@Profile`, 활성 프로파일 | 환경에 따라 등록할 빈 그룹 선택 |
| 리소스 접근 | `Resource`, `ResourceLoader` | 클래스패스·파일 등의 자원을 일관된 방식으로 접근 |
| 메시지 국제화 | `MessageSource` | 메시지 코드와 언어에 따른 문구 조회 |
| 애플리케이션 이벤트 | 이벤트 발행과 리스너 | 컨텍스트 내부 컴포넌트 사이의 알림 전달 |

환경 설정값은 예를 들어 `context.getEnvironment().getProperty("shop.name")`으로 조회할 수 있다. `${shop.name}` 같은 자리표시자 해석도 구성된 설정 처리 기반과 연결된다.

프로파일은 이름이 붙은 빈 구성 그룹을 활성화하는 방식이다. `@Profile("dev")`인 빈은 해당 프로파일이 활성화된 경우 등록 대상이 된다. 파일이 프로젝트에 있다는 이유만으로 모든 설정 파일의 값이 자동으로 읽히는 것은 아니다. 특히 `application.properties`·`application.yml`의 관례적 로딩은 Spring Boot의 설정 처리와 구분해야 한다. [공식 설명: Environment와 프로파일](https://docs.spring.io/spring-framework/reference/core/beans/environment.html), [컨텍스트의 추가 기능](https://docs.spring.io/spring-framework/reference/core/beans/context-introduction.html), [Spring Boot 외부 설정](https://docs.spring.io/spring-boot/reference/features/external-config.html)

## 13. 프록시, AOP, 트랜잭션과의 관계

컨텍스트에서 주입·조회한 빈은 업무 클래스의 원본 객체가 아니라 그 앞을 감싸는 **프록시 객체**일 수 있다. 스프링 AOP는 프록시를 통해 업무 메서드 호출 전후에 부가 처리를 적용한다.

```text
호출자
  → 주입된 프록시
      → 트랜잭션 등 부가 처리
          → 실제 업무 객체
```

프록시에는 JDK 동적 프록시와 클래스 기반 프록시 등이 사용된다. 따라서 실제 실행 클래스명이 소스에 작성한 클래스명과 다를 수 있고, 클래스 기반 프록시에서는 `final` 클래스·메서드 같은 제약을 고려해야 한다. [공식 설명: 프록시 메커니즘](https://docs.spring.io/spring-framework/reference/core/aop/proxying.html)

`@Transactional`도 관련 트랜잭션 관리 설정과 관리자가 구성되어 있어야 의미 있게 처리된다. 애노테이션을 붙였다는 이유만으로 모든 호출에 무조건 트랜잭션이 적용되는 것은 아니다.

일반적인 프록시 방식에서는 같은 객체 내부에서 `this.otherMethod()`로 호출하면 프록시를 통과하지 않는다. 따라서 그 호출만으로 `otherMethod()`에 선언한 새 트랜잭션 속성이 적용되지는 않는다. 이미 실행 중인 트랜잭션 안에서 호출될 수는 있다. 또한 생성자나 `@PostConstruct` 시점에 완성된 트랜잭션 프록시의 동작을 기대해서는 안 된다. AspectJ 방식 등은 별도의 적용 모델을 사용한다. [공식 설명: @Transactional과 프록시 적용 범위](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)

## 14. Spring Framework와 Spring Boot의 관계

**Spring Framework는 컨테이너와 DI 등의 기반을 제공하고, Spring Boot는 이를 사용하는 애플리케이션의 시작과 구성을 편리하게 한다.**

| 구분 | 담당하는 대표 기능 |
| --- | --- |
| Spring Framework | 빈 컨테이너, DI, AOP, 트랜잭션 기반, Spring MVC 등 |
| Spring Boot | 기동 절차, 조건 기반 자동 설정, 외부 설정 관례, 실행·운영 편의 등 |

```java
ConfigurableApplicationContext context =
        SpringApplication.run(MyApplication.class, args);
```

이 호출은 애플리케이션 컨텍스트를 생성·초기화하고 반환한다. 필요한 import는 각각 `org.springframework.context.ConfigurableApplicationContext`와 `org.springframework.boot.SpringApplication`이다. `MyApplication`은 애플리케이션의 구성 클래스다.

Boot 자동 설정은 클래스패스의 라이브러리와 이미 등록된 빈 등의 조건을 바탕으로 필요한 구성을 제공한다. 예를 들어 사용자가 특정 빈을 직접 제공하면 관련 자동 설정이 물러나도록 설계된 경우가 있다. Boot가 컨텍스트를 대체하는 것은 아니며, 5절처럼 Boot 없이도 컨텍스트를 사용할 수 있다. [공식 설명: SpringApplication](https://docs.spring.io/spring-boot/reference/features/spring-application.html), [자동 설정](https://docs.spring.io/spring-boot/reference/using/auto-configuration.html)

## 15. 비슷한 이름의 개념 구별

| 개념 | 무엇을 뜻하는가 | 스프링 컨텍스트와의 관계 |
| --- | --- | --- |
| `ApplicationContext` | 스프링의 빈 관리와 애플리케이션 기반 기능 | 이 문서의 주제 |
| `WebApplicationContext` | 웹 환경과 연계된 ApplicationContext | 웹에 특화된 확장 |
| `ServletContext` | 서블릿 컨테이너가 웹 애플리케이션별로 제공하는 환경 | WebApplicationContext와 연결될 수 있지만 별도 객체 |
| URL 컨텍스트 경로 | `/shop` 같은 웹 애플리케이션의 경로 접두부 | 빈 컨테이너 자체를 뜻하지 않음 |
| 요청·세션 | HTTP 요청과 사용자 세션의 범위·상태 | 특정 빈 스코프와 연계될 수 있음 |
| `FactoryBean<T>` | 다른 객체를 제공하는 스프링 확장 계약 | 컨테이너인 BeanFactory와 별개 |
| `@Bean` | 객체를 빈으로 제공하는 설정 메서드 표시 | 빈을 선언하는 수단 |

특히 `BeanFactory`와 `FactoryBean`은 단어 순서만 다른 동의어가 아니다. `FactoryBean`으로 등록된 이름을 보통 방식으로 조회하면 그 팩터리가 제공하는 객체를 받으며, 팩터리 자체는 `&` 접두부를 사용하여 조회한다. [공식 설명: FactoryBean](https://docs.spring.io/spring-framework/reference/core/beans/factory-extension.html), [웹 컨텍스트](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-servlet/context-hierarchy.html)

## 16. 자주 발생하는 오류와 조사 방향

| 증상·대표 예외 | 가능한 원인 | 먼저 확인할 사항 |
| --- | --- | --- |
| `NoSuchBeanDefinitionException` | 필요한 빈이 없거나 현재 컨텍스트에서 보이지 않음 | 등록 경로, 탐색 패키지, 프로파일, 컨텍스트 계층 |
| `NoUniqueBeanDefinitionException` | 단일 주입 지점에 적합한 후보가 여러 개 | 중복 등록, `@Primary`, `@Qualifier` |
| `UnsatisfiedDependencyException` | 생성자·필드 등의 의존성 해결 실패 | 문제 주입 지점과 안쪽 원인 예외 |
| `BeanCreationException` | 생성자·초기화·설정 처리 등에서 실패 | 가장 구체적인 원인 예외와 대상 빈 |
| `BeanCurrentlyInCreationException` | 생성 중인 빈에 다시 의존하는 등의 문제 | 순환 의존관계 |
| 자리표시자 해석 실패 | 필요한 속성이 없거나 설정 소스를 읽지 못함 | 키 이름, 로딩 경로, 활성 환경 |
| 빈 정의 중복 오류 | 같은 이름의 중복 정의와 덮어쓰기 제한 | 각 정의의 출처와 중복 정책 |
| 트랜잭션이 기대대로 적용되지 않음 | 비관리 객체, 자기 호출, 관련 설정 누락 등 | 빈 등록 여부, 호출 경로, 트랜잭션 관리자 |

예외 이름 하나만으로 원인을 확정하지 말고 중첩된 원인 예외를 함께 읽어야 한다. 예를 들어 `BeanCreationException` 안쪽 원인이 DB 접속 실패일 수도 있으므로 모든 기동 실패가 빈 등록 문제인 것은 아니다.

생성자 주입의 순환 의존성은 컨테이너가 두 객체의 생성을 완료할 수 없게 만든다. 다른 주입 방식으로 일부 순환이 처리될 수 있더라도 모든 구성이 지원되는 것은 아니다. 먼저 책임 분리와 의존 방향을 검토해야 한다. [공식 설명: 의존성 해결과 순환 의존성](https://docs.spring.io/spring-framework/reference/core/beans/dependencies/factory-collaborators.html)

## 17. 이 저장소의 Spring XML 분석 작업에 적용하기

이 저장소처럼 빈 정의와 XML 로딩을 분석할 때는 **파일에 적힌 선언과 실행 중 컨텍스트의 실제 상태를 구분**해야 한다. 다음은 앞 절의 개념을 분석 작업에 적용한 조사 원칙이다.

1. **실행 구성을 확인한다.** 어떤 애플리케이션·웹 모듈·서블릿의 컨텍스트를 조사하는지 정한다.
2. **로딩 진입점을 찾는다.** 시작 코드, 로더 설정, `web.xml` 등에서 최초 설정 리소스를 확인한다.
3. **읽히는 설정을 따라간다.** XML import, Java 설정, 탐색 범위와 프로그램 등록을 조사한다.
4. **소속 컨텍스트를 기록한다.** 같은 빈 이름이라도 어느 컨텍스트에 등록되는지 구별한다.
5. **조건과 순서를 확인한다.** 프로파일, 설정값, 등록 순서, 중복 허용 정책을 확인한다.
6. **정의와 인스턴스를 구분한다.** 추상 빈 정의, 지연 생성, 스코프, FactoryBean, 후처리기를 고려한다.
7. **실행 상태와 대조한다.** 정적 분석의 후보를 기동 로그나 실제 컨텍스트 조회 결과와 비교한다.

예를 들어 XML A와 B에서 같은 `orderService`를 찾았다는 사실만으로 최종 사용 빈을 확정할 수 없다. 둘이 같은 컨텍스트에 로드되는지, 등록 조건이 성립하는지, 중복 정책과 순서가 무엇인지에 따라 결과가 달라진다. 서로 다른 자식 컨텍스트에 각각 등록된 경우에는 둘 다 존재할 수 있다.

또한 파일의 디렉터리 배치와 컨텍스트 계층은 서로 다른 구조다. SAP Commerce 같은 플랫폼에서는 플랫폼이 구성하는 실제 로더와 컨텍스트 관계를 별도로 확인해야 한다. 이 문서는 특정 배포의 코어·웹·서블릿 계층이나 최종 빈을 확정하지 않는다.

## 18. 참고 문서 안내

본문의 링크는 Spring 공식 문서다. `reference`와 `current` 주소는 시간이 지나면 다른 버전의 문서를 보여 줄 수 있다. 실제 프로젝트에 적용할 때는 해당 프로젝트가 사용하는 Spring Framework·Spring Boot 버전의 문서를 선택해야 한다.

- 개념의 출발점: [IoC 컨테이너와 빈](https://docs.spring.io/spring-framework/reference/core/beans/introduction.html)
- 컨테이너 구성: [Container Overview](https://docs.spring.io/spring-framework/reference/core/beans/basics.html)
- 핵심 API: [ApplicationContext](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/context/ApplicationContext.html)
- 객체의 범위: [Bean Scopes](https://docs.spring.io/spring-framework/reference/core/beans/factory-scopes.html)
- 웹 계층: [Context Hierarchy](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-servlet/context-hierarchy.html)
- Boot 기동: [SpringApplication](https://docs.spring.io/spring-boot/reference/features/spring-application.html)
