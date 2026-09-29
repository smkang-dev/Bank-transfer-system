-- 조회 전용 예시. 계좌 ID/기간은 실제 측정 대상으로 바꾼다.
SELECT VERSION(), DATABASE(), @@session.time_zone, @@system_time_zone;
SHOW CREATE TABLE users;
SHOW CREATE TABLE accounts;
SHOW CREATE TABLE transactions;
SHOW INDEX FROM transactions;

SELECT password_encoding,COUNT(*) FROM users GROUP BY password_encoding;
SELECT password_encoding,COUNT(*) FROM accounts GROUP BY password_encoding;
SELECT COUNT(*) AS invalid_accounts FROM accounts
 WHERE balance<0 OR one_time_limit<0 OR daily_limit<0;

-- 요청 결과 조회: 실제 UUID 입력. 금액 변경 없이 조회만 한다.
SELECT transaction_id,request_id,request_user_id,from_account_id,to_account_id,amount,t_status
FROM transactions WHERE request_id='실제-요청-UUID';

-- 성능 비교는 동일 DB 복사본·데이터·서버·반복 횟수에서 실행.
-- EXPLAIN ANALYZE는 MySQL 8.0.18 이상. 아래 SELECT를 실제 실행하여 읽는다.
SET @account_id=1;
EXPLAIN ANALYZE
SELECT COALESCE(SUM(amount),0) FROM transactions
WHERE from_account_id=@account_id AND type='TRANSFER' AND t_status='SUCCESS'
  AND DATE(t_created_at)=CURDATE();

-- 일일 한도 기준을 business_date로 명시한 변경 쿼리.
-- 자정 부근 거래는 기준 시각 차이로 위 쿼리와 대상 행이 다를 수 있다.
EXPLAIN ANALYZE
SELECT COALESCE(SUM(amount),0) FROM transactions
WHERE from_account_id=@account_id AND business_date=CURDATE()
  AND type='TRANSFER' AND t_status='SUCCESS';

EXPLAIN ANALYZE
SELECT * FROM transactions
WHERE (from_account_id=@account_id OR to_account_id=@account_id)
  AND t_created_at>='2026-01-01' AND t_created_at<'2027-01-01'
ORDER BY t_created_at DESC,transaction_id DESC LIMIT 51;
-- OR 조건의 실행 계획/정렬 비용은 데이터 분포에 따라 다르다.
-- 인덱스를 추가했다는 사실만으로 성능 개선을 주장하지 않는다.
