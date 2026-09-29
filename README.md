# 금융 이체 시스템 (Bank Transfer System)

Java Swing, JDBC, MySQL을 활용하여 구현한 금융 이체 시스템입니다.

회원가입, 로그인, 계좌 생성, 계좌 조회, 이체, 거래내역 조회 기능을 구현했으며, 실제 금융 시스템에서 고려되는 계좌 검증, 이체 한도, 트랜잭션, 동시성 제어, 데드락 방지 등을 반영하여 데이터 정합성을 유지하도록 이체 프로세스를 구현했습니다. 요청 ID 기반 중복 처리 방지와 비밀번호 해시 저장을 추가하여 재시도와 인증 정보 처리도 보완했습니다.

## 핵심 기술

- 🔒 JDBC Transaction (Commit / Rollback)
- 🔒 SELECT ... FOR UPDATE
- 🔒 Deadlock Prevention (Lock Ordering)
- 🔁 Request ID 기반 멱등성 및 결과 미확정 요청 복구
- 🔑 PBKDF2-HMAC-SHA256 비밀번호 해시
- ⚡ Composite Index / Cursor Pagination (Transaction History)
- 🏗 Layered Architecture (Domain / DAO / Service / GUI)

--- 

# 주요 기능

- 회원가입 및 로그인
- 계좌 생성 및 계좌 조회
- 계좌 간 이체
- 거래내역 전체 조회
- 기간별 거래내역 조회
- 거래 상세 조회

---

# 핵심 구현 내용

---

## 시스템 설계

- Domain - DAO - Service - GUI 계층형 아키텍처 설계
- Service 계층 중심의 비즈니스 로직 구현
- 업무 상황별 사용자 정의 Exception 분리

---

## 금융 업무 로직

### 금융 이체 검증

이체 요청값과 계좌 소유권·비밀번호를 확인하고, 계좌 잠금을 획득한 뒤 변경 가능한 잔액·상태·한도를 다시 검증하도록 구현했습니다.

- 이체 요청값 검증
- 출금 계좌 존재 여부 및 본인 계좌 확인
- 출금 계좌 상태 확인
- 입금 계좌 존재 여부 및 상태 확인
- 계좌 비밀번호 검증
- 잔액 검증
- 1회 이체 한도 검증
- 1일 이체 한도 검증

모든 검증을 통과한 경우에만 실제 이체가 수행됩니다.

### 이체 한도

- 1회 이체 한도
- 1일 이체 한도

잠금 획득 후 DB 세션의 날짜를 한 번 확정하여 `business_date`로 저장합니다. 같은 업무일의 성공한 이체 금액을 합산하고 현재 요청 금액을 더해 일일 한도를 검증합니다. 합계 계산은 `BigDecimal`을 사용하며, 금액은 Java `long`과 MySQL `BIGINT`로 처리합니다.

### 거래내역 조회

- 전체 거래 조회
- 기간별 거래 조회
- 거래 상세 조회

조회 대상 계좌를 기준으로 입금과 출금을 구분하여 표시합니다. 거래내역은 `(t_created_at, transaction_id)`를 커서로 사용해 다음 목록을 조회하며, 조회 전 계좌 소유권을 확인합니다.

---

## 🔒 데이터 정합성 보장

### JDBC 트랜잭션

출금, 입금, 거래내역 저장을 하나의 JDBC 연결과 트랜잭션으로 처리합니다. 모든 작업이 성공하면 Commit하고, 처리 중 오류가 발생하면 Rollback을 시도합니다. Commit 응답이나 Rollback에 문제가 있어 결과를 확정할 수 없는 경우에는 별도 예외로 구분합니다.

### 동시성 제어

동일 계좌에 대한 여러 이체 요청이 동시에 처리되는 상황을 고려하여 `SELECT ... FOR UPDATE`를 적용하고, 계좌 레코드를 잠근 후 잔액을 갱신하여 데이터 정합성을 유지하도록 구현했습니다.

### 데드락 방지

송금 계좌와 입금 계좌를 잠글 때 account_id가 작은 계좌부터 일관된 순서로 Lock을 획득하도록 구현하여 데드락 발생 가능성을 줄였습니다.

### 중복 이체 방지와 결과 미확정 처리

- UUID `request_id`와 UNIQUE 인덱스를 이용하여 같은 요청의 중복 거래 저장 제한
- 같은 ID와 같은 이체 내용으로 재시도하면 기존 성공 거래 ID 반환
- 같은 ID에 다른 사용자·계좌·금액이 전달되면 요청 거부
- Commit 결과 등을 확정할 수 없으면 `TransferOutcomeUnknownException`으로 구분
- GUI에서 요청 ID와 계좌·금액을 로컬에 보관하고, 재실행 후에도 동일 ID로 재시도할 수 있도록 처리
- 로컬 요청 파일에는 비밀번호를 저장하지 않음

멱등성을 유지하려면 재시도할 때 같은 요청 ID를 사용해야 합니다. 새 ID를 생성하면 별개의 이체 요청으로 취급됩니다.

### 비밀번호 해시 저장

회원 비밀번호와 계좌 비밀번호를 `PBKDF2-HMAC-SHA256`으로 해시하여 저장합니다. 비밀번호마다 16바이트 랜덤 salt를 생성하고 600,000회 반복하여 256비트 해시를 계산합니다.

기존 평문 데이터는 `main.MigratePasswords`로 변환하며, `password_encoding`으로 변환 여부를 구분합니다. 로그인 및 이체 시에는 입력값과 저장된 해시를 검증합니다.

### 인덱스 적용

- `(from_account_id, t_created_at)`: 출금 거래 및 기간 조회
- `(to_account_id, t_created_at)`: 입금 거래 및 기간 조회
- `(from_account_id, business_date)`: 일일 이체 금액 합산
- `request_id` UNIQUE: 중복 요청 제한

실행 계획과 조회 시간을 확인할 수 있는 `sql/02_check_and_measure.sql`을 포함했습니다. 인덱스 적용에 따른 성능 향상 수치는 아직 측정하지 않았습니다.

---

# 기술 스택

- Java 21: 최신 문법이며 장기 지원이 가능한 버전을 택해 안정적인 개발 환경을 확보했습니다.
- Java Swing
- JDBC: Java에서 DB에 접근하기 위한 표준 API로, 트랜잭션을 직접 제어해 원자성을 확보했습니다.
- MySQL: 관계형 DB의 제약 조건과 트랜잭션, 잠금 기능을 활용해 정합성을 유지하기 위해 선택했습니다.
- Maven: MySQL Connector/J 의존성 관리를 위해 사용했습니다.
- Git / GitHub

---

# 프로젝트 구조

```text
├─ src
│  ├─ dao
│  ├─ domain
│  ├─ exception
│  ├─ gui
│  ├─ main
│  ├─ service
│  └─ util
├─ tests
│  ├─ BankUnitTests.java
│  └─ BankMysqlTests.java
├─ tools
│  └─ check.ps1
├─ sql
│  ├─ 01_migrate_existing.sql
│  └─ 02_check_and_measure.sql
├─ schema.sql
└─ pom.xml
```

---

# 데이터베이스 설계

## users

- user_id
- login_id
- password: 비밀번호 해시
- password_encoding: 저장 형식
- name

## accounts

- account_id
- user_id
- account_number
- balance: BIGINT
- account_password: 계좌 비밀번호 해시
- password_encoding: 저장 형식
- status
- one_time_limit
- daily_limit
- created_at

## transactions

- transaction_id
- from_account_id
- to_account_id
- amount
- type
- t_status
- t_created_at
- request_id: 중복 요청 식별 UUID
- request_user_id: 이체 요청 사용자
- business_date: 일일 한도 계산 기준일

---

# 실행 방법

## 요구 환경

- Java 21
- MySQL
- Maven

## 1. 데이터베이스 생성

MySQL에서 `transfer_db` 데이터베이스를 생성합니다.

```sql
CREATE DATABASE transfer_db;
USE transfer_db;
```

새 DB에서는 프로젝트 루트의 `schema.sql`을 실행한 뒤, **`sql/01_migrate_existing.sql`까지 순서대로 실행**합니다. 현재 `schema.sql`은 초기 스키마이므로 이것만 실행하면 최신 코드에 필요한 컬럼과 인덱스가 빠집니다.

기존 DB를 업그레이드할 때는 앱을 종료하고 백업한 뒤 `sql/01_migrate_existing.sql`을 적용합니다. 이 SQL은 1회 적용용이며, 이미 적용한 DB에서는 다시 실행하지 않습니다. 일부만 반영된 DB는 `SHOW CREATE TABLE`과 대조해 필요한 구문만 실행합니다.

## 2. 데이터베이스 연결 설정

`src/util/DBConnection.java`에서 로컬 MySQL 환경에 맞게 접속 정보를 설정합니다.

```java
private static final String URL = "jdbc:mysql://localhost:3306/transfer_db";
private static final String USER = "root";
private static final String PASSWORD = "비밀번호";
```

`PASSWORD`는 실행 시 본인의 로컬 MySQL 비밀번호로 변경해야 합니다.

> 실제 데이터베이스 비밀번호는 GitHub에 커밋하지 않도록 주의하세요.

## 3. Maven 의존성 로드

프로젝트의 `pom.xml`을 Maven 프로젝트로 로드하면 MySQL Connector/J 의존성이 자동으로 설치됩니다.

## 4. 기존 비밀번호 변환

기존 평문 비밀번호가 있는 DB는 앱을 종료하고 백업한 상태에서 `src/main/MigratePasswords.java`를 실행합니다. IntelliJ 실행 구성의 프로그램 인수에 `--confirm-backup`을 지정합니다.

PowerShell에서는 다음 명령으로 실행할 수도 있습니다. JDK 21과 Maven이 PATH에 있어야 합니다.

```powershell
.\tools\check.ps1 -Mode Migrate
```

이미 변환된 행은 건너뜁니다. 데이터가 없는 새 DB는 변환할 비밀번호가 없으므로 이 단계를 생략할 수 있습니다.

## 5. 애플리케이션 실행

`src/main/Main.java`를 실행하면 애플리케이션이 시작됩니다.

---

# 테스트 및 검증

## DB 없이 실행하는 테스트

프로젝트 루트의 PowerShell에서 실행합니다.

```powershell
.\tools\check.ps1 -Mode Unit
```

비밀번호 해시, 요청 검증, 요청 ID 재사용 및 트랜잭션 오류 처리 분기를 검사합니다. JDBC 연결과 DAO를 대체하는 테스트이므로 실제 DB 잠금과 Rollback 동작을 검증하는 테스트는 아닙니다.

## MySQL 통합 테스트

별도의 `transfer_db_test` DB를 생성하고 `schema.sql` → `sql/01_migrate_existing.sql` 순서로 적용합니다. 테스트는 데이터를 생성·변경·정리하므로 실제 사용 DB와 분리합니다. DB 이름은 `_test`로 끝나야 합니다.

```powershell
$env:BANK_TEST_DB_URL = "jdbc:mysql://localhost:3306/transfer_db_test"
$env:BANK_TEST_DB_USER = "root"
$env:BANK_TEST_DB_PASSWORD = "테스트_DB_비밀번호"
.\tools\check.ps1 -Mode Mysql
```

동시 이체, 일일 한도 경쟁, 동일 요청 재시도, 부분 실패 Rollback, BIGINT 저장 등을 확인하는 코드를 포함했습니다. 테스트 종료 시 자체 생성 데이터를 정리합니다.

> 현재 테스트는 JUnit이 아닌 `main` 기반 검증 코드입니다. `mvn test`만으로 위 테스트가 실행되지는 않습니다. MySQL 통합 테스트 및 대량 데이터 성능 측정의 통과 결과는 아직 기록하지 않았습니다.

---

# 실행 화면

- 로그인
- 회원가입
- 메인 화면
- 계좌 조회
- 이체
- 거래내역 조회

---

# 프로젝트에서 배운 점

- Swing 화면 처리, 이체 로직, JDBC 데이터 접근을 분리해 코드 변경 영향 범위를 줄이는 구조를 익혔습니다.
- 잔액 부족 체크와 이체 가능 여부 판단을 서비스 계층에 두어, 화면이나 DB 접근 로직과 분리해 구현했습니다.
- 출금·입금·거래내역 저장을 하나의 트랜잭션으로 묶고, SELECT ... FOR UPDATE로 동시 이체 중 잔액 불일치 위험을 줄이는 방법을 익혔습니다.

---

# 향후 개선 사항

- 비밀번호 정책과 인증 실패 횟수 제한 보강: PBKDF2 해시 저장에 더해 로그인 및 계좌 비밀번호 반복 시도에 대한 정책을 보완할 예정입니다.
- 계좌 점유 제한 정책 적용: 장시간 Lock 유지 시 다른 거래가 지연될 수 있으므로 Lock 점유 시간을 줄이는 방향으로 개선할 예정입니다.
- 테스트 실행 자동화: 현재 main 기반 검증 코드를 JUnit과 CI에 통합하고, 실제 MySQL 동시성·장애 시나리오의 실행 결과를 기록할 예정입니다.
- 실행 계획 및 성능 측정: 대량 거래 데이터에서 커서 조회와 인덱스의 효과를 측정하고 개선할 예정입니다.
- 운영 편의성 개선: 이상 거래 확인, 관리자 페이지, 거래 검색, 계좌상태 변경, 사용자 관리 등 운영 편의성을 높이고자 합니다.
- Spring Boot 기반 웹 애플리케이션으로 확장: 웹 서비스로 전환하여 클라이언트와 서버를 분리하고 웹 환경에서 이체 서비스를 제공하도록 확장할 수 있습니다.

